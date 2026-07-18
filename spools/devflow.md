# Skein Devflow Spool

`ct.spools.devflow` has moved to the external git-distributed spool repo:
[`codethread/devflow.spool`](https://github.com/codethread/devflow.spool).

The contract doc lives there:
[`devflow.md`](https://github.com/codethread/devflow.spool/blob/9b0296a37b7ad8968c4630bbe676c3a4a0cf5df5/devflow.md)
(snapshot link at the pinned release commit).

This checkout pins the spool once, in `.skein/spools.edn`: one family entry holding the
`:git/url`, a release `:git/tag`, and its peeled `:git/sha`. Tests exercise that same entry
with an embedded runtime. [The spool index](./README.md) explains how this repo consumes
external spools, including the developer `spools.local.edn` override.
