# Task 11: External devflow.spool update and spools.edn repin

**Document ID:** `TASK-Ooc-011`

## TASK-Ooc-011.P1 Scope

Type: HITL

Coordinator-executed (external repo publish; do not delegate to an AFK worker). Update `codethread/devflow.spool` for the new CLI surface and repin it in `.skein/spools.edn`.

## TASK-Ooc-011.P2 Must implement exactly

- **TASK-Ooc-011.MI1:** In a clone of `codethread/devflow.spool`: sweep emitted guidance/instruction strings and docs for `strand op ` and old builtin/lifecycle invocations; update to the root-op + mill surface (same mapping as task 9).
- **TASK-Ooc-011.MI2:** Commit, push, capture the new 40-hex sha.
- **TASK-Ooc-011.MI3:** Update `.skein/spools.edn` `:git/sha` for the devflow coordinate to the new pin (and `:git/tag` if the repo uses one).
- **TASK-Ooc-011.MI4:** Verify in a disposable workspace that approves the same coordinate: sync + activate devflow, run one `strand devflow-describe`, confirm emitted text uses the new surface.

## TASK-Ooc-011.P3 Done when

- **TASK-Ooc-011.DW1:** New sha pinned in `.skein/spools.edn`; disposable-world verification passes; repo validation suites still green.

## TASK-Ooc-011.P4 Out of scope

- **TASK-Ooc-011.OS1:** Any other external spool consumers.

## TASK-Ooc-011.P5 References

- **TASK-Ooc-011.REF1:** plan CM2/R3; CLAUDE.md `.skein` section (RFC-017 git-distributed spool, `spools.local.edn` developer override); repo rule: clone external repos with `nu -l -c "clone --help"`.
