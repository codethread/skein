# Brief: v1 tighten skein.api.format.alpha (and pave the api.* refactor pattern)

Card `g1men`, first feature under epic `9nu0q` (v1 API promise: tighten every `skein.api.*.alpha` module). Captured from the user's spoken instructions, 2026-07-18.

The work is twofold: do the v1 tightening refactor of `skein.api.format.alpha`, and identify the patterns the other sixteen api.* refactor cards will follow, encoding them in the codebase — worked into the workflow engine and review rosters where that fits best, and dogfooded against this very change. Follow-on agents will be fable/oracle-class; parts that stay ad hoc are acceptable and those agents can handle them the same way.

Patterns to establish:

1. Function ordering. Invert the current lexical helpers-first style: public and important functions first, descending to low-level detail down the page, so a reader meets the surface before the plumbing.
2. Public/private file separation (design freedom granted; counsel optional). The module namespace, e.g. `skein.api.weaver.alpha`, holds the public surface only; internals move to a distinct file/namespace (naming to be designed — `alpha.private`, `-internal`, or a helpers folder). The module name then names the promise, reviewers and the repo owner focus on that one file, and the plumbing carries no review-attention cost so long as the public surface is right. Scoped to the `skein.api.*` tier for now (core/db maybe later). Should feed roster reviews and patterns, and could later enable stricter review on files matching the pattern. A consistent shape matters more than any particular choice.
3. Docstring and comment wrapping. Strings and comments are hard-wrapped in source so files read in an IDE without soft-wrap; a review roster seat should always be checking this. Markdown is the explicit opposite: prose runs full length and the IDE wraps it.
4. Public surface audit. For each public fn, check real usage; remove trivial or easily-derivable functions to cut the v1 surface.
