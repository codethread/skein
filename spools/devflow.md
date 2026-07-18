# Skein Devflow Spool

`ct.spools.devflow` has moved to the external git-distributed spool repo: [`codethread/devflow.spool`](https://github.com/codethread/devflow.spool).

The contract doc now lives there: [`devflow.md`](https://github.com/codethread/devflow.spool/blob/9b0296a37b7ad8968c4630bbe676c3a4a0cf5df5/devflow.md). This Skein checkout consumes the spool by the sha-pinned coordinate in `.skein/spools.edn`; the test JVM pins the same sha in `deps.edn`. Keep those two pins synchronized.
