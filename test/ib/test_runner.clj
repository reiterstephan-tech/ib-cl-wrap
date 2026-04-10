(ns ib.test-runner
  (:gen-class)
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.test :as t]
            [ib.account-test]
            [ib.client-test]
            [ib.events-test]
            [ib.market-data-test]
            [ib.open-orders-test]
            [ib.spec]
            [ib.spec-generative-test]
            [ib.spec-instrument-test]
            [ib.positions-test]))

(defn -main [& _]
  (stest/instrument ib.spec/public-api-vars)
  (let [{:keys [fail error]} (t/run-tests 'ib.spec-instrument-test
                                          'ib.spec-generative-test
                                          'ib.client-test
                                          'ib.account-test
                                          'ib.events-test
                                          'ib.market-data-test
                                          'ib.open-orders-test
                                          'ib.positions-test)]
    (stest/unstrument ib.spec/public-api-vars)
    (when (pos? (+ fail error))
      (System/exit 1))))
