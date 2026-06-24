# Task 2: Add scriptable agent CLI

**Document ID:** `TASK-002`
**Configuration identification:** `TASK-002` is the second task in `agent-tool-interface`. Every nested point ID is prefixed with `TASK-002`.

## TASK-002.P1 Scope

Type: AFK

## TASK-002.P2 Must implement exactly

- **TASK-002.MI1:** Add `src/todo/cli.clj` as a command entrypoint for agent automation.
- **TASK-002.MI2:** Add a `:todo` alias to `deps.edn` that runs `todo.cli` with `--enable-native-access=ALL-UNNAMED`.
- **TASK-002.MI3:** Support explicit database selection via a simple option such as `--db <path>`, defaulting to `todo.sqlite` when omitted.
- **TASK-002.MI4:** Implement core subcommands: `init`, `add`, `link`, `show`, `list`, `deps`, `transitive-deps`, `blocking`, `ready`, `by-attr`, and `done`.
- **TASK-002.MI5:** Support arbitrary task attributes on `add` and arbitrary edge attributes on `link` with a repeatable option such as `--attr key=value`; `done` may update the conventional `status` attribute.
- **TASK-002.MI6:** Support at least EDN and JSON output modes for query commands, while allowing human-readable output as the default.
- **TASK-002.MI7:** Fail loudly with a non-zero exit and useful usage text for unknown commands, missing required arguments, malformed attributes, or invalid output formats.
- **TASK-002.MI8:** Keep command syntax small and documented in code usage text; do not introduce a heavyweight CLI framework unless required.

## TASK-002.P3 Done when

- **TASK-002.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:todo --db /tmp/todo-agent.sqlite init` creates/initializes a database without warning noise.
- **TASK-002.DW2:** An agent can add two tasks with non-status attributes, link one as `depends-on` the other with an edge attribute, mark the dependency done, and query `ready` from shell commands.
- **TASK-002.DW3:** An agent can query a non-status attribute populated from the CLI with `by-attr`.
- **TASK-002.DW4:** An agent can retrieve transitive dependencies from the CLI.
- **TASK-002.DW5:** At least one query command returns valid JSON when invoked with JSON output mode.
- **TASK-002.DW6:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` still passes.

## TASK-002.P4 Out of scope

- **TASK-002.OS1:** Do not implement shell completion, a daemon, MCP integration, or network APIs.
- **TASK-002.OS2:** Do not remove or rewrite the existing TUI.
- **TASK-002.OS3:** Do not implement schema validation for attributes.

## TASK-002.P5 References

- **TASK-002.REF1:** Proposal: `devflow/feat/agent-tool-interface/proposal.md`.
- **TASK-002.REF2:** Plan: `devflow/feat/agent-tool-interface/agent-tool-interface.plan.md`, especially `PLAN-001.PH2`.
- **TASK-002.REF3:** Shared DB operations from Task 1 in `src/todo/db.clj`.
- **TASK-002.REF4:** Current aliases in `deps.edn`.
