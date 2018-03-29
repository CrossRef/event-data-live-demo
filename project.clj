(defproject event-live-demo "0.1.17"
  :description "Event Data Live Demo"
  :url "http://eventdata.crossref.org/"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[clj-time "0.12.2"]
                 [com.auth0/java-jwt "2.2.1"]
                 [compojure "1.5.1"]
                 [crossref-util "0.1.14"]
                 [event-data-common "0.1.43"]
                 [http-kit "2.3.0-alpha5"]
                 [http-kit.fake "0.2.1"]
                 [liberator "0.14.1"]
                 [metosin/scjsv "0.4.0"]
                 [org.apache.httpcomponents/httpclient "4.5.2"]
                 [org.apache.logging.log4j/log4j-core "2.6.2"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.eclipse.jetty/jetty-server "9.4.0.M0"]
                 [org.slf4j/slf4j-simple "1.7.21"]
                 [overtone/at-at "1.2.0"]
                 [redis.clients/jedis "2.8.0"]
                 [ring "1.5.0"]
                 [ring/ring-jetty-adapter "1.5.0"]
                 [ring/ring-mock "0.3.0"]
                 [ring/ring-servlet "1.5.0"]
                 [robert/bruce "0.8.0"]
                 [yogthos/config "0.8"]
                 [org.apache.kafka/kafka-clients "0.10.2.0"]]
  :main ^:skip-aot event-data-live-demo.core
  :target-path "target/%s"
  :test-selectors {:default (constantly true)
                   :unit :unit
                   :component :component
                   :integration :integration
                   :all (constantly true)}
  :jvm-opts ["-Duser.timezone=UTC"]
  :profiles {:uberjar {:aot :all}
             :prod {:resource-paths ["config/prod"]}
             :dev  {:resource-paths ["config/dev"]}})
