# Skein Devflow Spool

`skein.spools.devflow` has moved to the external git-distributed spool repo: [`codethread/devflow.spool`](https://github.com/codethread/devflow.spool).

The contract doc now lives there: [`devflow.md`](https://github.com/codethread/devflow.spool/blob/6c0f8c7e20a7f6de4cf81c98f4d7a33388663592/devflow.md). This Skein checkout consumes the spool by the sha-pinned coordinate in `.skein/spools.edn`; the test JVM pins the same sha in `deps.edn`. Keep those two pins synchronized.
