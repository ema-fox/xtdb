(ns crux.http-server
  (:require [clojure.edn :as edn]
            [crux.db :as db]
            [crux.doc :as doc]
            [crux.io :as cio]
            [crux.tx :as tx]
            [crux.query :as q]
            [crux.kv-store :as kvs]
            [ring.adapter.jetty :as j]
            [ring.middleware.params :as p]
            [ring.util.request :as req])
  (:import [java.io Closeable]))

(defn exception-response [^Exception e status]
  {:status status
   :headers {"Content-Type" "text/plain"}
   :body (str
          (.getMessage e) "\n"
          (ex-data e))})

(defn status [kvs db-dir]
  {:status 200
   :headers {"Content-Type" "application/edn"}
   :body (pr-str
          {:crux.kv-store/kv-backend (.getName (class kvs))
           :crux.kv-store/estimate-num-keys (kvs/count-keys kvs)
           :crux.kv-store/size (cio/folder-human-size db-dir)
           :crux.tx-log/tx-time (doc/read-meta kvs :crux.tx-log/tx-time)})})

(defn do-query [kvs request]
  (try
    (let [query (case (:request-method request)
                  :get (-> request
                           :query-params
                           (find "q")
                           last
                           edn/read-string)
                  :post (edn/read-string (req/body-string request)))]
      (try
        {:status 200
         :headers {"Content-Type" "application/edn"}
         :body (pr-str (q/q (q/db kvs) query))}
        (catch Exception e
          (if (= "Invalid input" (.getMessage e))
            (exception-response e 400) ;; Valid edn, invalid query
            (exception-response e 500))))) ;; Valid query; something internal failed
    (catch Exception e
      (exception-response e 400)))) ;; Invalid edn

(defn transact [tx-log request]
  (try
    (let [tx-op (edn/read-string (req/body-string request))]
      (try
        {:status 200
         :headers {"Content-Type" "application/edn"}
         :body (pr-str @(db/submit-tx tx-log [tx-op]))}
        (catch Exception e
          (if (= "Invalid input" (.getMessage e))
            (exception-response e 400) ;; Valid edn, invalid tx-op
            (exception-response e 500))))) ;; Valid tx-op; something internal failed
    (catch Exception e
      (exception-response e 400)))) ;; Invalid edn

(defn check-path [request valid-paths valid-methods]
  (let [path (req/path-info request)
        method (:request-method request)]
    (and (some #{path} valid-paths)
         (some #{method} valid-methods))))

(defn handler [kvs tx-log db-dir request]
  (cond
    (check-path request ["/"] [:get])
    (status kvs db-dir)

    (check-path request ["/q" "/query"] [:get :post])
    (do-query kvs request)

    (check-path request ["/tx-log"] [:post])
    (transact tx-log request)

    :default
    {:status 400
     :headers {"Content-Type" "text/plain"}
     :body "Unsupported method on this address."}))

(defn ^Closeable create-server
  ([kvs tx-log db-dir]
   (create-server kvs tx-log 3000))

  ([kvs tx-log db-dir port]
   (let [server (j/run-jetty (p/wrap-params (partial handler kvs tx-log db-dir))
                             {:port port
                              :join? false})]
     (println (str "HTTP server started on port " port))
     (reify Closeable (close [_] (.stop server))))))
