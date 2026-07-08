(ns skein.macros.queries
  "Macros for defining Skein named queries with fused var, docstring, and usage.

  Mirrors `skein.macros.patterns`: `defquery` expands to a real top-level `def`
  of the query var plus a `remember-query!` call, deferring registration to
  `install-queries!`. Nothing is registered at macroexpansion time.

  Naming convention: the name symbol is the query var (kept so other forms can
  read it, e.g. `branches-op` reads the `work-query` var). Its registered query
  handle strips a trailing `-query` suffix, so `work-query` registers as `work`
  and `feature-active-query` as `feature-active`, matching today's names. A name
  without the suffix registers under itself."
  (:require [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as api]))

(defonce ^:private query-registry (atom {}))

(defn- registered-name
  "Return the registered query handle symbol for a query var name symbol.

  Strips a trailing `-query` suffix so var names stay descriptive while the
  registered handle matches the unqualified name callers pass to `--query`."
  [name]
  (let [s (str name)
        suffix "-query"]
    (symbol (if (and (str/ends-with? s suffix) (> (count s) (count suffix)))
              (subs s 0 (- (count s) (count suffix)))
              s))))

(defn remember-query!
  "Remember a query defined in namespace `ns-sym` for later install-queries!.

  Entries are kept in author order per namespace; re-remembering the same
  `:name` replaces the existing entry in place, keeping registration and any
  conventions derivation reload-friendly and order-stable."
  [ns-sym entry]
  (swap! query-registry update ns-sym
         (fn [entries]
           (let [entries (vec entries)
                 idx (first (keep-indexed (fn [i e] (when (= (:name e) (:name entry)) i))
                                          entries))]
             (if idx
               (assoc entries idx entry)
               (conj entries entry)))))
  entry)

(defn remembered-queries
  "Return ordered `{:name :usage}` entries remembered for the current namespace,
  or for `ns-sym`.

  The accessor task 7 uses to derive the `devflow-conventions` `:queries`
  listing without re-reading source; entries come back in author order."
  ([] (remembered-queries (ns-name *ns*)))
  ([ns-sym]
   (mapv #(select-keys % [:name :usage]) (get @query-registry ns-sym))))

(defn install-queries!
  "Install all queries remembered for the current namespace, or for `ns-sym`.

  Resolves the runtime via `skein.api.current.alpha/current`, registers each
  remembered query through `skein.api.weaver.alpha/register-query!` in author
  order, and returns a map of registered-name symbol to that call's canonical
  return, matching today's `register-query-map!` result shape."
  ([]
   (install-queries! (ns-name *ns*)))
  ([ns-sym]
   (let [runtime (current/runtime)
         entries (get @query-registry ns-sym)]
     (into {}
           (map (fn [{:keys [name query]}]
                  [name (api/register-query! runtime name query)]))
           entries))))

(defmacro defquery
  "Define a Skein named query and remember it for install-queries!.

  Signature is `[name docstring opts query-def]`: `name` is the query var
  symbol, `docstring` documents it, `opts` is a map carrying at least `:usage`
  (the `strand ... --query <name>` string `devflow-conventions` lists), and
  `query-def` is the query definition — a `:where` vector or a full
  `{:params [...] :where [...]}` map.

  Expands to a real top-level `(def name docstring query-def)` plus a
  `remember-query!` call recording `{:name <registered-name> :query <def>
  :usage <usage> :doc <docstring>}`. The registered name strips a trailing
  `-query` from `name` (see the namespace docstring). No registration happens at
  macroexpansion time; `install-queries!` performs it. Fails loudly at
  macroexpansion for a non-symbol name, a missing/non-string docstring, or a
  missing `:usage`."
  [name docstring opts query-def]
  (when-not (symbol? name)
    (throw (ex-info "defquery name must be a symbol" {:name name})))
  (when-not (string? docstring)
    (throw (ex-info "defquery requires a docstring" {:query name})))
  (when-not (:usage opts)
    (throw (ex-info "defquery options require a :usage string" {:query name :opts opts})))
  (let [ns-sym (ns-name *ns*)
        register-sym (registered-name name)]
    `(do
       (def ~name ~docstring ~query-def)
       (remember-query! '~ns-sym
                        {:name '~register-sym
                         :query ~name
                         :usage ~(:usage opts)
                         :doc ~docstring})
       (var ~name))))

(defmacro defq
  "Short alias for defquery."
  [& args]
  `(defquery ~@args))
