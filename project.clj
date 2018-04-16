(defproject dagger "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [com.stuartsierra/dependency "0.2.0"]
                 [metosin/jsonista "0.1.1"]
                 [io.forward/yaml "1.0.6"]
                 [tick "0.3.5"]
                 [environ "1.1.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [io.fabric8/kubernetes-client "3.1.10"]
                 [clj-ulid "0.1.0-SNAPSHOT"]]
  :plugins [[lein-environ "1.1.0"]]
  :main ^:skip-aot dagger.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
