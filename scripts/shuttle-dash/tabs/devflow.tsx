// DEVFLOW tab: a high-level view of devflow workflow runs. Polls
// `strand list --query devflow-runs`, then enriches each active run with
// `strand flow-status <feature>` for its actionable frontier, agent
// failures, and stalled gates. Self-contained: the row type, fetcher, colour
// maps, and list component all live here so the tab is edited in one place.
//
// Detail rendering is shell-owned (app.tsx routes to the shared DetailView,
// which lists a strand's attributes in sorted-key order — a tab cannot supply
// its own detail component). The frontier "section" is therefore composed into
// a synthetic `frontier` attribute on the row so the shared view renders it
// alongside the run strand's real attributes.

import { Box, Text } from "ink";
import { str, strandJson, type DetailRow, type StrandRecord } from "../data";
import { clip, fitCol, ListFooter, oneLine, pad, TableRow, windowRows, type Cell, type ListProps } from "../ui";
import { listDetailTab } from "../app";

type FrontierItem = {
  id: string;
  kind: string; // "step" | "checkpoint"
  title: string;
  state?: string;
  instruction?: string;
  hitl: boolean; // human-in-the-loop checkpoint (workflow/hitl on the item strand)
};

type StateLabel = "error" | "attention" | "active" | "done";

export type DevflowRow = DetailRow & {
  feature: string;
  stage: string;
  stateLabel: StateLabel;
  frontier: FrontierItem[];
  failCount: number;
  // Set when flow-status enrichment failed for an active run: the row renders
  // loud (red "error" state) instead of a healthy-looking zero frontier.
  flowError?: string;
};

const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

const STATE_COLOR: Record<StateLabel, string | undefined> = { error: "red", attention: "red", active: "green", done: undefined };
// error floats highest, then attention above active; done sinks. Stable within
// groups (Array.sort).
const STATE_RANK: Record<StateLabel, number> = { error: 0, attention: 1, active: 2, done: 3 };

type FlowStatus = {
  done?: boolean;
  frontier?: { id: string; kind: string; title: string; state?: string; instruction?: string }[];
  "agent-failures"?: unknown[];
  "stalled-gates"?: unknown[];
};

// hitl is not carried inline in the flow-status frontier, and only checkpoints
// can be human decisions, so we only pay the extra `strand show` for those.
async function frontierWithHitl(raw: NonNullable<FlowStatus["frontier"]>): Promise<FrontierItem[]> {
  return Promise.all(
    raw.map(async (f): Promise<FrontierItem> => {
      let hitl = false;
      if (f.kind === "checkpoint") {
        try {
          const item = (await strandJson(["show", f.id])) as StrandRecord;
          hitl = item.attributes?.["workflow/hitl"] === "true";
        } catch {
          hitl = false;
        }
      }
      return { id: f.id, kind: f.kind, title: str(f.title), state: f.state, instruction: f.instruction, hitl };
    }),
  );
}

function frontierAttr(frontier: FrontierItem[]): string {
  if (frontier.length === 0) return "none";
  return frontier
    .map((f, i) => {
      const head = `${i + 1}. ${f.kind} ${f.id}${f.hitl ? " [HITL]" : ""}  ${oneLine(f.title)}`;
      return f.instruction ? `${head}\n   ↳ ${oneLine(f.instruction)}` : head;
    })
    .join("\n");
}

async function fetchDevflow(all: boolean): Promise<DevflowRow[]> {
  const args = ["list", "--query", "devflow-runs", ...(all ? [] : ["--state", "active"])];
  const runs = (await strandJson(args)) as StrandRecord[];
  const rows = await Promise.all(
    runs.map(async (s): Promise<DevflowRow> => {
      const attrs = s.attributes;
      const feature = str(attrs["devflow/feature"], "-");
      const stage = str(attrs["devflow/stage"], "-");
      const base = {
        id: s.id,
        title: s.title,
        state: s.state,
        branch: "-",
        createdAt: s.created_at,
        updatedAt: s.updated_at,
        feature,
        stage,
      };
      // Only live runs have a meaningful frontier; closed/archived runs sink to
      // "done" and skip flow-status entirely (it errors on unknown run-ids).
      if (s.state !== "active") {
        return { ...base, attrs, stateLabel: "done", frontier: [], failCount: 0 };
      }
      // Enrichment must fail loudly: a broken flow-status call on an active run
      // surfaces as a red "error" row carrying the message (list + detail view),
      // never a healthy-looking zero frontier that hides an actionable failure.
      let status: FlowStatus;
      try {
        status = (await strandJson(["flow-status", feature])) as FlowStatus;
      } catch (e) {
        const flowError = e instanceof Error ? e.message : String(e);
        return {
          ...base,
          attrs: { ...attrs, "flow-status-error": flowError },
          stateLabel: "error",
          frontier: [],
          failCount: 0,
          flowError,
        };
      }
      const frontier = await frontierWithHitl(status.frontier ?? []);
      const failCount = arr(status["agent-failures"]).length;
      const stalled = arr(status["stalled-gates"]).length;
      const stateLabel: StateLabel = status.done ? "done" : failCount > 0 || stalled > 0 ? "attention" : "active";
      return {
        ...base,
        attrs: { ...attrs, frontier: frontierAttr(frontier) },
        stateLabel,
        frontier,
        failCount,
      };
    }),
  );
  return rows.sort((a, b) => STATE_RANK[a.stateLabel] - STATE_RANK[b.stateLabel]);
}

const frontierText = (r: DevflowRow): string =>
  r.flowError
    ? `flow-status failed: ${oneLine(r.flowError)}`
    : r.frontier.length === 0
      ? "0"
      : `${r.frontier.length} · ${oneLine(r.frontier[0]!.title)}`;

function DevflowList({ rows, selected, interactive, cols, termRows, all, loaded }: ListProps<DevflowRow>) {
  if (rows.length === 0) {
    return <Text dimColor>{clip(loaded ? `no ${all ? "" : "active "}devflow runs` : "loading devflow runs…", cols)}</Text>;
  }
  const w = {
    feature: fitCol("FEATURE", rows.map((r) => r.feature), 28),
    stage: fitCol("STAGE", rows.map((r) => r.stage), 14),
    state: fitCol("STATE", rows.map((r) => r.stateLabel), 10),
    fail: fitCol("FAIL", rows.map((r) => String(r.failCount)), 5),
  };
  const frontierWidth = Math.max(0, cols - 8 - w.feature - w.stage - w.state - w.fail);
  const { start, visible, below } = windowRows(rows, selected, interactive, termRows);

  return (
    <Box flexDirection="column">
      <TableRow
        width={cols}
        bold
        cells={[
          { text: pad("FEATURE", w.feature) }, { text: "  " },
          { text: pad("STAGE", w.stage) }, { text: "  " },
          { text: pad("STATE", w.state) }, { text: "  " },
          { text: pad("FRONTIER", frontierWidth) }, { text: "  " },
          { text: pad("FAIL", w.fail) },
        ]}
      />
      {visible.map((r, i) => {
        const isSelected = interactive && start + i === selected;
        const cells: Cell[] = [
          { text: pad(r.feature, w.feature) },
          { text: "  " },
          { text: pad(r.stage, w.stage) },
          { text: "  " },
          { text: pad(r.stateLabel, w.state), color: isSelected ? undefined : STATE_COLOR[r.stateLabel], dimColor: !isSelected && r.stateLabel === "done" },
          { text: "  " },
          { text: pad(clip(frontierText(r), frontierWidth), frontierWidth) },
          { text: "  " },
          { text: pad(String(r.failCount), w.fail), color: isSelected ? undefined : r.failCount > 0 ? "red" : undefined, dimColor: !isSelected && r.failCount === 0 },
        ];
        return <TableRow key={r.id} cells={cells} width={cols} inverse={isSelected} />;
      })}
      {interactive && (
        <ListFooter hint="↑↓/jk move · ⏎ attrs+frontier · a all/active · r refresh · ⇥ tab · q quit" cols={cols} start={start} below={below} total={rows.length} />
      )}
    </Box>
  );
}

export const devflowTab = listDetailTab<DevflowRow>({ id: "devflow", label: "DEVFLOW", fetch: fetchDevflow, List: DevflowList });
