(ns dagger.scheduler
  (:require [tick.core :refer [minutes seconds]]
            [tick.timeline :refer [timeline periodic-seq]]
            [tick.clock :refer [now]]
            [tick.schedule])
  (:import java.util.concurrent.locks.ReentrantLock))

(defn perform-job-if-no-others-are-running [f]
  (let [lock (new ReentrantLock)]
    (fn [tick]
      (when (.tryLock lock)
        (f tick)
        (.unlock lock)))))

(defn schedule-every-30s [f]
  (let [every-30s (timeline (periodic-seq (now) (seconds 30)))
        schedule (tick.schedule/schedule f every-30s)]
    (tick.schedule/start schedule (tick.clock/clock-ticking-in-seconds))
    schedule))
