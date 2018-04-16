(ns dagger.manifest
  (:require [yaml.core :as yaml]
            [jsonista.core :as j]
            [environ.core :refer [env]]))


(defn kube-env-kv [k v]
  {"name" k "value" v})

(defn ->kube-env [hm]
  (map kube-env-kv (keys hm) (vals hm)))


(def workload-container 0)
(def savestate-container 1)
(def load-container 0)

(def workload-container-mounts
  [{"name" "state" "mountPath" "/state"}
   {"name" "input" "mountPath" "/input"}
   {"name" "output" "mountPath" "/output"}])


(defn render-job [manifest task]
  (let [k8stask (-> task
                  (dissoc :id :status :namespace :depends))
        workload-env (concat
                      (->kube-env {:DAGGER_PARAMS (-> manifest :run :params j/write-value-as-string)})
                      (:env task))
        sidekick-env (->kube-env {:DAGGER_TASK (-> task j/write-value-as-string)
                                  :DAGGER_BUCKET (env :state-bucket)
                                  :TASK_NAME (-> task :name)
                                  :RUN_NAME (-> manifest :run :name)
                                  :MANIFEST_NAME (-> manifest :name)
                                  :AWS_REGION (env :aws-region)
                                  :AWS_ACCESS_KEY_ID (env :aws-access-key)
                                  :AWS_SECRET_ACCESS_KEY (env :aws-secret-key)})]
    (-> (yaml/from-file "job.template.yml")
      (assoc-in ["metadata" "name"] (str (name (:name task)) "-" (:id task)))
      (assoc-in ["metadata" "labels" "task-id"] (:id task))
      (assoc-in ["metadata" "labels" "task"] (:name task))
      (assoc-in ["metadata" "labels" "run"] (:name (:run manifest)))
      (assoc-in ["metadata" "labels" "priority"] (str (:priority (:run manifest))))
      (assoc-in ["metadata" "labels" "manifest"] (:name manifest))
      (assoc-in ["metadata" "namespace"] (:namespace task))
      (assoc-in ["spec" "template" "metadata" "namespace"] (:namespace task))
      (assoc-in ["spec" "template" "metadata" "labels" "task-id"] (:id task))
      (assoc-in ["spec" "template" "metadata" "labels" "task"] (:name task))
      (assoc-in ["spec" "template" "metadata" "labels" "run"] (:name (:run manifest)))
      (assoc-in ["spec" "template" "metadata" "labels" "priority"] (str (:priority (:run manifest))))
      (assoc-in ["spec" "template" "metadata" "labels" "manifest"] (:name manifest))
      (assoc-in ["spec" "template" "spec" "containers" workload-container] k8stask)
      (assoc-in
        ["spec" "template" "spec" "containers" workload-container "volumeMounts"]
        (concat
          (or (:volumeMounts k8stask) [])
          workload-container-mounts))
      (assoc-in ["spec" "template" "spec" "containers" workload-container "name"] "workload")
      (assoc-in ["spec" "template" "spec" "containers" workload-container "env"] workload-env)
      (assoc-in ["spec" "template" "spec" "containers" savestate-container "env"] sidekick-env)
      (assoc-in ["spec" "template" "spec" "initContainers" load-container "env"] sidekick-env)
      (yaml/generate-string))))
