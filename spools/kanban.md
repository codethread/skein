# Skein Kanban Spool

`ct.spools.kanban` has moved to the external git-distributed spool repo:
[`codethread/kanban.spool`](https://github.com/codethread/kanban.spool).

The contract doc and cookbook live there:
[`kanban.md`](https://github.com/codethread/kanban.spool/blob/dfd6948afb5db9c8ca30778cb1ba329a3afff877/kanban.md)
and
[`kanban.cookbook.md`](https://github.com/codethread/kanban.spool/blob/dfd6948afb5db9c8ca30778cb1ba329a3afff877/kanban.cookbook.md)
(snapshot links at the pinned release commit).

This checkout pins the spool in `.skein/spools.edn`. [The spool index](./README.md) explains
how this repo consumes external spools, including the developer `spools.local.edn` override.

Kanban loads independently of devflow. Only the tracker adapter in
`.skein/kanban_tracker.clj` depends on both spools: it binds devflow as kanban's tracker
once both are active.
