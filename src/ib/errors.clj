(ns ib.errors
  "IB error classification helpers.")

(def retryable-error-codes
  "Pragmatic set of transient IB error codes that are often retryable.

  This list is intentionally conservative and can be extended as needed."
  #{1100 1101 1102 2103 2104 2105 2106 2110 2158})

(defn retryable-ib-error?
  "True when IB error code is likely transient and retryable."
  [code]
  (contains? retryable-error-codes code))
