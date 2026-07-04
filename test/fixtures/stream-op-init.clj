;; Self-contained streaming-op fixture, loadable from a disposable workspace's
;; init.clj via `(load-file ".../test/fixtures/stream-op-init.clj")`.
;;
;; It registers `test-stream`, a `:stream? true` op with an optional `--count n`
;; flag (default 3). The handler emits `{"i": <n>}` NDJSON lines through its
;; `:op/emit!` and returns `{"emitted": <count>}` as the terminator payload.
;; Tasks 8 and 10 load this exact file to exercise the strand -> mill -> weaver
;; stream relay end to end; keep it dependency-free beyond blessed core APIs.
(ns skein.test.fixtures.stream-op
  (:require [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as weaver]))

(defn test-stream-op
  "Emit `--count` lines (default 3), then return the emitted count."
  [ctx]
  (let [emit! (:op/emit! ctx)
        n (or (:count (:op/args ctx)) 3)]
    (dotimes [i n]
      (emit! {"i" i}))
    {"emitted" n}))

(weaver/register-op! (current/runtime)
                     'test-stream
                     {:doc "Emit `--count` NDJSON lines, then a terminator count."
                      :stream? true
                      :arg-spec {:op "test-stream"
                                 :flags {:count {:type :int
                                                 :doc "Number of lines to emit (default 3)."}}}}
                     'skein.test.fixtures.stream-op/test-stream-op)
