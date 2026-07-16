# Skein orientation: building on `.skein`

Read this when you are extending a workspace — its `.skein` config, a local or shared spool,
or the Skein source itself. Day-to-day strand tracking does not need it; that discipline is
`mill strand prime`.

Skein is a local strand graph for agents and humans: a durable SQLite-backed graph of work,
notes, dependencies, and workflow state behind a small machine-readable command surface.
`mill` is the local router and supervisor, the long-lived weaver owns storage and runtime
state, and the `strand` CLI is a thin JSON control surface. A workspace's workflow model
lives mostly in custom attributes and its own config/spool code — shaping that code is what
this orientation is for.

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

For personal config in a repository that does not commit Skein config, run `mill init --stealth`.
It keeps a physical repo-local `.skein`, hides local files through Git's private exclude file,
and prints the Codex instruction you may add yourself. The customisation guide above explains
the local-spool convention for substantive personal config.

When the `.skein` you are touching is a shared coordination world — a live weaver, other
agents' runs — repo conventions for that world take precedence, and smoke config experiments
in a disposable `--workspace` world as `docs/spools/customisation.md` describes.
