// AGENTS tab: three views over the live shuttle delegations. `v` toggles the two
// list views; `d` on a plans node opens the graph for that subtree.
//   RUNS  (default) — the flat table of shuttle runs (`strand agent ps` +
//                     `strand show` per run + local git branch).
//   PLANS           — the delegation forest from `strand agent status`
//                     (runs nested under their task, tasks under their plan,
//                     future/blocked work greyed), with per-node detail fetched
//                     on demand.
//   GRAPH           — the selected delegation subtree rendered as boxart: the
//                     parent-of tree (plan → tasks → runs) plus dashed depends-on
//                     blockers between tasks, drawn by shelling out to graph-easy
//                     (auto LR/TB by width, like devflow's show.nu). It degrades
//                     to the indented tree when graph-easy is missing or errors.
//
// The tab owns all views as first-class state on its AgentsView (persisted by
// the shell across tab switches): each view keeps its own selection, scroll, and
// detail, built on the shell's shared list+detail kit rather than a private
// state machine. Navigation keys arrive through the tab contract's onKey, so the
// shell's single dispatch is the only keyboard reader — ⇥ and the all/active
// axis honour the active view's detail uniformly with every other tab.

import { Box, Text } from "ink";
import stringWidth from "string-width";
import {
  branchFor,
  detailRowFrom,
  parseInstant,
  renderBoxart,
  str,
  strandJson,
  strandShow,
  workspaceRoot,
  type DetailRow,
} from "../data";
import {
  age,
  clip,
  DetailView,
  detailMaxScroll,
  Failure,
  fitCol,
  graphViewport,
  ListFooter,
  oneLine,
  pad,
  TableRow,
  windowRows,
  type Cell,
} from "../ui";
import {
  defineTab,
  emptyListState,
  followSelection,
  reduceListKeys,
  reduceScrollKeys,
  type ListState,
} from "../app";

// ── runs view types ────────────────────────────────────────────────────────

export type RunSummary = {
  id: string;
  title?: string;
  state: string;
  phase?: string;
  harness?: string;
  for?: string;
  "spawned-by"?: string;
  attempt?: number;
  error?: string;
  mode?: string;
  backend?: string;
  session?: string;
  attach?: string;
};

export type Row = RunSummary &
  DetailRow & {
    prompt: string;
    cwd?: string;
    startedAt?: Date;
  };

const PHASE_COLOR: Record<string, string | undefined> = {
  running: "green",
  pending: "yellow",
  failed: "red",
  exhausted: "red",
};
const TERMINAL_DIM = new Set(["done", "superseded"]);

const PHASE_RANK: Record<string, number> = { running: 0, pending: 1, failed: 2, exhausted: 3, done: 4, superseded: 5 };

// ── plans view types ───────────────────────────────────────────────────────

type TreeNode = {
  id: string;
  title: string;
  kind: "task" | "run";
  phase?: string;
  status?: string;
  children?: TreeNode[];
};

type StatusPayload = {
  awaiting_verification?: string[];
  blocked?: { task: string; blockers: string[] }[];
  failed?: { task?: string; run: string; error?: string }[];
  ready?: string[];
  running?: string[];
  tree?: TreeNode[];
};

type NodeStatus = { text: string; color?: string; dim: boolean };

// `path` is the position-unique row identity (ancestor ids joined, with a `#n`
// suffix disambiguating identical siblings). `id` is the strand id, which is NOT
// unique in the tree: a run attaches to its task via parent-of and can reappear
// under its spawner via spawned-by. Selection anchors on `path`; detail fetching
// still uses `id`.
type TreeRow = {
  path: string;
  id: string;
  title: string;
  kind: "task" | "run";
  phase?: string;
  depth: number;
  status: NodeStatus;
};

// ── view state ───────────────────────────────────────────────────────────────
// Runs detail is synchronous: the selected Row already carries its attributes.
// Plans detail is fetched on demand — tree nodes carry no attributes — so it is
// a separate slot rather than the list's list⇄detail view flag.

type PlanDetail = { id: string; phase?: string; loading: boolean; row: DetailRow | null; failure: string | null; scroll: number };

type RunsView = { rows: Row[]; loaded: boolean; failure: string | null; s: ListState };
type PlansView = { rows: TreeRow[]; loaded: boolean; failure: string | null; s: ListState; detail: PlanDetail | null };

// The boxart graph for one delegation subtree. `dot` is the last DOT we handed
// graph-easy: an unchanged DOT whose last render succeeded skips a redundant
// (and possibly slow) re-render. `art` is the rendered boxart, null whenever the
// last render failed so the pane falls back to the `error` banner over the
// indented-tree `fallback`. Scroll is 2-D because a wide LR graph overflows
// horizontally.
type GraphState = {
  root: string;
  dot: string | null;
  art: string[] | null;
  error: string | null;
  fallback: string[];
  loaded: boolean;
  scrollY: number;
  scrollX: number;
};

type AgentsView = {
  mode: "runs" | "plans" | "graph";
  runs: RunsView;
  plans: PlansView;
  graph: GraphState | null;
};

const branchCache = new Map<string, string>();
const msg = (e: unknown): string => (e instanceof Error ? e.message : String(e));

// ── runs fetch ─────────────────────────────────────────────────────────────

async function fetchRunsRows(all: boolean): Promise<Row[]> {
  const psArgs = ["agent", "ps", ...(all ? [] : ["--active"])];
  const summaries = (await strandJson(psArgs)) as RunSummary[];
  const cache = new Map<string, string>();
  const rows = await Promise.all(
    summaries.map(async (s): Promise<Row> => {
      const strand = await strandShow(s.id);
      const attrs = strand.attributes;
      const cwd = str(attrs["shuttle/cwd"]) || undefined;
      return {
        ...s,
        ...detailRowFrom(strand, { branch: await branchFor(cwd ?? workspaceRoot, cache), phase: s.phase }),
        prompt: str(attrs["shuttle/prompt"]),
        cwd,
        startedAt: parseInstant(attrs["shuttle/started-at"] as string | undefined) ?? parseInstant(strand.created_at),
      };
    }),
  );
  return rows.sort(
    (a, b) =>
      (PHASE_RANK[a.phase ?? ""] ?? 9) - (PHASE_RANK[b.phase ?? ""] ?? 9) ||
      (b.startedAt?.getTime() ?? 0) - (a.startedAt?.getTime() ?? 0),
  );
}

// ── plans fetch ────────────────────────────────────────────────────────────

// Join a tree node against the flat cross-reference lists into a status cell.
function nodeStatus(node: TreeNode, depth: number, ctx: {
  blocked: Map<string, string[]>;
  ready: Set<string>;
  verify: Set<string>;
  failedTasks: Set<string>;
  running: Set<string>;
}): NodeStatus {
  if (node.kind === "run") {
    const phase = node.phase ?? "?";
    return { text: phase, color: PHASE_COLOR[phase], dim: TERMINAL_DIM.has(phase) };
  }
  if (depth === 0) {
    const children = node.children ?? [];
    const tasks = children.filter((c) => c.kind === "task").length;
    const runs = children.filter((c) => c.kind === "run").length;
    const parts: string[] = [];
    if (tasks) parts.push(`${tasks} ${tasks === 1 ? "task" : "tasks"}`);
    if (runs) parts.push(`${runs} ${runs === 1 ? "run" : "runs"}`);
    return { text: parts.length ? parts.join(", ") : "empty", dim: true };
  }
  const blockers = ctx.blocked.get(node.id);
  if (blockers) return { text: `blocked (waiting: ${blockers.join(", ")})`, dim: true };
  if (ctx.ready.has(node.id)) return { text: "ready", color: "yellow", dim: false };
  if (ctx.verify.has(node.id)) return { text: "verify", color: "cyan", dim: false };
  if (ctx.failedTasks.has(node.id)) return { text: "failed", color: "red", dim: false };
  const hasRunningChild = (node.children ?? []).some((c) => c.kind === "run" && (c.phase === "running" || ctx.running.has(c.id)));
  if (hasRunningChild) return { text: "active", color: "green", dim: false };
  return { text: "-", dim: true };
}

function flattenTree(payload: StatusPayload): TreeRow[] {
  const ctx = {
    blocked: new Map((payload.blocked ?? []).map((b) => [b.task, b.blockers])),
    ready: new Set(payload.ready ?? []),
    verify: new Set(payload.awaiting_verification ?? []),
    failedTasks: new Set((payload.failed ?? []).map((f) => f.task).filter((t): t is string => typeof t === "string")),
    running: new Set(payload.running ?? []),
  };
  const out: TreeRow[] = [];
  const seen = new Set<string>();
  const walk = (node: TreeNode, depth: number, parentPath: string) => {
    let path = parentPath ? `${parentPath}/${node.id}` : node.id;
    if (seen.has(path)) {
      let n = 2;
      while (seen.has(`${path}#${n}`)) n++;
      path = `${path}#${n}`;
    }
    seen.add(path);
    out.push({ path, id: node.id, title: node.title, kind: node.kind, phase: node.phase, depth, status: nodeStatus(node, depth, ctx) });
    for (const c of node.children ?? []) walk(c, depth + 1, path);
  };
  for (const root of payload.tree ?? []) walk(root, 0, "");
  return out;
}

// Tree nodes carry no attributes, so the detail is fetched on demand. A sequence
// counter drops a fetch whose target was superseded (reopened, closed, or the
// view left) before it landed.
let planDetailSeq = 0;
function closePlanDetail() {
  planDetailSeq++;
}
async function openPlanDetail(id: string, phase: string | undefined, setV: (next: AgentsView | ((v: AgentsView) => AgentsView)) => void) {
  const seq = ++planDetailSeq;
  try {
    const s = await strandShow(id);
    if (seq !== planDetailSeq) return;
    const cwd = str(s.attributes["shuttle/cwd"]) || undefined;
    const branch = cwd ? await branchFor(cwd, branchCache) : str(s.attributes["branch"], "-");
    if (seq !== planDetailSeq) return;
    const row = detailRowFrom(s, { branch, phase });
    setV((v) =>
      v.plans.detail?.id === id
        ? { ...v, plans: { ...v.plans, detail: { id, phase, loading: false, row, failure: null, scroll: 0 } } }
        : v,
    );
  } catch (e) {
    if (seq !== planDetailSeq) return;
    const failure = msg(e);
    setV((v) =>
      v.plans.detail?.id === id
        ? { ...v, plans: { ...v.plans, detail: { id, phase, loading: false, row: null, failure, scroll: 0 } } }
        : v,
    );
  }
}

// ── graph build ────────────────────────────────────────────────────────────
// Mirrors devflow's show.nu: a task DAG → Graphviz DOT → graph-easy boxart, with
// direction and label wrap inferred from terminal width. Node labels carry the
// strand id, kind, joined status, and a word-wrapped title; edges are the
// parent-of tree (solid) plus depends-on blockers (dashed) between tree nodes.

const GRAPH_PADDING = 16;

// LR reads best on a wide terminal; a narrow one has to stack ranks top-to-bottom
// or every box is shaved to nothing (show.nu's 120-column pivot).
function graphDirection(cols: number): "LR" | "TB" {
  return Math.max(20, cols - GRAPH_PADDING) < 120 ? "TB" : "LR";
}

// Infer a per-label wrap width from how many boxes share a rank, clamped so a
// label is neither a sliver nor a wall of text.
function graphWrap(cols: number, nodeCount: number, direction: "LR" | "TB"): number {
  const usable = Math.max(20, cols - GRAPH_PADDING);
  const rankNodes = direction === "TB" ? 3 : Math.min(Math.max(1, nodeCount), 6);
  return Math.min(24, Math.max(8, Math.floor(usable / rankNodes) - 5));
}

// Greedy word wrap; an over-long single word is kept whole rather than split.
function wrapWords(text: string, width: number): string[] {
  const words = oneLine(text).split(" ").filter(Boolean);
  const lines: string[] = [];
  let cur = "";
  for (const w of words) {
    const cand = cur ? `${cur} ${w}` : w;
    if (cand.length <= width) cur = cand;
    else if (cur === "") lines.push(w);
    else {
      lines.push(cur);
      cur = w;
    }
  }
  if (cur) lines.push(cur);
  return lines;
}

// DOT string literals only need backslash and quote escaped; embedded newlines
// are collapsed by oneLine before wrapping, so none survive into a segment.
const dotEscape = (s: string): string => s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');

type SubgraphEdge = { from_strand_id: string; to_strand_id: string; edge_type: string };
type SubgraphPayload = { edges?: SubgraphEdge[] };

// depends-on reads "from depends-on to", so `to` is the prerequisite. Draw the
// arrow prerequisite→dependent so blockers point at the work they gate, matching
// the parent-of flow. A `subgraph <root> --relation depends-on` only walks
// depends-on outward from the root, so a plan root (whose children hang off
// parent-of) yields nothing — the blockers live between the task nodes. Query
// depends-on from each task in the subtree instead and union the results.
// Best-effort: a failed query drops that node's edges rather than the render.
async function fetchDepEdges(taskIds: string[]): Promise<[string, string][]> {
  const perTask = await Promise.all(
    taskIds.map((id) =>
      (strandJson(["subgraph", id, "--relation", "depends-on"]) as Promise<SubgraphPayload>)
        .then((sg) => sg.edges ?? [])
        .catch(() => [] as SubgraphEdge[]),
    ),
  );
  const seen = new Set<string>();
  const out: [string, string][] = [];
  for (const edges of perTask)
    for (const e of edges) {
      if (e.edge_type !== "depends-on") continue;
      const key = `${e.to_strand_id} ${e.from_strand_id}`;
      if (seen.has(key)) continue;
      seen.add(key);
      out.push([e.to_strand_id, e.from_strand_id]);
    }
  return out;
}

function buildGraphDot(payload: StatusPayload, depEdges: [string, string][], cols: number): { dot: string; fallback: string[] } {
  const rows = flattenTree(payload);
  // A strand id is not unique in the tree (a run appears under its task and its
  // spawner), so declare each node once, keyed by id, from its first occurrence.
  const meta = new Map<string, TreeRow>();
  for (const r of rows) if (!meta.has(r.id)) meta.set(r.id, r);

  // parent-of edges straight off the tree walk, deduped, self-edges dropped.
  const parentEdges = new Set<string>();
  const walk = (node: TreeNode, parentId: string | null) => {
    if (parentId && parentId !== node.id) parentEdges.add(`${parentId} ${node.id}`);
    for (const c of node.children ?? []) walk(c, node.id);
  };
  for (const root of payload.tree ?? []) walk(root, null);

  const direction = graphDirection(cols);
  const wrap = graphWrap(cols, meta.size, direction);

  const nodeLines = [...meta.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([id, r]) => {
      const label = [`${id} ${r.kind}`, r.status.text, ...wrapWords(r.title, wrap)].map(dotEscape).join("\\n");
      return `  "${dotEscape(id)}" [label = "${label}"];`;
    });

  const edgeLines: string[] = [];
  for (const key of parentEdges) {
    const [from, to] = key.split(" ") as [string, string];
    edgeLines.push(`  "${dotEscape(from)}" -> "${dotEscape(to)}";`);
  }
  // depends-on edges only between nodes actually in this subtree — the subgraph
  // relation can reach siblings outside the delegation the user is graphing.
  const depSeen = new Set<string>();
  for (const [from, to] of depEdges) {
    if (from === to || !meta.has(from) || !meta.has(to)) continue;
    const k = `${from} ${to}`;
    if (depSeen.has(k)) continue;
    depSeen.add(k);
    edgeLines.push(`  "${dotEscape(from)}" -> "${dotEscape(to)}" [style = dashed];`);
  }

  const body = [nodeLines.join("\n"), edgeLines.join("\n")].filter(Boolean).join("\n\n");
  const dot = `digraph delegation {\n  rankdir = ${direction};\n  node [shape = box];\n\n${body}\n}\n`;
  const fallback = rows.map((r) => `${"  ".repeat(r.depth)}${r.id} [${r.kind}] ${r.status.text} — ${oneLine(r.title)}`);
  return { dot, fallback };
}

// The lines the graph pane scrolls: the boxart when present, else the loud error
// banner over the indented-tree fallback so the pane is never blank.
function graphLines(g: GraphState): string[] {
  if (g.art) return g.art;
  const banner = g.error ? [`graph-easy unavailable: ${g.error}`, ""] : [];
  return [...banner, ...g.fallback];
}

// ── runs view ──────────────────────────────────────────────────────────────

const RUNS_HINT = "[runs] ↑↓/jk move · ⏎ attrs · a all/active · r refresh · v plans · ⇥ tab · q quit";

function RunsTable({
  rows: runs,
  selected,
  interactive,
  cols,
  termRows,
  all,
  loaded,
}: {
  rows: Row[];
  selected: number;
  interactive: boolean;
  cols: number;
  termRows: number;
  all: boolean;
  loaded: boolean;
}) {
  const now = new Date();
  if (runs.length === 0) {
    return <Text dimColor>{clip(loaded ? `no ${all ? "" : "active "}runs · v plans` : "loading runs…", cols)}</Text>;
  }
  const phaseText = (r: Row) => (r.phase ?? "?") + (r.mode === "interactive" ? "*" : "");
  const w = {
    id: fitCol("ID", runs.map((r) => r.id), 12),
    phase: fitCol("PHASE", runs.map(phaseText), 11),
    harness: fitCol("HARNESS", runs.map((r) => r.harness ?? "?"), 14),
    for: fitCol("FOR", runs.map((r) => r.for ?? "-"), 12),
    branch: fitCol("BRANCH", runs.map((r) => r.branch), 24),
    age: 6,
  };
  const promptWidth = Math.max(0, cols - 12 - w.id - w.phase - w.harness - w.for - w.branch - w.age);
  const { start, visible, below } = windowRows(runs, selected, interactive, termRows);

  return (
    <Box flexDirection="column">
      <TableRow
        width={cols}
        bold
        cells={[
          { text: pad("ID", w.id) }, { text: "  " },
          { text: pad("PHASE", w.phase) }, { text: "  " },
          { text: pad("HARNESS", w.harness) }, { text: "  " },
          { text: pad("FOR", w.for) }, { text: "  " },
          { text: pad("BRANCH", w.branch) }, { text: "  " },
          { text: pad("AGE", w.age) }, { text: "  " },
          { text: "PROMPT" },
        ]}
      />
      {visible.map((r, i) => {
        const isSelected = interactive && start + i === selected;
        const cells: Cell[] = [
          { text: pad(r.id, w.id) },
          { text: "  " },
          { text: pad(phaseText(r), w.phase), color: isSelected ? undefined : PHASE_COLOR[r.phase ?? ""], dimColor: !isSelected && TERMINAL_DIM.has(r.phase ?? "") },
          { text: "  " },
          { text: pad(r.harness ?? "?", w.harness) },
          { text: "  " },
          { text: pad(r.for ?? "-", w.for), dimColor: !isSelected },
          { text: "  " },
          { text: pad(r.branch, w.branch) },
          { text: "  " },
          { text: pad(age(r.startedAt, now), w.age) },
          { text: "  " },
          { text: clip(oneLine(r.prompt), promptWidth) },
        ];
        return <TableRow key={r.id} cells={cells} width={cols} inverse={isSelected} />;
      })}
      {interactive && <ListFooter hint={RUNS_HINT} cols={cols} start={start} below={below} total={runs.length} />}
    </Box>
  );
}

// ── plans view ─────────────────────────────────────────────────────────────

const PLANS_HINT = "[plans] ↑↓/jk move · ⏎ detail · d graph · r refresh · v runs · ⇥ tab · q quit";

function PlansTree({
  rows,
  selected,
  interactive,
  cols,
  termRows,
  loaded,
}: {
  rows: TreeRow[];
  selected: number;
  interactive: boolean;
  cols: number;
  termRows: number;
  loaded: boolean;
}) {
  if (rows.length === 0) {
    return <Text dimColor>{clip(loaded ? "no active delegations · v runs" : "loading plans…", cols)}</Text>;
  }
  const kindW = fitCol("KIND", rows.map((r) => r.kind), 5);
  const statusW = fitCol("STATUS", rows.map((r) => r.status.text), Math.max(12, Math.floor(cols / 3)));
  const treeW = Math.max(0, cols - kindW - statusW - 4);
  const { start, visible, below } = windowRows(rows, selected, interactive, termRows);

  return (
    <Box flexDirection="column">
      <TableRow
        width={cols}
        bold
        cells={[
          { text: pad("TREE", treeW) }, { text: "  " },
          { text: pad("KIND", kindW) }, { text: "  " },
          { text: "STATUS" },
        ]}
      />
      {visible.map((r, i) => {
        const isSelected = interactive && start + i === selected;
        const label = "  ".repeat(r.depth) + oneLine(r.title);
        const cells: Cell[] = [
          { text: pad(label, treeW) },
          { text: "  " },
          { text: pad(r.kind, kindW) },
          { text: "  " },
          { text: clip(r.status.text, statusW), color: isSelected ? undefined : r.status.color, dimColor: !isSelected && r.status.dim },
        ];
        return <TableRow key={r.path} cells={cells} width={cols} inverse={isSelected} />;
      })}
      {interactive && <ListFooter hint={PLANS_HINT} cols={cols} start={start} below={below} total={rows.length} />}
    </Box>
  );
}

// ── graph view ─────────────────────────────────────────────────────────────

const GRAPH_PAN = 8;

// Codepoint slice for the horizontal pan: boxart is single-column box-drawing
// characters, so dropping N codepoints drops N columns.
const sliceCols = (s: string, n: number): string => (n <= 0 ? s : [...s].slice(n).join(""));

function GraphPane({ g, cols, termRows }: { g: GraphState; cols: number; termRows: number }) {
  const lines = graphLines(g);
  if (!g.loaded && lines.length === 0) {
    return <Text dimColor>{clip(`rendering graph for ${g.root}…`, cols)}</Text>;
  }
  const viewport = graphViewport(termRows);
  const maxY = Math.max(0, lines.length - viewport);
  const fromY = Math.min(g.scrollY, maxY);
  const visible = lines.slice(fromY, fromY + viewport);
  const legend = g.art ? "──▶ parent-of · ╴╴▶ depends-on" : "tree fallback (graph-easy unavailable)";
  const scrollInfo =
    maxY > 0 || g.scrollX > 0 ? ` · ${fromY}↑ ${maxY - fromY}↓${g.scrollX > 0 ? ` · ${g.scrollX}→` : ""}` : "";

  return (
    <Box flexDirection="column">
      <Text dimColor>{clip(legend, cols)}</Text>
      <Box flexDirection="column">
        {visible.map((l, i) => (
          <Text key={fromY + i}>{clip(sliceCols(l, g.scrollX), cols)}</Text>
        ))}
      </Box>
      <Box marginTop={1}>
        <Text dimColor>
          {clip(`[graph ${g.root}] ↑↓/jk scroll · <> pan · g/G ends · esc back · r refresh · ⇥ tab · q quit${scrollInfo}`, cols)}
        </Text>
      </Box>
    </Box>
  );
}

// ── the tab ──────────────────────────────────────────────────────────────────

const emptyGraph = (root: string): GraphState => ({
  root,
  dot: null,
  art: null,
  error: null,
  fallback: [],
  loaded: false,
  scrollY: 0,
  scrollX: 0,
});

export const agentsTab = defineTab<AgentsView>({
  id: "agents",
  label: "AGENTS",
  init: () => ({
    mode: "runs",
    runs: { rows: [], loaded: false, failure: null, s: emptyListState() },
    plans: { rows: [], loaded: false, failure: null, s: emptyListState(), detail: null },
    graph: null,
  }),
  // The views hit different endpoints, so switching must refetch — and the graph
  // is keyed by its root so opening one (or graphing a different node) re-polls
  // immediately rather than waiting for the interval.
  fetchKey: (v) => (v.mode === "graph" ? `graph:${v.graph?.root ?? ""}` : v.mode),
  // The plans tree renders the full delegation forest and the graph is a fixed
  // subtree, so the all/active axis only applies to the runs table.
  allApplies: (v) => v.mode === "runs",
  // The graph is a full-pane view, not a modal detail, so ⇥ stays live there.
  inDetail: (v) => (v.mode === "runs" ? v.runs.s.view === "detail" : v.mode === "plans" ? v.plans.detail !== null : false),
  // Fetch the mode's dataset, then fold it into the latest view — but only if the
  // mode is unchanged: a result for a view the user toggled away from mid-fetch
  // drops itself rather than overwriting the other view.
  refresh: async (v, all) => {
    if (v.mode === "runs") {
      try {
        const rows = await fetchRunsRows(all);
        return (latest) =>
          latest.mode === "runs"
            ? { ...latest, runs: { rows, loaded: true, failure: null, s: followSelection(latest.runs.s, rows, (r) => r.id) } }
            : latest;
      } catch (e) {
        const failure = msg(e);
        return (latest) => (latest.mode === "runs" ? { ...latest, runs: { ...latest.runs, loaded: true, failure } } : latest);
      }
    }
    if (v.mode === "plans") {
      try {
        const rows = flattenTree((await strandJson(["agent", "status"])) as StatusPayload);
        return (latest) =>
          latest.mode === "plans"
            ? { ...latest, plans: { ...latest.plans, rows, loaded: true, failure: null, s: followSelection(latest.plans.s, rows, (r) => r.path) } }
            : latest;
      } catch (e) {
        const failure = msg(e);
        return (latest) => (latest.mode === "plans" ? { ...latest, plans: { ...latest.plans, loaded: true, failure } } : latest);
      }
    }

    // graph mode: fetch the root's subtree + depends-on edges, build DOT, and —
    // unless the DOT is unchanged from the last render — shell out to graph-easy.
    // The whole pipeline is awaited here (never blocking the Ink render loop) and
    // the app shell serialises refreshes, so there is only ever one graph-easy in
    // flight per tab. The returned updater drops itself when the user has since
    // left the graph or graphed a different root, so a slow render can't land on
    // the wrong pane. Width comes from the live terminal, not the fetch args.
    const g = v.graph;
    if (!g) return (latest) => latest;
    const root = g.root;
    try {
      // Dep edges need the subtree's task ids, so status is fetched first; its
      // per-task subgraph queries then fan out in parallel inside fetchDepEdges.
      const statusPayload = (await strandJson(["agent", "status", root])) as StatusPayload;
      const taskIds = [...new Set(flattenTree(statusPayload).filter((r) => r.kind === "task").map((r) => r.id))];
      const depEdges = await fetchDepEdges(taskIds).catch(() => [] as [string, string][]);
      const { dot, fallback } = buildGraphDot(statusPayload, depEdges, process.stdout.columns || 120);
      // Skip the re-render only when the last one succeeded on this same DOT (art
      // present ⇒ no error): an errored render leaves art null so it retries here.
      if (g.dot === dot && g.art) {
        return (latest) =>
          latest.mode === "graph" && latest.graph?.root === root
            ? { ...latest, graph: { ...latest.graph, fallback, loaded: true } }
            : latest;
      }
      const { lines, error } = await renderBoxart(dot);
      return (latest) => {
        if (latest.mode !== "graph" || latest.graph?.root !== root) return latest;
        // A failed render drops to null art so the pane shows the error banner
        // over the indented-tree fallback rather than lingering on stale boxart.
        return { ...latest, graph: { ...latest.graph, dot, fallback, loaded: true, art: lines, error } };
      };
    } catch (e) {
      const failure = msg(e);
      // An upstream fetch failure (status/dep query) drops art to null too, so the
      // pane surfaces the error banner over the last-known tree fallback instead of
      // lingering on stale boxart — matching the failed-render path above.
      return (latest) =>
        latest.mode === "graph" && latest.graph?.root === root
          ? { ...latest, graph: { ...latest.graph, loaded: true, error: failure, art: null } }
          : latest;
    }
  },
  onKey: (v, ctx) => {
    const { input, key } = ctx;
    if (input === "v") return { ...v, mode: v.mode === "runs" ? "plans" : "runs" };

    if (v.mode === "runs") {
      const rs = v.runs;
      if (rs.s.view === "detail") {
        const r = reduceScrollKeys(rs.s.detailScroll, input, key, detailMaxScroll(rs.rows[rs.s.selected], ctx.cols, ctx.termRows));
        if (r === "back") return { ...v, runs: { ...rs, s: { ...rs.s, view: "list" } } };
        if (r !== null) return { ...v, runs: { ...rs, s: { ...rs.s, detailScroll: r } } };
        if (input === "r") ctx.refresh();
        return v;
      }
      const moved = reduceListKeys(rs.s, input, key, rs.rows, (r) => r.id);
      if (moved) return { ...v, runs: { ...rs, s: moved } };
      if (key.return || key.rightArrow || input === "l")
        return rs.rows[rs.s.selected] ? { ...v, runs: { ...rs, s: { ...rs.s, view: "detail", detailScroll: 0 } } } : v;
      if (input === "r") ctx.refresh();
      return v;
    }

    if (v.mode === "graph") {
      const g = v.graph;
      if (!g) return { ...v, mode: "plans" };
      if (key.escape || key.leftArrow || input === "h") return { ...v, mode: "plans" };
      if (input === "r") {
        ctx.refresh();
        return v;
      }
      const lines = graphLines(g);
      const maxY = Math.max(0, lines.length - graphViewport(ctx.termRows));
      const maxX = Math.max(0, Math.max(0, ...lines.map((l) => stringWidth(l))) - ctx.cols);
      if (key.upArrow || input === "k") return { ...v, graph: { ...g, scrollY: Math.max(0, g.scrollY - 1) } };
      if (key.downArrow || input === "j") return { ...v, graph: { ...g, scrollY: Math.min(maxY, g.scrollY + 1) } };
      if (input === "g") return { ...v, graph: { ...g, scrollY: 0 } };
      if (input === "G") return { ...v, graph: { ...g, scrollY: maxY } };
      if (input === "<") return { ...v, graph: { ...g, scrollX: Math.max(0, g.scrollX - GRAPH_PAN) } };
      if (input === ">") return { ...v, graph: { ...g, scrollX: Math.min(maxX, g.scrollX + GRAPH_PAN) } };
      return v;
    }

    const ps = v.plans;
    if (ps.detail) {
      const d = ps.detail;
      const r = reduceScrollKeys(d.scroll, input, key, detailMaxScroll(d.row ?? undefined, ctx.cols, ctx.termRows));
      if (r === "back") {
        closePlanDetail();
        return { ...v, plans: { ...ps, detail: null } };
      }
      if (r !== null) return { ...v, plans: { ...ps, detail: { ...d, scroll: r } } };
      if (input === "r") {
        openPlanDetail(d.id, d.phase, ctx.setV);
        return { ...v, plans: { ...ps, detail: { ...d, loading: true } } };
      }
      return v;
    }
    const moved = reduceListKeys(ps.s, input, key, ps.rows, (r) => r.path);
    if (moved) return { ...v, plans: { ...ps, s: moved } };
    if (input === "d") {
      const r = ps.rows[ps.s.selected];
      return r ? { ...v, mode: "graph", graph: emptyGraph(r.id) } : v;
    }
    if (key.return || key.rightArrow || input === "l") {
      const r = ps.rows[ps.s.selected];
      if (!r) return v;
      openPlanDetail(r.id, r.phase, ctx.setV);
      return { ...v, plans: { ...ps, detail: { id: r.id, phase: r.phase, loading: true, row: null, failure: null, scroll: 0 } } };
    }
    if (input === "r") ctx.refresh();
    return v;
  },
  render: (v, ctx) => {
    const { cols, termRows, interactive, all } = ctx;
    // Non-interactive frames (--once / non-TTY) can't toggle, so they print the
    // flat runs frame — the default mode's fetched rows.
    if (!interactive) {
      return <RunsTable rows={v.runs.rows} selected={0} interactive={false} cols={cols} termRows={termRows} all={all} loaded={v.runs.loaded} />;
    }
    if (v.mode === "runs") {
      const rs = v.runs;
      if (rs.failure) return <Failure failure={rs.failure} cols={cols} />;
      if (rs.s.view === "detail") return <DetailView row={rs.rows[rs.s.selected]} scroll={rs.s.detailScroll} cols={cols} termRows={termRows} />;
      return <RunsTable rows={rs.rows} selected={rs.s.selected} interactive cols={cols} termRows={termRows} all={all} loaded={rs.loaded} />;
    }
    if (v.mode === "graph") {
      return v.graph ? <GraphPane g={v.graph} cols={cols} termRows={termRows} /> : <Text dimColor>{clip("no graph selected", cols)}</Text>;
    }
    const ps = v.plans;
    if (ps.detail) {
      const d = ps.detail;
      if (d.loading) return <Text dimColor>{clip(`loading ${d.id}… · esc back`, cols)}</Text>;
      if (d.failure) return <Failure failure={d.failure} cols={cols} />;
      return <DetailView row={d.row ?? undefined} scroll={d.scroll} cols={cols} termRows={termRows} />;
    }
    if (ps.failure) return <Failure failure={ps.failure} cols={cols} />;
    return <PlansTree rows={ps.rows} selected={ps.s.selected} interactive cols={cols} termRows={termRows} loaded={ps.loaded} />;
  },
});
