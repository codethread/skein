# Task 4: Userland surface

**Document ID:** `TASK-Werg-004`

## TASK-Werg-004.P1 Scope

Type: AFK

Implement DELTA-Werg-001 items D3.1–D3.2: the repo `.skein` config, agent guidance, and the strand skill.

## TASK-Werg-004.P2 Must implement exactly

- **TASK-Werg-004.MI1 (D3.1):** `.skein/config.clj`: existing devflow ops return the engine's `{:ready :done}` data spliced into their result maps; new thin ops `devflow-advance <feature> [choice] [json-input] [notes] [step=<id>]` (dispatching via `skein.spools.workflow/advance!`; parse optional args unambiguously — reuse the `step=` token pattern, treat a leading `{` arg as JSON input, one bare arg as notes when no choice given / as choice when the ready step is a checkpoint: keep it simple and loud), `devflow-describe [stage-key]`, `devflow-history <feature>`, `devflow-archive <feature>`. Update `devflow-conventions-op` and `install!` registrations.
- **TASK-Werg-004.MI2 (D3.2):** register named query `work`: active strands excluding `workflow/role` in `#{"molecule" "digest" "procedure"}` — checkpoints and steps stay visible; suitable for `strand ready --query work` and `strand list --query work --state active`. Document in `.skein/AGENTS.md` (driving section + discovery section) and update `.agents/skills/strand/SKILL.md` to teach `strand ready --query work` as the default ready loop in this repo (keep the skill generic elsewhere).
- **TASK-Werg-004.MI3:** update `test/skein/config_test.clj` for the new/changed ops (advance drive-through, history/describe/archive smoke, `work` query registration + plumbing exclusion) and keep the startup/reload registration assertions in sync.

## TASK-Werg-004.P3 Done when

- **TASK-Werg-004.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
- **TASK-Werg-004.DW2:** `.skein/AGENTS.md` usage lines match the actual op argv contracts (spot-check by reading the op fns).

## TASK-Werg-004.P4 Out of scope

- **TASK-Werg-004.OS1:** Engine changes (tasks 1–3 landed them); live weaver reload (coordinator does it); commits.

## TASK-Werg-004.P5 References

- **TASK-Werg-004.REF1:** [DELTA-Werg-001](../specs/workflow-spool-contract.delta.md) D3.1–D3.2; [PLAN-Werg-001](../workflow-ergonomics.plan.md) PH4.
- **TASK-Werg-004.REF2:** `.skein/config.clj` op helpers (`require-argv-range!`, `split-step-arg`, `parse-json-object-arg`), `test/skein/config_test.clj`, `.skein/AGENTS.md`, `.agents/skills/strand/SKILL.md`.
