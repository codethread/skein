# Skein orientation

Skein is a local strand graph for agents and humans: a durable SQLite-backed graph of work,
notes, dependencies, and workflow state behind a small machine-readable command surface.
`mill` is the local router and supervisor, the long-lived weaver owns storage and runtime
state, and the `strand` CLI is a thin JSON control surface. Your workflow model lives mostly
in custom attributes and your own config/spool code.

For personal use in a repository that does not commit Skein config, run `mill init --stealth`.
It keeps a physical repo-local `.skein`, hides local files through Git's private exclude file,
and prints the Codex instruction you may add yourself. The customisation guide below explains
the local-spool convention for substantive personal config.

The Skein source checkout for this world is `{{.Source}}`, resolved from `SKEIN_SOURCE`, the
install-time source recorded by `make install`, or a canonical Skein checkout cwd. Every path
below lives under it.

| Path | What you get there |
| --- | --- |
| `docs/tutorial.md` | the start-to-finish learning path, from install to the live REPL |
| `docs/reference.md` | the user reference: workspaces, the weaver, the CLI surface, strands, edges, attributes, queries, and the REPL |
| `docs/spools/customisation.md` | workspace config: `init.clj`, spool approval and activation, authoring a local spool, and REPL hygiene for shared weavers |
| `docs/spools/testing.md` | testing spools and libraries: the testing tiers and `skein.test.alpha` weaver worlds |
| `docs/spools/writing-shared-spools.md` | the discipline for spools other people run |
| `docs/api/` | the generated Alpha API reference |
| `spools/README.md` | the index of shipped spools, each row linking its contract doc |
| `devflow/specs/` | the behavior contracts maintainers hold Skein to |

Run `mill strand prime` next for the task-tracking working loop: creating and driving strand
plans.

When you are working inside a Skein-style repo (one whose `.skein` directory is a shared
coordination world), read `AGENTS.md` in the same checkout for the `.skein` coordination
workspace discipline before touching that shared world.
