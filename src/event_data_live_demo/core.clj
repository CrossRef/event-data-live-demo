(ns event-data-live-demo.core
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as server]
            [config.core :refer [env]]
            [compojure.core :refer [defroutes GET POST]]
            [ring.util.response :as ring-response]
            [ring.middleware.params :as middleware-params]
            [ring.middleware.content-type :as middleware-content-type]
            [liberator.core :refer [defresource]]
            [clj-time.core :as clj-time]
            [event-data-common.jwt :as jwt]
            [overtone.at-at :as at-at]
            [clojure.core.async :as async]
            [ring.middleware.resource :as middleware-resource])
  (:import
           [java.net URL MalformedURLException InetAddress]
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecords])

  (:gen-class))

; Websocket things
(def event-channel-hub (atom {}))
(def status-channel-hub (atom {}))

(def schedule-pool (at-at/mk-pool))

(def latest-log-record-by-type
  (atom {}))

(def latest-log-record (atom nil))

(defn store-latest-log-record
  "Store the most recent log record of each type."
  [log-record-json]
  (let [parsed (json/read-str log-record-json :key-fn keyword)
        ; Key by service/component/facet/partition
        k ((juxt :s :c :f :p) parsed)]
    (reset! latest-log-record parsed)
    (swap! latest-log-record-by-type #(assoc % k parsed))))

(defn broadcast
  "Send event to all websocket listeners."
  [channel-hub-promise event-json]
  ; Heartbeat is sent through pubsub. Don't rebroadcast it.
  (try
    (let [hub @channel-hub-promise
          num-listeners (count hub)]

      (doseq [[channel channel-options] hub]
        (server/send! channel event-json)))

    (catch Exception e (log/error "Error in broadcasting to websocket listeners" (.getMessage e)))))

(defresource status-snapshot-handler
  :available-media-types ["application/json"]
  :handle-ok 
  {:latest @latest-log-record
   :latest-by-type @latest-log-record-by-type})

(defn events-socket-handler [request]
  (server/with-channel request channel   
    (server/on-close channel (fn [status]
                               (swap! event-channel-hub dissoc channel)))

    (server/on-receive channel (fn [data]
                                 (swap! event-channel-hub assoc channel {})))))

(defn status-socket-handler [request]
  (server/with-channel request channel    
      (server/on-close channel (fn [status]
                                 (swap! status-channel-hub dissoc channel)))

      (server/on-receive channel (fn [data]
                                   (swap! status-channel-hub assoc channel {})))))

(defroutes app-routes
  (GET "/events-socket" [] events-socket-handler)
  (GET "/status-socket" [] status-socket-handler)
  (GET "/status-snapshot" [] status-snapshot-handler))

(def app
  ; Delay construction to runtime for secrets config value.
  (delay
    (-> app-routes
       middleware-params/wrap-params
       (middleware-resource/wrap-resource "public")
       (middleware-content-type/wrap-content-type))))

(defn ingest-kafka
  [topic-name callback]
  (let [consumer (KafkaConsumer. {
         "bootstrap.servers" (:global-kafka-bootstrap-servers env)     
         ; Give every process a separate group so it gets everything.
         "group.id"  (str "live-demo-" topic-name "-" (System/currentTimeMillis))
         "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"
         "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"
         "auto.offset.reset" "earliest"})]
       (log/info "Subscribing to" topic-name)
       (.subscribe consumer (list topic-name))
       (log/info "Subscribed to" topic-name "got" (count (or (.assignment consumer) [])) "assigned partitions")
       (loop []
         (let [^ConsumerRecords records (.poll consumer (int 10000))]
           (doseq [^ConsumerRecords record records]
             ; Don't deserialize JSON, just send it out.
             (callback (.value record))))
          (recur))))

(defn run-server []
  (let [port (Integer/parseInt (:live-port env))]

    ; Listen on pubsub and send to all listening websockets.
    (async/thread
      (log/info "Start Status listener in thread")
      (try 
        
        (ingest-kafka
          (:global-status-topic env)
          (fn [log-record] (store-latest-log-record log-record)
                           (broadcast status-channel-hub log-record)))

        (catch Exception e (log/error "Error in Topic listener " (.getMessage e))))
      (log/error "Stopped listening to Topic"))

    (async/thread
      (log/info "Start Event listener in thread")
      (try 
        (ingest-kafka (:global-bus-output-topic env) (partial broadcast event-channel-hub))
        (catch Exception e (log/error "Error in Topic listener " (.getMessage e))))
      (log/error "Stopped listening to Topic"))

    (log/info "Start server on " port)
    (server/run-server @app {:port port})))

(defn -main
  [& args]
  (run-server))
