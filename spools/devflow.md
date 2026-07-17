# Skein Devflow Spool

`ct.spools.devflow` has moved to the external git-distributed spool repo: [`codethread/devflow.spool`](https://github.com/codethread/devflow.spool).

The contract doc now lives there: [`devflow.md`](https://github.com/codethread/devflow.spool/blob/312cdffeabf68d40cca2cb246e0bb404e99c5984/devflow.md). This Skein checkout consumes the spool by the sha-pinned coordinate in `.skein/spools.edn`; the test JVM pins the same sha in `deps.edn`. Keep those two pins synchronized.
