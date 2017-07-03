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
            [event-data-common.status :as status]
            [overtone.at-at :as at-at]
            [clojure.core.async :as async]
            [ring.middleware.resource :as middleware-resource])
  (:import
           [java.net URL MalformedURLException InetAddress]
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecords])

  (:gen-class))

; Websocket things
(def channel-hub (atom {}))
(def schedule-pool (at-at/mk-pool))

; About an hour's worth.
(def num-recent-events 5000)
(def recent-events (atom (list)))
(defn shift-recent-events [event]
  (swap! recent-events (fn [events] (doall (take num-recent-events (conj events event))))))

(defn broadcast
  "Send event to all websocket listeners."
  [event-json]
  ; Heartbeat is sent through pubsub. Don't rebroadcast it.
  (shift-recent-events event-json)
  (try
    (let [hub @channel-hub
          num-listeners (count hub)]

    (when-not (zero? num-listeners)
      (log/info "Broadcast to" num-listeners))

      (doseq [[channel channel-options] hub]
        (server/send! channel event-json)))

    (catch Exception e (log/error "Error in broadcasting to websocket listeners" (.getMessage e)))))

(defn socket-handler [request]
  (server/with-channel request channel
    (let [; source-filter is either the source id or nil for everything
          source-filter (get-in request [:query-params "source_id"])]
    
      (server/on-close channel (fn [status]
                                 (swap! channel-hub dissoc channel)))

      (server/on-receive channel (fn [data]
                                   (swap! channel-hub assoc channel {}))))))

(defresource events
  "Get a few recent Events"
  []
  :allowed-methods [:get]
  :available-media-types ["application/json"]
  :handle-ok (fn [ctx]
               @recent-events))

(defroutes app-routes
  (GET "/socket" [] socket-handler)
  (GET "/events" [] events))

(def app
  ; Delay construction to runtime for secrets config value.
  (delay
    (-> app-routes
       middleware-params/wrap-params
       (middleware-resource/wrap-resource "public")
       (middleware-content-type/wrap-content-type))))

(defn ingest-kafka
  [callback]
  (let [properties (java.util.Properties.)]
     (.put properties "bootstrap.servers" (:global-kafka-bootstrap-servers env))
     
     ; Give every process a separate group so it gets everything.
     (.put properties "group.id"  (str "live-demo" (System/currentTimeMillis)))
     (.put properties "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
     (.put properties "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    
     (.put properties "auto.offset.reset" "earliest")

     (let [consumer (KafkaConsumer. properties)
           topic-name (:global-bus-output-topic env)]
       (log/info "Subscribing to" topic-name)
       (.subscribe consumer (list topic-name))
       (log/info "Subscribed to" topic-name "got" (count (or (.assignment consumer) [])) "assigned partitions")
       (loop []
         (log/info "Polling...")
         (let [^ConsumerRecords records (.poll consumer (int 10000))]
           (log/info "Got" (.count records) "records." (.hashCode records))
           (doseq [^ConsumerRecords record records]
            (Thread/sleep 1000)
             ; Don't deserialize JSON, just send it out.
             (callback (.value record))))
          (recur)))))

(defn run-server []
  (let [port (Integer/parseInt (:live-port env))]
    (log/info "Start heartbeat")
    (at-at/every 10000 #(status/send! "live-demo" "heartbeat" "tick" 1) schedule-pool)

    ; Listen on pubsub and send to all listening websockets.
    (async/thread
      (log/info "Start Topic listener in thread")
      (try 
        (ingest-kafka broadcast)
        (catch Exception e (log/error "Error in Topic listener " (.getMessage e))))
      (log/error "Stopped listening to Topic"))

    (log/info "Start server on " port)
    (server/run-server @app {:port port})))

(defn -main
  [& args]
  (run-server))
