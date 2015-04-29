(ns spatialista.middlewares.cookies
  "Stateful access to cookie values"
  (:refer-clojure :exclude [get remove])
  (:require [spatialista.utils.crypt :as crypt])
  (:use ring.middleware.cookies))

(declare ^:dynamic *cur-cookies*)
(declare ^:dynamic *new-cookies*)

(defn- k->s
  "Returns the keyword k as a string."
  [k]
  (if (string? k) k (subs (str k) 1)))

(defn put!
  "Add a new cookie whose name is k and has the value v. If v is a string
  a cookie map is created with :path '/'. To set custom attributes, such as
  \"expires\", provide a map as v. Stores all keys as strings."
  [k v]
  (let [props (if (map? v)
                v
                {:value v :path "/"})]
    (swap! *new-cookies* assoc (k->s k) props)))

(defn get
  "Get the value of a cookie from the request. k can either be a string or keyword.
   If this is a signed cookie, use get-signed, otherwise the signature will not be
   checked."
  ([k] (get k nil))
  ([k default]
   (let [str-k (k->s k)]
     (if-let [v (or (get-in @*new-cookies* [str-k :value])
                    (get-in *cur-cookies* [str-k :value]))]
       v
       default))))

(defn signed-name
  "Construct the name of the signing cookie using a simple suffix."
  [k]
  (str (k->s k) "__s"))

(defn put-signed!
  "Adds a new cookie whose name is k and has the value v. In addition,
  adds another cookie that checks the authenticity of 'v'. Sign-key
  should be a secret that's user-wide, session-wide or site wide (worst)."
  [sign-key k v]
  (let [actual-v (if (map? v) (:value v) v)]
    (put! k v)
    (put! (signed-name k)
          (let [signed-v (crypt/sha1-sign-hex sign-key actual-v)]
            (if (map? v) ;; If previous value was a map with other attributes,
              (assoc v :value signed-v) ;; Place the signed value in a similar map,
              signed-v))))) ;; Otherwise just signed value.

(defn get-signed
  "Get the value of a cookie from the request using 'get'. Verifies that a signing
   cookie also exists. If not, returns default or nil. "
  ([sign-key k] (get-signed sign-key k nil))
  ([sign-key k default]
     (let [v (get k)
           stored-sig (get (signed-name k)) ]
       (if (or (nil? stored-sig) ;; If signature not available,
               (nil? v) ;; or value is not found,
               (not= (crypt/sha1-sign-hex sign-key v) stored-sig)) ;; or sig mismatch,
         default ;; return default.
         v)))) ;; otherwise return the value.

(defn spatialista-cookies [handler]
  (fn [request]
    (binding [*cur-cookies* (:cookies request)
              *new-cookies* (atom {})]
      (when-let [final (handler request)]
        (assoc final :cookies (merge (:cookies final) @*new-cookies*))))))

(defn wrap-spatialista-cookies
  "A stateful layer over wrap-cookies."
  [handler]
  (-> handler
    (spatialista-cookies)
    (wrap-cookies)))

(defn wrap-spatialista-cookies*
  "A stateful layer over wrap-cookies. Expects that wrap-cookies has already
   been called."
  [handler]
  (spatialista-cookies handler))
