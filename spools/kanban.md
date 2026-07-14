# Skein Kanban Spool

`ct.spools.kanban` has moved to the external git-distributed spool repo: [`codethread/kanban.spool`](https://github.com/codethread/kanban.spool).

The contract doc and cookbook now live there ([`kanban.md`](https://github.com/codethread/kanban.spool/blob/03707e525185cbd5685522a45c6e779b22ceb6b8/kanban.md), [`kanban.cookbook.md`](https://github.com/codethread/kanban.spool/blob/03707e525185cbd5685522a45c6e779b22ceb6b8/kanban.cookbook.md)). This Skein checkout consumes the spool by the sha-pinned coordinate in `.skein/spools.edn`; the test JVM pins the same sha in `deps.edn`. Keep those two pins synchronized (config_test enforces it).

The spool requires `ct.spools.devflow` (the `kanban card` devflow join), so its activation is ordered after devflow's in `.skein/init.clj`.
