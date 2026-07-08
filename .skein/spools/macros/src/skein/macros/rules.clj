(ns skein.macros.rules
  "Macros for defining Skein chime attention rules with fused handler and registration.

  Mirrors `skein.macros.patterns`/`skein.macros.queries`/`skein.macros.ops`:
  `defrule` expands to a real top-level `(defn <name>-rule ...)` handler plus a
  `remember-rule!` call, deferring registration to `install-rules!`. Nothing is
  registered at macroexpansion time.

  Naming convention: the `defrule` name symbol becomes the chime rule key (e.g.
  `agent-failure` -> `:agent-failure`); the handler var it defines is
  `<name>-rule`, so today's rule fns (`attention/agent-failure-rule`, ...) are
  unchanged and the fully-qualified handler symbol remembered for registration is
  `<current-ns>/<name>-rule`."
  (:require [skein.spools.chime :as chime]))

(defonce ^:private rule-registry (atom {}))

(defn remember-rule!
  "Remember a rule defined in namespace `ns-sym` for later install-rules!.

  Entries are kept in author order per namespace; re-remembering the same `:key`
  replaces the existing entry in place, keeping registration reload-friendly and
  order-stable."
  [ns-sym entry]
  (swap! rule-registry update ns-sym
         (fn [entries]
           (let [entries (vec entries)
                 idx (first (keep-indexed (fn [i e] (when (= (:key e) (:key entry)) i))
                                          entries))]
             (if idx
               (assoc entries idx entry)
               (conj entries entry)))))
  entry)

(defn forget-rules!
  "Forget every rule remembered for the current namespace, or for `ns-sym`.

  A config namespace calls this once at the top of its load, before its
  `defrule` forms re-register, so a targeted reload (load-file + reload!)
  installs exactly what the current source defines. Without it the JVM-global
  registry keeps entries for rules since renamed or deleted from source, and
  `install-rules!` would silently re-register those stale handlers (TEN-003).
  Returns nil."
  ([] (forget-rules! (ns-name *ns*)))
  ([ns-sym]
   (swap! rule-registry dissoc ns-sym)
   nil))

(defn install-rules!
  "Install all rules remembered for the current namespace, or for `ns-sym`.

  Registers each remembered rule through `skein.spools.chime/defrule!` in author
  order and returns a vector of the `defrule!` return maps, matching today's
  `register-chime-rules!` result shape so `attention/install!` keeps its
  `:chime-rules` return."
  ([]
   (install-rules! (ns-name *ns*)))
  ([ns-sym]
   (mapv (fn [{:keys [key] fn-sym :fn}]
           (chime/defrule! key fn-sym))
         (get @rule-registry ns-sym))))

(defmacro defrule
  "Define a Skein chime attention rule and remember it for install-rules!.

  Signature is `[name docstring argv & body]`: `name` is the rule name symbol
  (its chime key is the keyword of that name, e.g. `agent-failure` ->
  `:agent-failure`), `docstring` documents the handler, and `argv`/`body` are the
  handler's arg vector and body — the handler takes one chime rule context map,
  typically destructured as `[{:keys [strand ready-ids]}]`.

  Expands to a real top-level `(defn <name>-rule docstring argv body...)` plus a
  `remember-rule!` call recording `{:key <keyword> :fn <ns>/<name>-rule}`. No
  registration happens at macroexpansion time; `install-rules!` performs it
  through `chime/defrule!`. Fails loudly at macroexpansion for a non-symbol name
  or a missing/non-string docstring."
  [name docstring argv & body]
  (when-not (symbol? name)
    (throw (ex-info "defrule name must be a symbol" {:name name})))
  (when-not (string? docstring)
    (throw (ex-info "defrule requires a docstring" {:rule name})))
  (let [ns-sym (ns-name *ns*)
        handler-name (symbol (str name "-rule"))
        fn-sym (symbol (str ns-sym) (str name "-rule"))
        rule-key (keyword (str name))]
    `(do
       (defn ~handler-name ~docstring ~argv ~@body)
       (remember-rule! '~ns-sym
                       {:key ~rule-key
                        :fn '~fn-sym})
       (var ~handler-name))))
