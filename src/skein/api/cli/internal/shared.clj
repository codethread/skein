(ns skein.api.cli.internal.shared
  "Shared failure mechanics for the declarative CLI parser.")

(defn fail!
  "Throw a structured parser error with stable public ex-data."
  [reason message data]
  (throw (ex-info message
                  (assoc data
                         :skein.api.cli.alpha/error true
                         :reason reason))))
