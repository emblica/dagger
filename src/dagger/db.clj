(ns dagger.db
  (:require [dagger.utils :as utils]
            [taoensso.timbre :as log]))




(def manifests (atom {:dags {}}))

(def runs (atom {}))


(defn update-task-with-job! [job]
  (swap!
    runs assoc-in [(-> job :labels :run)
                   :tasks
                   (-> job :labels :task)
                   :status]
                  (:status job)))

(defn update-tasks [k8s-jobs]
  (log/info "Load tasks into local state")
  (dorun (map update-task-with-job! k8s-jobs)))


(defn update-manifests [k8s-manifests]
  (log/info "Load manifests into local state")
  (swap! manifests assoc :dags (utils/index-by :name k8s-manifests)))


(defn update-runs [k8s-runs]
  (log/info "Load runs into local state")
  (reset! runs (utils/index-by :name k8s-runs)))

(defn manifests! []
  (->> @manifests
    :dags
    (vals)))


(defn runs-by-manifest! [manifest]
  (->> @runs
    (vals)
    (filter #(= (:manifest %) (:name manifest)))))
