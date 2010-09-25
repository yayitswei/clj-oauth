(ns 
    #^{:author "Matt Revelle"
       :doc "OAuth client library for Clojure."} 
  oauth.client
  (:require [oauth.digest :as digest]
            [oauth.signature :as sig]
            [com.twinql.clojure.http :as http])
  (:use [clojure.contrib.string :only [as-str join split upper-case]]))

(declare success-content
         authorization-header)

(defstruct #^{:doc "OAuth consumer"} consumer
           :key
           :secret
           :request-uri
           :access-uri
           :authorize-uri
           :signature-method)

(defn check-success-response [m]
  (let [code (:code m)]
    (if (or (< code 200)
              (>= code 300))
      (throw (new Exception (str "Got non-success response " code ".")))
      m)))

(defn success-content [m]
  (:content
     (check-success-response m)))

(defn make-consumer
  "Make a consumer struct map."
  [key secret request-uri access-uri authorize-uri signature-method]
  (struct consumer 
          key
          secret
          request-uri 
          access-uri 
          authorize-uri 
          signature-method))

;;; Parse form-encoded bodies from OAuth responses.
(defmethod http/entity-as :urldecoded [entity as status]
  (into {}
        (map (fn [kv]
               (let [[k v] (split #"=" kv)]
		 [(keyword (sig/url-decode k)) (sig/url-decode v)]))
             (split #"&" (http/entity-as entity :string status)))))

(defn request-token
  "Fetch request token for the consumer."
  ([consumer & optional-params]
     (let [unsigned-params (merge (sig/oauth-params consumer) (first optional-params))
           signature (sig/url-encode (sig/sign consumer
                                               (sig/base-string "POST" 
                                                                (:request-uri consumer)
                                                                unsigned-params)))
           params (assoc unsigned-params :oauth_signature signature)]
       (success-content
        (http/post (:request-uri consumer)
                   :headers {"Authorization" (authorization-header (assoc params
                                                                     :oauth_signature signature))}
                   :parameters (http/map->params {:use-expect-continue false})
                   :as :urldecoded)))))

(defn user-approval-uri
  "Builds the URI to the Service Provider where the User will be prompted
to approve the Consumer's access to their account."
  ([consumer token & optional-params]
     (.toString (http/resolve-uri (:authorize-uri consumer) 
                                  (merge {:oauth_token (:oauth_token token)} (first optional-params))))))

(defn access-token 
  "Exchange a request token for an access token.
  When provided with two arguments, this function operates as per OAuth 1.0.
  With three arguments, a verifier is used:

      http://wiki.oauth.net/Signed-Callback-URLs

  This allows Twitter's PIN pass-back:

      http://apiwiki.twitter.com/Authentication"
  ([consumer request-token]
     (access-token consumer request-token nil))
  ([consumer request-token verifier]
     (let [unsigned-params (sig/oauth-params consumer
                                             (:oauth_token request-token)
                                             verifier)
           signature (sig/sign consumer
                               (sig/base-string "POST"
                                                (:access-uri consumer)
                                                unsigned-params)
                               (:oauth_token_secret request-token))
           params (assoc unsigned-params
                    :oauth_signature signature)]
       (swank.core/break)
       (success-content
        (http/post (:access-uri consumer)
                   :query params
                   :header {"Authorization" (authorization-header params)}
                   :parameters (http/map->params {:use-expect-continue false})
                   :as :urldecoded)))))

(defn credentials
  "Return authorization credentials needed for access to protected resources.  
The key-value pairs returned as a map will need to be added to the 
Authorization HTTP header or added as query parameters to the request."
  ([consumer token token-secret request-method request-uri & [request-params]]
     (let [unsigned-oauth-params (sig/oauth-params consumer token)
           unsigned-params (merge request-params 
                                  unsigned-oauth-params)
           signature (sig/sign consumer 
                               (sig/base-string (-> request-method
                                                    as-str
                                                    upper-case)
                                                request-uri
                                                 unsigned-params)
                               token-secret)]
       (assoc unsigned-oauth-params :oauth_signature signature))))

(defn authorization-header
  "OAuth credentials formatted for the Authorization HTTP header."
  ([oauth-params]
     (str "OAuth " (join ", " (map (fn [[k v]] 
                                    (str (as-str k) "=\"" v "\""))
                                  oauth-params))))
  ([oauth-params realm]
     (authorization-header (assoc oauth-params realm))))

(defn check-success-response [m]
  (let [code (:code m)
        reason (:reason m)]
    (if (or (< code 200)
            (>= code 300))
      (throw (new Exception (str "Got non-success code: " code ". "
                                 "Reason: " reason ", "
                                 "Content: " (:content m))))
      m)))

(defn success-content [m]
  (:content (check-success-response m)))
