(ns com.unbounce.yopa.integration-tests
  (:require [com.unbounce.yopa.core :as yopa]
            [com.unbounce.yopa.aws-client :as aws]
            [com.unbounce.yopa.config :as config]
            [amazonica.aws.sns :as sns]
            [amazonica.aws.sqs :as sqs]
            [amazonica.aws.s3 :as s3]
            [base64-clj.core :as b64]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is are use-fixtures]]
            [clj-http.client :as http])
  (:import com.amazonaws.AmazonServiceException
           com.amazonaws.AmazonClientException
           java.util.UUID))

(defonce config-file
  (io/file "yopa-config-example.yml"))

(defn- with-yopa [f]
  (yopa/init-and-start
    config-file
    (yopa/default-override-file))
  (f)
  (yopa/stop))

(use-fixtures :once with-yopa)

; TODO test that validates the generated aws-regions-override-test.xml file

(deftest queues-were-created
  (let [qurls (set (:queue-urls (aws/list-queues)))]
    (is (= (count qurls) 3))))

(deftest topics-were-created
  (let [tarns (set (:topics (aws/list-topics)))]
    (is (>= (count tarns) 2))))

(deftest create-topic-is-idempotent
  (let [topic-name "test-idempotency"
        tarn1 (aws/create-topic topic-name)
        tarn2 (aws/create-topic topic-name)]
    (is (= tarn1 tarn2))))

(deftest get-topic-attributes
  (let [topic-name "test-topic-without-subscription"
        tattrs (aws/get-topic-attributes (aws/make-arn "sns" topic-name))]
    (is (= (:DisplayName tattrs) topic-name))))

(deftest get-missing-topic-attributes
  (is
    (thrown-with-msg?
      AmazonServiceException
      #"No topic found for ARN: _doesnt_exist_"
      (aws/get-topic-attributes "_doesnt_exist_"))))

(deftest unsupported-sns-operation
  (is
    (thrown-with-msg?
      AmazonServiceException
      #"Unsupported action: DeleteTopic"
      (aws/run-on-sns #(sns/delete-topic "fake-arn")))))

(defn- receive-one-message [queue-name]
  (let [queue-url (aws/queue-name->url queue-name)
        receive-result (aws/run-on-sqs
                         #(sqs/receive-message
                            :queue-url queue-url
                            :wait-time-seconds 10
                            :max-number-of-messages 10
                            :delete true))
        messages (:messages receive-result)]
    (is (= 1 (count messages)))
    (->> messages first :body)))

(deftest topic-with-subscriptions-delivery
  (let [topic-arn (aws/make-arn "sns" "test-topic-with-subscriptions")
        subject (str "test subject " (rand-int 32768))
        message (str "test message " (rand-int 32768))
        publish-result (aws/run-on-sns
                         #(sns/publish :topic-arn topic-arn
                                       :subject subject
                                       :message message))
        message-id (:message-id publish-result)]
    (is (some? message-id))
    (let [standard-message (json/read-str
                             (receive-one-message "test-subscribed-queue-standard")
                             :key-fn keyword)
          raw-message (receive-one-message "test-subscribed-queue-raw")]
      (are [a e] (= a e)
           message raw-message
           message (:Message standard-message)
           subject (:Subject standard-message)
           "Notification" (:Type standard-message)))))

(deftest topic-without-subscription-delivery
  (let [message-id (aws/run-on-sns
                     #(sns/publish :topic-arn "arn:aws:sns:yopa-local:000000000000:test-topic-without-subscription"
                                   :subject "test subject"
                                   :message "test message"))]
    (is (some? message-id))))

(deftest topic-unsubscribe-success
  (let [subscription-id (aws/subscribe
                          "http://localhost:47196/request-logger"
                          "http"
                          "arn:aws:sns:yopa-local:000000000000:test-topic-with-subscriptions")]
    (aws/unsubscribe subscription-id)))

(deftest topic-unsubscribe-failure
  (is
    (thrown-with-msg?
      AmazonServiceException
      #"No subscription found for ARN: _doesnt_exist_"
      (aws/unsubscribe "_doesnt_exist_"))))

(deftest topic-list-subscriptions
  (let [subscriptions (:subscriptions
                        (aws/run-on-sns sns/list-subscriptions))]
    (is
      (>=
        (count subscriptions)
        3))))

(deftest topic-list-subscriptions-by-topic
  (let [topic-arn (aws/make-arn "sns" "test-topic-with-subscriptions")
        subscriptions (:subscriptions
                        (aws/run-on-sns
                          #(sns/list-subscriptions-by-topic topic-arn)))]
    (is
      (>=
        (count subscriptions)
        3))))

(deftest confirm-subscription
  (let [subscriptions (:subscriptions
                        (aws/run-on-sns sns/list-subscriptions))
        http-subscription (first
                            (filter
                              #(= (:protocol %) "http")
                              subscriptions))
        topic-arn (:topic-arn http-subscription)
        token (b64/encode (:subscription-arn http-subscription))
        response (http/get
                   (str
                     "http://localhost:47196/"
                     "?Action=ConfirmSubscription"
                     "&TopicArn=" topic-arn
                     "&Token=" token))]
    (is (= 200 (:status response)))))

;; TODO test Verify Subscription

(deftest request-logger
  (let [response (http/get "http://localhost:47196/request-logger")]
    (is (= 200 (:status response)))))

(deftest ec2-metadata-not-found
  (is
    (thrown? AmazonClientException
      (aws/read-ec2-metadata-resource "/_not_found"))))

(deftest ec2-metadata-security-groups
  (is
    (= "yopa-local-security-group"
      (aws/read-ec2-metadata-resource "/latest/meta-data/security-groups"))))

(deftest ec2-dynamic-instance-id-document
  (let [id-doc-json
        (aws/read-ec2-metadata-resource
          "/latest/dynamic/instance-identity/document")
        id-doc
        (json/read-str id-doc-json :key-fn keyword)]
    (is
      (= (:region id-doc)
        "yopa-local"))))

(deftest ec2-meta-iam-security-credentials
  (let [iam-sc
        (aws/read-ec2-metadata-resource
          "/latest/meta-data/iam/security-credentials")]
    (is
      (= iam-sc
        "fake-role-name")))

  (let [iam-doc-json
        (aws/read-ec2-metadata-resource
          "/latest/meta-data/iam/security-credentials/fake-role-name")
        iam-doc
        (json/read-str iam-doc-json :key-fn keyword)]
    (is
      (.contains
        (:Type iam-doc)
        "AWS-HMAC"))))

(deftest s3
  (let [bucket-name (str "yopa-test-" (UUID/randomUUID))
        bucket (aws/run-on-s3
                 #(s3/create-bucket bucket-name))]
    (is
      (= bucket-name (:name bucket)))

    (is (not (empty?
               (aws/run-on-s3
                 #(s3/list-buckets)))))

    (aws/run-on-s3
      #(s3/delete-bucket bucket-name))))
