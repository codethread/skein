# Skein Kanban Spool

`ct.spools.kanban` has moved to the external git-distributed spool repo: [`codethread/kanban.spool`](https://github.com/codethread/kanban.spool).

The contract doc and cookbook now live there ([`kanban.md`](https://github.com/codethread/kanban.spool/blob/dfd6948afb5db9c8ca30778cb1ba329a3afff877/kanban.md), [`kanban.cookbook.md`](https://github.com/codethread/kanban.spool/blob/dfd6948afb5db9c8ca30778cb1ba329a3afff877/kanban.cookbook.md)). This Skein checkout consumes the spool by the family coordinate (`v1` tag plus peeled sha) in `.skein/spools.edn` — the single source of the pin; the test JVM loads it through the product loader from the same entry (config_test exercises that path).

The spool requires `ct.spools.devflow` (the `kanban card` devflow join), so its activation is ordered after devflow's in `.skein/init.clj`.
