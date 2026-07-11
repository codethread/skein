# Large-attribute Scaling — Assessment Report

**Document ID:** `ASSESS-LargeAttrScaling-001` **Feature:** `large-attr-scaling` **Plan:** [large-attr-scaling.plan.md](./large-attr-scaling.plan.md) (`PLAN-LargeAttrScaling-001`) **Proposal:** [proposal.md](./proposal.md) (`PROP-LargeAttrScaling-001`) **Status:** `S2` numbers recorded; `S3` (residual-options assessment + verdict) and the re-run usage note are appended by later slices.

## Baseline run

- **Pinned `main` sha:** `2509e0c093d6152641c3db6ecc724f01a3b7dcbe` — the commit `large-attr-scaling` branched from. No `src/skein/core/db.clj` or `spools/text-search` change lands on `main` between that sha and the current tip (`53c19d8`), so this is the exact storage code the run measured.
- **Harness commit:** `6477bd2` (`test(large-attr-scaling): durable large-attr load harness + structural smoke`, `test/skein/large_attr_benchmark.clj`).
- **Run date:** 2026-07-11, started `2026-07-11T21:09:41.789037Z`, wall clock ~6m40s end-to-end (fixture generation + both measurement families).
- **Raw result:** [`results/results.edn`](./results/results.edn), written by the harness's own `-main` under `--out`.
- **Invocation used:** the `:test` alias in `deps.edn` pins `:main-opts` to `["-m" "skein.test-runner"]`; `clojure -M` concatenates alias `:main-opts` with any CLI-supplied `-m`/args rather than letting the CLI `-m` replace it, so the documented one-liner `clojure -M:test -m skein.large-attr-benchmark ...` (from the task body and the harness's own usage string) resolves to `skein.test-runner`, which then rejects the trailing `-m skein.large-attr-benchmark --out ...` as unrecognized arguments. Ran with an equivalent classpath instead — same paths/deps/jvm-opts the `:test` alias provides, computed via `clojure -A:test -Spath` — invoking `skein.large-attr-benchmark/-main` directly:

  ```sh
  ws=$(mktemp -d)
  cp=$(cd /Users/ct/dev/projects/skein-src__large-attr-scaling && PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -A:test -Spath)
  PATH="/opt/homebrew/opt/openjdk/bin:$PATH" \
    flock -w 3600 /tmp/skein-bench.lock \
    env SKEIN_LARGE_ATTR_BENCH_FULL=1 clojure -Scp "$cp" -J--enable-native-access=ALL-UNNAMED -M -m skein.large-attr-benchmark --out "${ws:?}"
  ```

  Flagged to the coordinator (feature card `kbcjt`, note `p1n9e`) as a harness/doc issue; not patched here (`NG2`/`OS3`).

## Seed profile (`A6`)

The full-scale default profile from `default-options` in the harness, unmodified (`--seed`/`--n` left at defaults, which already match `A6`):

| Knob | Value |
| --- | --- |
| `seed` | `1337` |
| `n` (strand count) | `250000` |
| `iterations` (timed reps per workload) | `5` |
| `measure-timeout-secs` | `60` |
| `list-size` | `500` |
| `patch-size` | `200` |
| `payload-every` | `50` |
| `payload-bytes` | `65536` (64 KiB) |
| `huge-payload-bytes` | `262144` (256 KiB) |
| `near-floor-bytes` / `near-floor-every` | `1100` / `40` |
| `mb-payload-bytes` / `mb-payload-count` | `1048576` (1 MiB) / `20` |
| `archived-fraction` | `0.2` |
| `corpus-hot-count` / `corpus-archived-count` | `100` / `40` |
| `corpus-needle` | `ZZQNEEDLEQZZ` |
| `point-read-sample` | `500` |

Measured row counts: `250000` strands, `50012` archived (≈20%), `16666` rows matching the filtered-scan predicate in both schemas, `62500` rows in the `ready` view in both schemas.

## Gate reproduction (`BG1`–`BG4`)

All five informational gate-reproduction checks the harness computes came back `true`:

```clojure
{:write-amp-payload-ge-16kb true
 :write-amp-payload-independence true
 :filtered-scan true
 :ready true
 :list-assembly-500 true}
```

- **`BG1` write-amp** (target `≥5×` reduction on `≥16 KiB` payload patches, document-patch-bytes ÷ EAV-patch-bytes, median of 200 patched rows):
  - 64 KiB payload bucket: `78280` → `32960` bytes, **`2.375×`**.
  - 256 KiB payload bucket: `276040` → `32960` bytes, **`8.375×`**.
  - Combined `≥16 KiB` bucket (the gated number): `177176` → `32960` bytes, **`5.375×`**.
  - Payload-independence check (256 KiB EAV patch bytes ÷ payload-free EAV patch bytes, target `≤1.5×`): `32960` ÷ `32960`, **`1.0×`** — EAV patch cost is flat regardless of payload size.
  - Payload-free rows: document `4120` bytes vs. EAV `32960` bytes median (accepted-slower case, per the harness's own `:accepted-small-row-cost` note — EAV patches carry a fixed page-granularity floor).
- **`BG2` filtered scan + `ready`** (250k strands):
  - Filtered scan median: document `209.84 ms` vs. EAV `180.98 ms` — EAV at or below the document envelope (target met).
  - `ready` median: document `253.12 ms` vs. EAV `426.57 ms`, ratio **`1.685×`** (target `≤1.7×`, accepted baseline `1.69×` — reproduced within measurement noise).
- **`BG3` list-of-500 assembly:** document `2.006 ms` vs. EAV `2.951 ms` median, ratio **`1.472×`** (target `≤2×`).
- **`BG4` serialization:** the run held `/tmp/skein-bench.lock` (`flock -w 3600`) for its full duration; no concurrent sibling contended the lock.

## Residual paths (`F2`)

Measured through the real shipped code (`weaver/show`, `weaver/list-lean`, `weaver/ready-lean`, `skein.spools.text-search/search`) on a disposable `:publish? false` world, `5` timed iterations each unless noted.

- **Full-fidelity point read, archived included** (`strand-row-by-id`, `500`-strand sample): median `307.23 ms`, max `1259.61 ms` (first-iteration JIT/cache outlier — remaining four samples were `293.85`–`346.41 ms`). Archived sample strand carried attribute keys `body corpus kind owner priority rank status`; the general sample carried `body corpus kind mb-payload near-floor owner priority rank status` (the full unomitted set, since this path resolves whole values regardless of size).
- **Lean list assembly** (`weaver/list-lean`, all `250000` strands): median `2075.39 ms`, max `2342.49 ms`. The `mb-payload` attribute (`1048578` bytes) is omitted from every row (`:skein/omitted true`), confirming the 1024-byte lean floor holds on the large-value regime.
- **Lean `ready` assembly** (`weaver/ready-lean`, all `250000` strands): median `2157.66 ms`, max `2356.83 ms`. Same `mb-payload` omission as the lean list.
- **Text-search `LIKE`, hot only** (default, archived excluded): median `209.23 ms`, `100` rows returned (matches `corpus-hot-count`).
- **Text-search `LIKE`, archived included** (`:archived? true`): median `408.40 ms`, `140` rows returned (`100` hot + `40` archived, matches `corpus-hot-count` + `corpus-archived-count`). **`1.952×`** the hot-only median.
