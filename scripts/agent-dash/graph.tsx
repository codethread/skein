// Shared graph pipeline: a strand subtree → Graphviz DOT → graph-easy boxart in a
// scrollable/pannable pane, degrading to an indented-tree fallback when graph-easy
// is missing or errors. AGENTS (a delegation subtree) and DEVFLOW (a feature's
// workflow DAG) both feed it the same generic GraphNode list plus parent-of and
// depends-on edge pairs, so the DOT assembly, boxart render wiring, fallback, pane,
// and scroll/pan keys live here once rather than once per tab.

import { Box, Text, type Key } from "ink";
import stringWidth from "string-width";
import { strandJson } from "./data";
import { clip, graphPage, graphViewport, oneLine } from "./ui";

// One node in a graph: strand id, a short kind tag, a joined status cell, and the
// title (word-wrapped at DOT-build time). `depth` positions it in the indented
// fallback only — the boxart layout comes from the edges.
export type GraphNode = { id: string; kind: string; status: string; title: string; depth: number };

// A directed edge as a [from, to] strand-id pair.
export type Edge = [string, string];

// `dot` is the last DOT handed to graph-easy: an unchanged DOT whose last render
// succeeded skips a redundant (and possibly slow) re-render. `art` is the rendered
// boxart, null whenever the last render failed so the pane falls back to the
// `error` banner over the indented-tree `fallback`. Scroll is 2-D because a wide LR
// graph overflows horizontally.
export type GraphState = {
  root: string;
  dot: string | null;
  art: string[] | null;
  error: string | null;
  fallback: string[];
  loaded: boolean;
  scrollY: number;
  scrollX: number;
};

export const emptyGraph = (root: string): GraphState => ({
  root,
  dot: null,
  art: null,
  error: null,
  fallback: [],
  loaded: false,
  scrollY: 0,
  scrollX: 0,
});

// ── DOT build ────────────────────────────────────────────────────────────────

const GRAPH_PADDING = 16;

// LR reads best on a wide terminal; a narrow one has to stack ranks top-to-bottom
// or every box is shaved to nothing (show.nu's 120-column pivot).
export function graphDirection(cols: number): "LR" | "TB" {
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

// DOT string literals only need backslash and quote escaped; embedded newlines are
// collapsed by oneLine before wrapping, so none survive into a segment.
const dotEscape = (s: string): string => s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');

type SubgraphEdge = { from_strand_id: string; to_strand_id: string; edge_type: string };
type SubgraphPayload = { edges?: SubgraphEdge[] };

// depends-on reads "from depends-on to", so `to` is the prerequisite. Draw the
// arrow prerequisite→dependent so blockers point at the work they gate, matching
// the parent-of flow. A `subgraph <root> --relation depends-on` only walks
// depends-on outward from the root, so a subtree whose children hang off parent-of
// yields nothing — the blockers live between the leaf work nodes. Query depends-on
// from each id in the subtree instead and union the results. Best-effort: a failed
// query drops that node's edges rather than the render.
export async function fetchDepEdges(ids: string[]): Promise<Edge[]> {
  const perNode = await Promise.all(
    ids.map((id) =>
      (strandJson(["subgraph", id, "--relation", "depends-on"]) as Promise<SubgraphPayload>)
        .then((sg) => sg.edges ?? [])
        .catch(() => [] as SubgraphEdge[]),
    ),
  );
  const seen = new Set<string>();
  const out: Edge[] = [];
  for (const edges of perNode)
    for (const e of edges) {
      if (e.edge_type !== "depends-on") continue;
      const key = `${e.to_strand_id} ${e.from_strand_id}`;
      if (seen.has(key)) continue;
      seen.add(key);
      out.push([e.to_strand_id, e.from_strand_id]);
    }
  return out;
}

// Assemble DOT from generic nodes plus parent-of (solid) and depends-on (dashed)
// edges, and the indented-tree fallback the pane shows when graph-easy is absent.
// `nodes` is the pre-order tree walk (a strand id may repeat at different depths,
// e.g. a run under both its task and its spawner): the fallback keeps that order
// and depth, while node declarations dedupe by id from the first occurrence.
export function buildGraphDot(nodes: GraphNode[], parentEdges: Edge[], depEdges: Edge[], cols: number): { dot: string; fallback: string[] } {
  const meta = new Map<string, GraphNode>();
  for (const n of nodes) if (!meta.has(n.id)) meta.set(n.id, n);

  const direction = graphDirection(cols);
  const wrap = graphWrap(cols, meta.size, direction);

  const nodeLines = [...meta.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([id, n]) => {
      const label = [`${id} ${n.kind}`, n.status, ...wrapWords(n.title, wrap)].map(dotEscape).join("\\n");
      return `  "${dotEscape(id)}" [label = "${label}"];`;
    });

  // Only draw edges whose endpoints are both declared nodes — a subgraph relation
  // can reach siblings outside the subtree being graphed. Dedupe, drop self-edges.
  const edgeLines: string[] = [];
  const emit = (edges: Edge[], style: string) => {
    const seen = new Set<string>();
    for (const [from, to] of edges) {
      if (from === to || !meta.has(from) || !meta.has(to)) continue;
      const k = `${from} ${to}`;
      if (seen.has(k)) continue;
      seen.add(k);
      edgeLines.push(`  "${dotEscape(from)}" -> "${dotEscape(to)}"${style};`);
    }
  };
  emit(parentEdges, "");
  emit(depEdges, " [style = dashed]");

  const body = [nodeLines.join("\n"), edgeLines.join("\n")].filter(Boolean).join("\n\n");
  const dot = `digraph delegation {\n  rankdir = ${direction};\n  node [shape = box];\n\n${body}\n}\n`;
  const fallback = nodes.map((n) => `${"  ".repeat(n.depth)}${n.id} [${n.kind}] ${n.status} — ${oneLine(n.title)}`);
  return { dot, fallback };
}

// The lines the graph pane scrolls: the boxart when present, else the loud error
// banner over the indented-tree fallback so the pane is never blank.
export function graphLines(g: GraphState): string[] {
  if (g.art) return g.art;
  const banner = g.error ? [`graph-easy unavailable: ${g.error}`, ""] : [];
  return [...banner, ...g.fallback];
}

// ── pane + keys ──────────────────────────────────────────────────────────────

const GRAPH_PAN = 8;

// Codepoint slice for the horizontal pan: boxart is single-column box-drawing
// characters, so dropping N codepoints drops N columns.
const sliceCols = (s: string, n: number): string => (n <= 0 ? s : [...s].slice(n).join(""));

// Graph-pane scroll (↑↓/jk, ⌃u/⌃d half-page, g/G), pan (<>), and back (esc/h/←).
// Returns the next state, "back" to leave the pane, or null when the key is not a
// graph movement so the caller can layer refresh/tab on top. maxima are derived
// from the current lines so scroll can't run past the rendered art.
export function reduceGraphKeys(g: GraphState, input: string, key: Key, cols: number, termRows: number): GraphState | "back" | null {
  if (key.escape || key.leftArrow || input === "h") return "back";
  const lines = graphLines(g);
  const maxY = Math.max(0, lines.length - graphViewport(termRows));
  const maxX = Math.max(0, Math.max(0, ...lines.map((l) => stringWidth(l))) - cols);
  const gp = graphPage(termRows);
  if (key.ctrl && input === "u") return { ...g, scrollY: Math.max(0, g.scrollY - gp) };
  if (key.ctrl && input === "d") return { ...g, scrollY: Math.min(maxY, g.scrollY + gp) };
  if (key.upArrow || input === "k") return { ...g, scrollY: Math.max(0, g.scrollY - 1) };
  if (key.downArrow || input === "j") return { ...g, scrollY: Math.min(maxY, g.scrollY + 1) };
  if (input === "g") return { ...g, scrollY: 0 };
  if (input === "G") return { ...g, scrollY: maxY };
  if (input === "<") return { ...g, scrollX: Math.max(0, g.scrollX - GRAPH_PAN) };
  if (input === ">") return { ...g, scrollX: Math.min(maxX, g.scrollX + GRAPH_PAN) };
  return null;
}

// The scrollable boxart pane. `label` names the subject in the footer/hint; the
// legend switches to a fallback notice when graph-easy produced no art.
export function GraphPane({ g, cols, termRows, label }: { g: GraphState; cols: number; termRows: number; label: string }) {
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
          {clip(`[${label}] ↑↓/jk scroll · ⌃d/⌃u page · <> pan · g/G ends · esc back · r refresh · ⇥ tab · q quit${scrollInfo}`, cols)}
        </Text>
      </Box>
    </Box>
  );
}
