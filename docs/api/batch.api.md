
-----
# <a name="skein.api.batch.alpha">skein.api.batch.alpha</a>


Explicit-runtime API for applying batch graph mutations.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns transactional batch persistence, attribute
  normalization through transform hooks, the pre-commit validation gate, and the
  batch plus per-strand event fanout. The SQL batch engine lives in
  `skein.core.db`; the shared lifecycle and dispatch plumbing in
  `skein.core.weaver.*`.




## <a name="skein.api.batch.alpha/apply!">`apply!`</a>
``` clojure
(apply! runtime payload)
(apply! runtime payload req-ctx)
```
Function.

Apply one transactional batch graph mutation payload to `runtime`.

  The payload is a map of `:refs` (symbol -> existing strand id), `:strands`
  (patches keyed by `:ref`), `:edges` (upsert ops between refs), and `:burn`
  (refs to burn); `skein.core.db/normalize-batch-payload!` is the grammar
  authority and rejects malformed payloads loudly. Normalizes strand
  attributes through the `:attributes/normalize` transform hooks, persists the
  batch atomically, runs the `:batch/apply-before-commit` validation gate
  inside the transaction, then enqueues the batch event followed by the
  per-strand created/updated/burned fanout.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/batch/alpha.clj#L21-L49">Source</a></sub></p>
