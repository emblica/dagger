(ns dagger.k8s
  (:require [clojure.java.shell :refer [sh]]
            [jsonista.core :as j]
            [dagger.manifest :as manifest]
            [dagger.utils :as utils]
            [taoensso.timbre :as log]))


(def not-nil? (complement nil?))


(defn type-failed? [c] (= (:type c) "Failed"))
(defn type-complete? [c] (= (:type c) "Complete"))

(defn resolve-job-state [job]
  (let [status (-> job
                :status)
        conditions (:conditions status)]
    (cond
      (not-nil? (:failed status))
      :failed

      (= (->> conditions
          (filter type-failed?)
          first
          :status)
        "True")
      :failed
      (= (->> conditions
          (filter type-complete?)
          first
          :status)
        "True")
      :done
      :else :running)))


(defn resolve-name [job]
  (-> job
    :metadata
    :name))

(defn resolve-namespace [job]
  (-> job
    :metadata
    :namespace))


(defn full-job->jobstate [job]
  {:status (resolve-job-state job)
   :ns (resolve-namespace job)
   :name (resolve-name job)
   :labels (:labels (:metadata job))})

(defn full-manifest-resource->manifest [dm]
  (-> dm
    (:spec dm)
    (assoc :name (-> dm :metadata :name))))


(defn kube-cmd
 ([verb body] (kube-cmd verb body []))
 ([verb body flags]
  (as-> ["kubectl" verb "--all-namespaces" body "-o=json" flags] $
    (flatten $)
    (apply sh $)
    (:out $)
    (j/read-value $ (j/object-mapper {:decode-key-fn true})))))


(defn kube-apply [content]
  (let [{:keys [out err]} (sh "kubectl" "apply" "-o=json" "-f" "-" :in content)]
    (when (not (empty? err))
      (log/error "kube-apply" err))
    (j/read-value out (j/object-mapper {:decode-key-fn true}))))


(defn kube-patch [resource name content]
    (let [json (j/write-value-as-string content)
          {:keys [out err]} (sh "kubectl" "patch" resource name "--type=merge" "-o=json" "-p" json)]
      (when (not (empty? err))
        (do
          (log/error "kube-patch error" err)
          (throw (Exception. err))))
      (j/read-value out (j/object-mapper {:decode-key-fn true}))))

(defn kube-get [resource flags]
  (->> (kube-cmd "get" resource flags)
    :items))

(defn kube-get-jobs [flags]
  (->> (kube-get "jobs" flags)
    (map full-job->jobstate)))


(defn kube-get-manifests [flags]
  (->> (kube-get "manifests" flags)
    (map full-manifest-resource->manifest)))

(defn kube-get-runs [flags]
  (->> (kube-get "runs" flags)
    identity))

(defn uninitialized-runs! []
  (kube-get-runs ["-l" "initialized!=true"]))

(defn spec-with-uid [ob]
  (-> ob
    :spec
    (assoc :uid (-> ob :metadata :uid))))

(defn runs! []
  (->> (kube-get-runs ["-l" "initialized=true"])
    (map spec-with-uid)))

(defn done-runs! []
  (->> (kube-get-runs ["-l" "initialized=true"])
    (map spec-with-uid)
    (filter #(= "done" (:status %)))))


(defn kube-delete-with-ns! [resource namespace name]
  (let [{:keys [out err]}
        (sh "kubectl" "delete" "-n" namespace resource name "-o=name")]
    (when (not (empty? err))
      (log/error "kube-delete error" err))
    (not (empty? out))))

(defn kube-delete! [resource name]
  (let [{:keys [out err]} (sh "kubectl" "delete" resource name)]
    (when (not (empty? err))
      (log/error "kube-delete error" err))
    (not (empty? out))))

(def kube-delete-job! (partial kube-delete-with-ns! "jobs"))


(def jobs (partial kube-get-jobs ["-l" "jobscheduler=dagger"]))

(def manifests (partial kube-get-manifests []))

(defn apply-job! [manifest task]
  (-> (manifest/render-job manifest task)
    (kube-apply)))

(defn save-run! [spec run tasks]
  ; Save to database
  (let [full-run (assoc run :tasks tasks)
        updated-spec (utils/deep-merge {:spec full-run} spec)
        initialized-spec (assoc-in updated-spec
                           [:metadata :labels :initialized] "true")
        spec-json (j/write-value-as-string initialized-spec)]
    (kube-apply spec-json)))

(defn update-dr-with-job! [k8s-job]
  (kube-patch "dr"
    (-> k8s-job :labels :run)
    {:spec
      {:tasks {(-> k8s-job :labels :task) {:status (-> k8s-job :status)}}}}))


(defn update-runs! [k8s-runs]
  (log/info "Updating k8s runs with k8s state (runs)...")
  (doall (map update-dr-with-job! k8s-runs)))

(defn mark-as-running! [run]
  (log/info "Marking run as running!" (:name run))
  (kube-patch "dr" (:name run) {:spec {:status :running}}))


(defn mark-as-failed! [run]
  (log/info "Marking run as failed!" (:name run))
  (kube-patch "dr" (:name run) {:spec {:status :failed}}))

(defn mark-as-done! [run]
  (log/info "Marking run as done!" (:name run))
  (kube-patch "dr" (:name run) {:spec {:status :done}}))

(defn clean-done-jobs-and-runs! []
  (let [jobs (jobs)
        jobs-by-task-id (utils/index-by #(-> % :labels :task-id) jobs)
        runs (done-runs!)
        done-tasks (mapcat :tasks runs)]
    (->>
      (for [task done-tasks]
        (let [task-id (-> task second :id)
              job (get jobs-by-task-id task-id)]
          (some-> job
            (select-keys [:name :ns]))))
      (remove nil?)
      (map #(kube-delete-job! (:ns %) (:name %)))
      (doall))
    (doseq [run runs]
      (kube-delete! "dr" (:name run)))))



(defn clean-done-jobs []
  (->> (jobs)
    (filter #(= :done (:status %)))
    (pmap #(kube-delete-job! (:ns %) (:name %)))))

(defn task-scheduled! [manifest task]
  (kube-patch "dr"
    (:name (:run manifest))
    {:spec
      {:tasks {(:name task) {:status :running}}}}))

;(clojure.pprint/pprint (jobs))
