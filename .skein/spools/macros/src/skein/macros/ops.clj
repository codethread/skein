(ns skein.macros.ops
  "Macros for defining Skein CLI ops with fused handler, arg-spec, and conventions.

  Mirrors `skein.macros.patterns`/`skein.macros.queries`: `defop` expands to a
  real top-level `(defn <name>-op ...)` handler plus a `remember-op!` call,
  deferring registration to `install-ops!`. Nothing is registered at
  macroexpansion time.

  Naming convention: the `defop` name symbol is the registered op name (e.g.
  `devflow-start`); the handler var it defines is `<name>-op`, so today's handler
  symbols (`config/devflow-start-op`, ...) are unchanged and the fully-qualified
  handler symbol remembered for registration is `<current-ns>/<name>-op`.

  The options map carries `:arg-spec` (a named arg-spec var/symbol or an inline
  arg-spec map), any extra `register-op!` metadata keys such as `:returns` or `:deadline-class`
  passed straight through to registration, and an optional `:convention` map of
  extra `devflow-conventions` `:ops` fields (`:manual`/`:purpose`/...) beyond the
  mechanically-derived `{:name :help}`, remembered but never registered."
  (:require [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as weaver]))

(defonce ^:private op-registry (atom {}))

(defn remember-op!
  "Remember an op defined in namespace `ns-sym` for later install-ops!.

  Entries are kept in author order per namespace; re-remembering the same
  `:name` replaces the existing entry in place, keeping registration and any
  conventions derivation reload-friendly and order-stable."
  [ns-sym entry]
  (swap! op-registry update ns-sym
         (fn [entries]
           (let [entries (vec entries)
                 idx (first (keep-indexed (fn [i e] (when (= (:name e) (:name entry)) i))
                                          entries))]
             (if idx
               (assoc entries idx entry)
               (conj entries entry)))))
  entry)

(defn forget-ops!
  "Forget every op remembered for the current namespace, or for `ns-sym`.

  A file-backed config namespace (loaded via `:file`, e.g. `config.clj`) calls
  this once at the top of its load, before its `defop` forms re-register, so
  `reload!` — which re-reads the file — installs exactly what the current source
  defines. Without it the JVM-global registry keeps entries for ops since
  renamed or deleted from source, and `install-ops!` would silently re-register
  those stale handlers (TEN-003). A namespace loaded via `:ns` is skipped by
  `reload!` once loaded, so it needs a targeted `(require '<ns> :reload)` to
  re-run this. Tolerates an unknown `ns-sym` (first-load calls it before
  anything is remembered). Returns nil."
  ([] (forget-ops! (ns-name *ns*)))
  ([ns-sym]
   (swap! op-registry dissoc ns-sym)
   nil))

(defn- remembered-ops
  "Return ordered `:convention` maps remembered for the current namespace, or for
  `ns-sym`.

  Test-only accessor over the remembered `:convention` maps; each carries at
  least `{:name :help}` plus any authored extra fields, in author order. The
  shipped `devflow-conventions` `:ops` listing stays hand-authored, so nothing in
  production derives from this."
  ([] (remembered-ops (ns-name *ns*)))
  ([ns-sym]
   (mapv :convention (get @op-registry ns-sym))))

(defn install-ops!
  "Install all ops remembered for the current namespace, or for `ns-sym`.

  Resolves the runtime via `skein.api.current.alpha/current`, registers each
  remembered op through `skein.api.weaver.alpha/register-op!` in author order,
  and returns a vector of the registration entries, matching today's `install!`
  `:ops` vector shape. Registration metadata is `{:doc <arg-spec :doc>
  :arg-spec <arg-spec>}` merged with any extra remembered metadata keys (e.g.
  `:returns` and `:deadline-class`); the `:convention` data is not passed to
  `register-op!`.

  Throws if `ns-sym` has no remembered ops — a typo'd or stale quoted ns
  literal, or a file that defined nothing, must fail loudly rather than silently
  install nothing (TEN-003)."
  ([]
   (install-ops! (ns-name *ns*)))
  ([ns-sym]
   (let [entries (get @op-registry ns-sym)]
     (when (empty? entries)
       (throw (ex-info "install-ops! found no remembered ops for namespace"
                       {:ns-sym ns-sym
                        :known-namespaces (vec (keys @op-registry))})))
     (let [runtime (current/runtime)]
       (mapv (fn [{:keys [name arg-spec metadata] fn-sym :fn}]
               (weaver/register-op! runtime name
                                    (merge {:doc (:doc arg-spec) :arg-spec arg-spec} metadata)
                                    fn-sym))
             entries)))))

(defmacro defop
  "Define a Skein CLI op and remember it for install-ops!.

  Signature is `[name docstring opts argv & body]`: `name` is the registered op
  name symbol, `docstring` documents the handler, `opts` is the options map (see
  the namespace docstring), and `argv`/`body` are the handler's arg vector and
  body — the handler takes one op context map.

  Expands to a real top-level `(defn <name>-op docstring argv body...)` plus a
  `remember-op!` call recording `{:name <name> :fn <ns>/<name>-op :arg-spec
  <resolved arg-spec> :metadata <extra op metadata> :convention {:name :help
  ...}}`. No registration happens at macroexpansion time; `install-ops!` performs
  it, preserving `register-op!`'s loud-collision contract. Fails loudly at
  macroexpansion for a non-symbol name, a missing/non-string docstring, or a
  missing `:arg-spec`."
  [name docstring opts argv & body]
  (when-not (symbol? name)
    (throw (ex-info "defop name must be a symbol" {:name name})))
  (when-not (string? docstring)
    (throw (ex-info "defop requires a docstring" {:op name})))
  (when-not (:arg-spec opts)
    (throw (ex-info "defop options require an :arg-spec" {:op name :opts opts})))
  (let [ns-sym (ns-name *ns*)
        handler-name (symbol (str name "-op"))
        fn-sym (symbol (str ns-sym) (str name "-op"))
        arg-spec (:arg-spec opts)
        metadata (dissoc opts :arg-spec :convention)
        convention (:convention opts)]
    `(do
       (defn ~handler-name ~docstring ~argv ~@body)
       (remember-op! '~ns-sym
                     {:name '~name
                      :fn '~fn-sym
                      :arg-spec ~arg-spec
                      :metadata ~metadata
                      :convention (merge {:name ~(str name) :help ~(str "strand help " name)}
                                         ~convention)})
       (var ~handler-name))))
