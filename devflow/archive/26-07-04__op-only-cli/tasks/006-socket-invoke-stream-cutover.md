# Task 6: Socket invoke envelope, stream framing, signal shutdown

**Document ID:** `TASK-Ooc-006`

## TASK-Ooc-006.P1 Scope

Type: AFK

Cut the weaver socket over to the op-only protocol per SPEC-004-D003: `invoke` + minimal `status`, stream response framing, signal-driven shutdown, metadata-class hook gating, deadlines, and the peers allowlist-mirror removal. **This intentionally breaks the Go CLI until tasks 7–8 land: do not run `(cd cli && go test ./...)` or the smoke suite as validation here; Clojure tests are the gate.**

## TASK-Ooc-006.P2 Must implement exactly

- **TASK-Ooc-006.MI1:** In `src/skein/core/weaver/socket.clj`: delete `allowed-operations`, per-op argument-shape validation, and the `dispatch` case. Requests keep the existing frame (protocol version, request id, weaver id — SPEC-004.C23 unchanged). Supported operations: `invoke` with arguments `{name, argv, payloads, cwd, worktree_root, git_common_dir, workspace, timeout?, client{pid,version}}` dispatching to `op!` with the envelope (task 1 arity); and `status` returning the existing minimal health/identity payload. `stop` as a socket operation is removed.
- **TASK-Ooc-006.MI2:** Signal shutdown: the weaver installs handlers so SIGTERM/SIGINT run the existing clean stop path (transports down, storage closed, `weaver.edn`/`weaver.json`/`weaver.sock` removed). A test proves artifact cleanup on programmatic stop; signal wiring itself may be covered by a thin main-path hook if untestable in-JVM.
- **TASK-Ooc-006.MI3:** Hook gating (SPEC-004-D003.C4): before dispatching `invoke` for an op whose `:hook-class` is `:mutating`, run `:payload/received` hooks with context `{:hook/type ... :request/source :json-socket :request/operation :invoke :request/id ... :request/args <decoded envelope> :request/options ... :op/name <canonical name>}`. `:read` ops skip payload hooks. Unknown op names fail loudly **before** hook invocation with the registry's available-names error.
- **TASK-Ooc-006.MI4:** Deadlines (SPEC-004-D003.C5): effective deadline = envelope `timeout` if present, else op `:deadline-class` (`:standard` → the existing default socket deadline; `:unbounded` → none). On expiry the connection returns a structured domain error and the weaver interrupts/abandons the handler future — no silent orphan writes after a reported timeout for mutating ops (document the exact cancellation semantics you implement in the delta if they differ).
- **TASK-Ooc-006.MI5:** Stream framing (SPEC-004-D003.C2): an op with `:stream? true` returns a handler value the socket writes incrementally — implement handler contract as: context gains `:op/emit!` (fn of one JSON-safe value, writes+flushes one NDJSON line); handler return value becomes the terminator payload. Wire shape: header frame `{protocol, request_id, stream: true}`, then emitted lines verbatim, then terminator `{protocol, request_id, done: true, success, result|error}`. Single-result ops keep today's one-frame response exactly. Register a test-only stream op (test fixture, not batteries) proving incremental flush and both success/error terminators. Ship the fixture additionally as a self-contained workspace-loadable file at the pinned path `test/fixtures/stream-op-init.clj`: designed for `load-file` from a disposable workspace's `init.clj`, it registers op `test-stream` (arg-spec: optional `--count n`, default 3) emitting `{"i": <n>}` lines then returning `{"emitted": <count>}` — tasks 8 and 10 load this exact file for end-to-end stream validation.
- **TASK-Ooc-006.MI6:** `src/skein/api/peers/alpha.clj`: remove `allowed-operations` and its pre-connect check; `call!` sends `invoke` envelopes (op name + argv/payloads args) or `status`; a peer response with a stream header fails loudly (`:peer/stream-unsupported`) without consuming the stream (SPEC-004-D003.C9). Update peers tests.
- **TASK-Ooc-006.MI7:** Update all Clojure tests that spoke old socket operations (`add`, `list`, `op`, `stop`, ...) to the new protocol; workflow/devflow/shuttle spool tests that go through `op!` directly should pass unchanged.

## TASK-Ooc-006.P3 Done when

- **TASK-Ooc-006.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` green (Go intentionally red until tasks 7–8).
- **TASK-Ooc-006.DW2:** Disposable-workspace REPL check: an `invoke` request over the socket (hand-rolled client or test helper) runs a batteries op end-to-end; `status` works; a mutating op is hook-gated; unknown op fails with available names.

## TASK-Ooc-006.P4 Out of scope

- **TASK-Ooc-006.OS1:** All Go code (tasks 7–8), spool `strand op` invocation updates (task 9), smoke (task 10).

## TASK-Ooc-006.P5 References

- **TASK-Ooc-006.REF1:** daemon-runtime delta SPEC-004-D003 (all clauses); plan A4, R1/R2, PH3 co-landing note; `src/skein/core/weaver/socket.clj`; `src/skein/api/peers/alpha.clj`; task 1's envelope arity.
