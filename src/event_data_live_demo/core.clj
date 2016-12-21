(ns event-data-live-demo.core
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log])
  (:require [org.httpkit.server :as server]
            [config.core :refer [env]]
            [compojure.core :refer [defroutes GET POST]]
            [ring.util.response :as ring-response]
            [ring.middleware.params :as middleware-params]
            [ring.middleware.content-type :as middleware-content-type]
            [liberator.core :refer [defresource]]
            [clj-time.core :as clj-time]
            [event-data-common.jwt :as jwt]
            [event-data-common.storage.redis :as redis]
            [event-data-common.storage.store :as store]
            [event-data-common.status :as status]
            [overtone.at-at :as at-at]
            [clojure.core.async :as async]
            [ring.middleware.resource :as middleware-resource])
  (:import
           [java.net URL MalformedURLException InetAddress])
  (:gen-class))

; Websocket things
(def channel-hub (atom {}))
(def pubsub-channel-name "__live_demo__broadcast")
(def schedule-pool (at-at/mk-pool))

(def redis-prefix "live_demo")
(def default-redis-db-str "0")

(def redis-store
  "A redis connection for storing subscription and short-term information."
  (delay 
    (try
      (redis/build redis-prefix (:redis-host env) (Integer/parseInt (:redis-port env)) (Integer/parseInt (get env :redis-db default-redis-db-str)))
      (catch Exception e (log/error "Failed to create Redis connection" (.getMessage e))))))

(defn broadcast
  "Send event to all websocket listeners."
  [data]
  (try
    (let [hub @channel-hub]
    (log/info "Broadcast to " (count hub))
      (doseq [[channel channel-options] hub]
        (server/send! channel data)))
    (catch Exception e (log/error "Error in broadcasting to websocket listeners " (.getMessage e)))))

; "Accept POSTed Events"
(defresource events
  []
  :allowed-methods [:post]
  :available-media-types ["application/json"]
  :authorized? (fn [ctx]
                ; sub must be supplied to post.
                (-> ctx :request :jwt-claims))

    :post! (fn [ctx]
      (let [body (-> ctx :request :body slurp)]
        (status/send! "live-demo" "event" "received" 1)
        (redis/publish-pubsub @redis-store pubsub-channel-name body))))

(defn socket-handler [request]
  (server/with-channel request channel
    (let [; source-filter is either the source id or nil for everything
          source-filter (get-in request [:query-params "source_id"])]
    
      (server/on-close channel (fn [status]
                                 (swap! channel-hub dissoc channel)))

      (server/on-receive channel (fn [data]
                                   (swap! channel-hub assoc channel {}))))))

(defroutes app-routes
  (GET "/socket" [] socket-handler)
  (POST "/events" [] (events)))

(def app
  ; Delay construction to runtime for secrets config value.
  (delay
    (-> app-routes
       middleware-params/wrap-params
       (jwt/wrap-jwt (:jwt-secrets env))
       (middleware-resource/wrap-resource "public")
       (middleware-content-type/wrap-content-type))))

(defn run-server []
  (let [port (Integer/parseInt (:port env))]
    (log/info "Start heartbeat")
    (at-at/every 10000 #(status/send! "live-demo" "heartbeat" "tick" 1) schedule-pool)

    ; Listen on pubsub and send to all listening websockets.
    (async/thread
      (log/info "Start redis pubsub listener in thread")
      (try 
        (redis/subscribe-pubsub @redis-store pubsub-channel-name #(broadcast %))
        (catch Exception e (log/error "Error in redis pubsub listener " (.getMessage e))))
      (log/error "Stopped listening to redis pubsub"))

    (log/info "Start server on " port)
    (server/run-server @app {:port port})))

(defn -main
  [& args]
  (run-server))
