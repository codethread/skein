# ypy3h implementation plan

1. Add runtime-owned Maven generation baseline state.
2. Extend `sync!` diff classification to compare previous added resolved Maven versions against the newly resolved added Maven versions before classloader mutation.
3. Reuse the existing pending-generation refusal shape for `:maven-version-bumps`.
4. Extend API specs and generated docs for the new diff class.
5. Add spool sync tests for refused loaded-coordinate bumps, unchanged versions, newly added coordinates, and baseline preservation across an intermediate failed sync.
6. Validate with focused/new tests, full Clojure suite, CLI Go tests, smoke, quality gates, and spool suite gate.
