(ns ib.spec-instrument-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is]]
            [ib.spec]))

(deftest instrumentation-active-test
  (is (every? some? (map s/get-spec ib.spec/public-api-vars)))
  (is (vector? (vec (stest/instrument ib.spec/public-api-vars)))))
