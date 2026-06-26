# CLI Surface delta for skein-rename

**Document ID:** `SR-DELTA-002`
**Root spec:** [cli.md](../../../specs/cli.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Merged
**Last Updated:** 2026-06-26

## SR-DELTA-002.P1 Summary

The public CLI binary becomes `strand`, daemon lifecycle commands become the `weaver` subcommand group, and task/status vocabulary becomes strand/active/ephemeral vocabulary. The CLI remains a thin JSON control surface and gains no rich query authoring, package, or view-loading behavior.

## SR-DELTA-002.P2 Contract changes

- **SR-DELTA-002.CC1:** The public Go CLI executable is named `strand`. The old `todo` binary is not a compatibility alias.
- **SR-DELTA-002.CC2:** The command tree is `strand init`, `strand add`, `strand update`, `strand show`, `strand list`, `strand ready`, and `strand weaver start|repl|stop|status`.
- **SR-DELTA-002.CC3:** `strand weaver repl --stdin` preserves the current connected stdin REPL behavior under the renamed command path.
- **SR-DELTA-002.CC4:** `add` accepts `<title>`, repeated `--attr key=value`, `--active true|false`, and `--ephemeral true|false`. `--active` defaults to `true`; `--ephemeral` defaults to `false`.
- **SR-DELTA-002.CC5:** `update` accepts `--title`, `--active true|false`, `--ephemeral true|false`, repeated `--attr key=value`, and repeated `--edge edge-type:to-id`. `--status` is removed.
- **SR-DELTA-002.CC5a:** The CLI fails loudly when a create request combines `--active false` with `--ephemeral true`, or when an update request changes `--active` and `--ephemeral` in the same command. Destructive delete-on-deactivate requires an already-ephemeral active strand followed by a later `--active false` update.
- **SR-DELTA-002.CC6:** `list` accepts optional `--active true|false` in addition to existing named query and string param flags. Without `--active`, list behavior follows the promoted root CLI spec; callers that care about liveness should pass the flag explicitly.
- **SR-DELTA-002.CC7:** `ready` returns active strands whose direct `depends-on` targets are not active. It retains `--query name` and repeated `--param key=value` with unchanged daemon-registry semantics, and it does not accept status values.
- **SR-DELTA-002.CC8:** JSON output uses strand-shaped rows with `active`, `ephemeral`, and `inactive_at`; it does not include `status` or `final_at`.
- **SR-DELTA-002.CC9:** Default selected worlds use `$XDG_CONFIG_HOME/skein`, `$XDG_STATE_HOME/skein`, and `$XDG_DATA_HOME/skein`. Explicit `--config-dir` worlds remain self-contained under the selected directory.
- **SR-DELTA-002.CC10:** The Go client reads `weaver.json`, connects to `weaver.sock`, and waits for cleanup of `weaver.json`, `weaver.edn`, and `weaver.sock`.
- **SR-DELTA-002.CC11:** Generated `init.clj` templates require `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha`, then call `(libs/sync!)`.
- **SR-DELTA-002.CC12:** CLI errors, help text, remediation messages, docs, and smoke examples use `strand`/`weaver` vocabulary only.

## SR-DELTA-002.P3 Design decisions

### SR-DELTA-002.D1 Weaver is a subcommand, not a second binary

- **Decision:** Daemon lifecycle remains under the main CLI as `strand weaver ...`.
- **Rationale:** One binary keeps install and scripting simple while giving the daemon/runtime a coherent product noun.
- **Rejected:** A standalone `weaver` executable because it creates two public binaries for one local tool.

### SR-DELTA-002.D2 Boolean lifecycle flags replace status flags

- **Decision:** CLI lifecycle input uses `--active true|false` and retention uses `--ephemeral true|false`.
- **Rationale:** The public CLI should expose the core primitive directly and leave outcome/category semantics to attributes.
- **Rejected:** `--status`, `--done`, `--cancelled`, or `--kind` because they reintroduce domain-specific workflow concepts.

## SR-DELTA-002.P4 Open questions

- **SR-DELTA-002.Q1:** None for MVP.
