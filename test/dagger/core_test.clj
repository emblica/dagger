(ns dagger.core-test
  (:require [clojure.test :refer :all]
            [dagger.core :refer :all]))

(deftest predicates
  (testing "no-running-runs?"
    (is (not (no-running-runs? [{:status :fatal} {:status :running} {:status :done}]))) ;=> false
    (is (no-running-runs? [{:status :failed} {:status :waiting} {:status :done}])) ;=> true
    (is (not (no-running-runs? [{:status :failed} {:status :running} {:status :done}])))) ;=> false

  (testing "no-fatal-runs?"
    (is (not (no-fatal-runs? [{:status :fatal} {:status :running} {:status :done}]))) ;=> false
    (is (no-fatal-runs? [{:status :failed} {:status :running} {:status :done}]))) ;=> true

  (testing "running?"
    (is (not (running? {:status :fatal}))) ;=> false
    (is (not (running? {:status "fatal"}))) ;=> false
    (is (running? {:status :running})) ;=> true
    (is (running? {:status "running"}))) ;=> true

  (testing "fatal-run?"
    (is (not (fatal-run? {:status :waiting}))) ;=> false
    (is (fatal-run? {:status :fatal}))) ;=> true

  (testing "safe-to-run?"
    (is (manifest-safe-to-run? nil [{:status :failed} {:status :done} {:status :waiting}]))
    (is (not (manifest-safe-to-run? nil [{:status :fatal} {:status :done} {:status :waiting}])))
    (is (not (manifest-safe-to-run? nil [{:status :failed} {:status :running} {:status :waiting}])))
    (is (not (manifest-safe-to-run? nil [{:status :fatal} {:status :running} {:status :waiting}])))))
