# Skein Devflow Spool

`ct.spools.devflow` has moved to the external git-distributed spool repo: [`codethread/devflow.spool`](https://github.com/codethread/devflow.spool).

The contract doc now lives there: [`devflow.md`](https://github.com/codethread/devflow.spool/blob/9b0296a37b7ad8968c4630bbe676c3a4a0cf5df5/devflow.md). This Skein checkout consumes the spool by the family coordinate (`v1` tag plus peeled sha) in `.skein/spools.edn` — the single source of the pin; the test JVM loads it through the product loader from the same entry.
