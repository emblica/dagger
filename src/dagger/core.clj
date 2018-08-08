(ns dagger.core
  (:require [com.stuartsierra.dependency :as dep]
            [dagger.db :as db :refer [manifests runs]]
            [dagger.k8s :as k8s]
            [dagger.utils :refer [index-by mapmap now]]
            [clj-ulid :as ulid]
            [dagger.scheduler :as scheduler]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [taoensso.timbre :as log])
  (:gen-class))



(defn dependency-pairs [task]
  (->> task
    :depends
    (map
      (fn [dependency]
        [(:name task) dependency]))))


(defn manifest->graph [manifest]
  (let [tasks (:tasks manifest)]
    (as-> tasks $
      (mapcat dependency-pairs $)
      (reduce #(apply dep/depend %1 %2) (dep/graph) $))))


(defn manifest->work-order [manifest]
  (dep/topo-sort (manifest->graph manifest)))


(defn status? [v]
  #(= (keyword (:status %)) v))


(def fatal-run? (status? :fatal))
(def running? (status? :running))
(def done? (status? :done))
(def failed? (status? :failed))
(def waiting? (status? :waiting))


(def no-fatal-runs? (partial every? (complement fatal-run?)))
(def no-running-runs? (partial every? (complement running?)))

(def all-tasks-done? (partial every? done?))



(defn manifest-safe-to-run? [manifest runs]
  (and
    ; It can be failed, then somebody have done something
    (no-fatal-runs? runs)
    ; Never allow more than one run concurrently
    (no-running-runs? runs)))


(defn task-default-state [task]
  {:status :waiting
   :name (:name task)
   :id (ulid/ulid)})


(defn new-run [bundle]
  {:name (-> bundle :metadata :name)
   :manifest (-> bundle :metadata :labels :manifest)
   :status :waiting
   :priority (now)}) ; Current timestamp



(defn populate-run! [manifest spec]
  (log/info "Populate tasks into the run" (:name manifest))
  (let [task-states (->> manifest
                      :tasks
                      (index-by :name)
                      (mapmap task-default-state))]
    (k8s/save-run! spec (new-run spec) task-states)))


(def any-task-failed? (partial some failed?))
(def any-task-running? (partial some running?))

(defn free-task? [g done-tasks task]
  (let [done (into #{} (map :name done-tasks))
        deps (dep/transitive-dependencies g (:name task))]
    (clojure.set/superset? done deps)))


(defn maximum-parallel-task-running? [tasks g]
  (let [done-tasks (filter done? tasks)
        running-tasks (filter running? tasks)
        waiting-tasks (filter waiting? tasks)]
    (not (some (partial free-task? g done-tasks) waiting-tasks))
    true))

(defn start-task! [manifest task]
  (let [task-name (:name task)
        task-manifest (as-> manifest M
                        (:tasks M)
                        (index-by :name M)
                        (get M task-name))
        enriched-task-manifest (merge task task-manifest)]
    (log/info "Schedule and start new task into K8s:" task-name)
    (k8s/apply-job!
      manifest
      enriched-task-manifest)
    (log/info "Mark task as scheduled")
    (k8s/task-scheduled! manifest enriched-task-manifest)))

(defn task-by-name-f [name]
  (fn [tasks]
    (get tasks name)))


(defn step! [manifest run]
  (let [g (manifest->graph manifest)
        work-order (manifest->work-order manifest)
        enrich (apply juxt (map keyword work-order))
        enriched-tasks (enrich (-> run :tasks))
        done-tasks (filter done? enriched-tasks)]
    (log/info "Taking step in " (:name run))
    (log/info "Task execution order: " work-order)
    (cond
      (nil? run) true
      (empty? enriched-tasks) true
      (all-tasks-done? enriched-tasks) (k8s/mark-as-done! run)
      (any-task-failed? enriched-tasks) (k8s/mark-as-failed! run)
      ;(maximum-parallel-task-running? enriched-tasks g) (k8s/mark-as-running! run)
      :else (some->> enriched-tasks
              (remove done?)
              (remove running?)
              (filter (partial free-task? g done-tasks))
              first
              (start-task! (assoc manifest :run run))))))



(defn branch [input fl]
  (doall (map #(% input) fl)))

(defn find-first [pred col]
  (first (filter pred col)))


(defn step-first-waiting! [manifest runs]
  (some->> runs
    (find-first waiting?)
    (step! manifest)))

(defn find-manifest [manifests name]
  (find-first #(= (:name %) name) manifests))



(defn loop-runs! [{:keys [runs] :as manifest}]
  (log/info "Iterating through all runs of the manifest count:" (count runs))
  (let [runs (->> runs
               (remove done?)
               (remove failed?)
               (sort-by :priority))]
    (when-not
      (loop [[run runs] (branch runs [first rest])]
        (cond
          (nil? run) false ; end of loop
          (running? run) (step! manifest run)
          :else (recur (branch runs [first rest]))))
      (do
        (log/info "No running tasks in runs, stepping into next waiting task")
        (step-first-waiting! manifest runs)))))


(defn manifest-runs-clean? [{:keys [runs]}]
  (no-fatal-runs? runs))


(defn with-manifest! [manifests]
  (fn [spec]
    {:manifest (find-manifest manifests (-> spec :metadata :labels :manifest))
     :spec spec}))


(defn with-runs! [manifest]
  (assoc manifest :runs (db/runs-by-manifest! manifest)))

(defn initialize-runs! [manifests]
  (->> (k8s/uninitialized-runs!)
    (map (with-manifest! manifests))
    (map
      (fn [{:keys [manifest spec]}]
        (populate-run! manifest spec)))
    (doall)))

(defn has-gc-field? [run]
  (run :gc))

(def datetime (f/formatters :date-time))

(defn garbage-collectable? [run]
  (let [gc-timestamp (f/parse datetime (run :gc))]
    (t/before? gc-timestamp (t/now))))


(defn garbage-collect! [runs]
  (dorun (->> runs
           (filter done?)
           (filter has-gc-field?)
           (filter garbage-collectable?)
           (map #(k8s/kube-delete! "dr" (:name %))))))


(defn main-loop []
  (do
    (log/info "Load manifests from k8s into memory")
    (db/update-manifests (k8s/manifests))
    (log/info "Initialize uninitialized runs (from loaded manifests)")
    (initialize-runs! (db/manifests!))
    (log/info "Garbage collection")
    (garbage-collect! (k8s/runs!))
    (log/info "Update state with already running jobs and update states back to k8s runs")
    (branch (k8s/jobs)
      [db/update-tasks
       k8s/update-runs!])
    (log/info "Load runs - (back from k8s)")
    (db/update-runs (k8s/runs!))
    (let [clean-manifests (->> (db/manifests!)
                               (map with-runs!)
                               (filter manifest-runs-clean?))]
      (log/info "Iterate through all valid manifests. count:" (count clean-manifests))
      (doall (map loop-runs! clean-manifests)))))

(defn -main
  "Schedule main loop"
  [& args]
  (let [non-concurrent-main-loop
        (scheduler/perform-job-if-no-others-are-running
          (fn [tick]
            (main-loop)))]
    (scheduler/schedule-every-30s non-concurrent-main-loop)))
