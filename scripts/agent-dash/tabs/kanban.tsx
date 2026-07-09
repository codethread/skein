// KANBAN tab: the user↔agent work board as strands (spools/kanban.md). Polls
// `strand list --query kanban-cards` for the board and, alongside it, scans the
// active strands for the land workflow's merge-lock sentinel, which it surfaces
// as a one-line status strip above the board. Self-contained: the row type, both
// fetchers, colour maps, list component, and the merge-lock banner all live here
// so the tab is edited in exactly one place — it composes defineTab directly
// (like the agents/devflow tabs) rather than a shared list helper, keeping the
// banner's failure isolation and reserved-row paging local to its one caller.

import { Box, Text } from "ink";
import { parseInstant, str, strandJson, type DetailRow, type StrandRecord } from "../data";
import {
  age,
  clip,
  DetailView,
  detailMaxScroll,
  detailPage,
  Failure,
  fitCol,
  listPage,
  ListFooter,
  oneLine,
  pad,
  TableRow,
  windowRows,
  type Cell,
  type ListProps,
} from "../ui";
import { defineTab, emptyListState, followSelection, reduceListKeys, reduceScrollKeys, type ListState, type RenderCtx } from "../app";

export type KanbanRow = DetailRow & {
  lane: string;
  type: string;
  owner: string;
  priority: string;
};

const LANE_COLOR: Record<string, string | undefined> = { claimed: "green", in_review: "magenta", pending: "yellow", refinement: "cyan" };

// Priority tint mirrors the spool's p1..p4 urgency (spools/kanban.md): p1 is an
// immediate blocker, p4 is someday. p3 is the unstamped default and stays plain.
const PRIO_COLOR: Record<string, string | undefined> = { p1: "red", p2: "yellow" };
const prioDim = (p: string): boolean => p === "p4";

// Board lane order is review-first urgency, not the spool's lifecycle order:
// claimed work in flight, then the cards under review that a coordinator should
// clear next (in_review), then the actionable queue (pending), then ideas still
// in refinement. Closed strands sink regardless of their kanban/status — migrated
// cards can carry a stale lane attr after close — and show their outcome
// (done/abandoned/...) dimmed.
const LANE_RANK: Record<string, number> = { claimed: 0, in_review: 1, pending: 2, refinement: 3 };
const laneRank = (r: KanbanRow): number => (r.state === "closed" ? 4 : (LANE_RANK[r.lane] ?? 4));

async function fetchKanban(all: boolean): Promise<KanbanRow[]> {
  const args = ["list", "--query", "kanban-cards", ...(all ? [] : ["--state", "active"])];
  const items = (await strandJson(args)) as StrandRecord[];
  const rows = items.map((s): KanbanRow => {
    const attrs = s.attributes;
    return {
      id: s.id,
      title: s.title,
      state: s.state,
      branch: str(attrs["branch"], "-"),
      lane: str(attrs["kanban/status"], "?"),
      type: str(attrs["kanban/type"], "feature"),
      owner: str(attrs["owner"], "-"),
      priority: str(attrs["kanban/priority"], "p3"),
      createdAt: s.created_at,
      updatedAt: s.updated_at,
      attrs,
    };
  });
  // created_at is "YYYY-MM-DD HH:MM:SS" (UTC), so lexical order is chronological,
  // and "p1".."p4" also compares lexically. Active lanes are queues and sort
  // priority-first then oldest-first to agree with `kanban next` (spools/kanban.md);
  // the closed bucket lists newest-first so fresh outcomes stay in reach.
  return rows.sort((a, b) => {
    const rank = laneRank(a) - laneRank(b);
    if (rank !== 0) return rank;
    if (a.state === "closed") return b.createdAt.localeCompare(a.createdAt);
    const prio = a.priority.localeCompare(b.priority);
    if (prio !== 0) return prio;
    return a.createdAt.localeCompare(b.createdAt);
  });
}

function KanbanList({ rows, selected, interactive, cols, termRows, all, loaded }: ListProps<KanbanRow>) {
  if (rows.length === 0) {
    return <Text dimColor>{clip(loaded ? `no ${all ? "" : "active "}cards` : "loading board…", cols)}</Text>;
  }
  const w = {
    id: fitCol("ID", rows.map((r) => r.id), 12),
    lane: fitCol("LANE", rows.map((r) => r.lane), 12),
    prio: fitCol("PRIO", rows.map((r) => r.priority), 4),
    type: fitCol("TYPE", rows.map((r) => r.type), 8),
    owner: fitCol("OWNER", rows.map((r) => r.owner), 14),
    branch: fitCol("BRANCH", rows.map((r) => r.branch), 24),
  };
  const titleWidth = Math.max(0, cols - 12 - w.id - w.lane - w.prio - w.type - w.owner - w.branch);
  const { start, visible, below } = windowRows(rows, selected, interactive, termRows);

  return (
    <Box flexDirection="column">
      <TableRow
        width={cols}
        bold
        cells={[
          { text: pad("ID", w.id) }, { text: "  " },
          { text: pad("LANE", w.lane) }, { text: "  " },
          { text: pad("PRIO", w.prio) }, { text: "  " },
          { text: pad("TYPE", w.type) }, { text: "  " },
          { text: pad("OWNER", w.owner) }, { text: "  " },
          { text: pad("BRANCH", w.branch) }, { text: "  " },
          { text: "TITLE" },
        ]}
      />
      {visible.map((r, i) => {
        const isSelected = interactive && start + i === selected;
        const closed = r.state === "closed";
        const cells: Cell[] = [
          { text: pad(r.id, w.id) },
          { text: "  " },
          { text: pad(r.lane, w.lane), color: isSelected || closed ? undefined : LANE_COLOR[r.lane], dimColor: !isSelected && closed },
          { text: "  " },
          { text: pad(r.priority, w.prio), color: isSelected || closed ? undefined : PRIO_COLOR[r.priority], dimColor: !isSelected && (closed || prioDim(r.priority)) },
          { text: "  " },
          { text: pad(r.type, w.type), dimColor: !isSelected && r.type !== "epic" },
          { text: "  " },
          { text: pad(r.owner, w.owner), dimColor: !isSelected },
          { text: "  " },
          { text: pad(r.branch, w.branch) },
          { text: "  " },
          { text: clip(oneLine(r.title ?? ""), titleWidth) },
        ];
        return <TableRow key={r.id} cells={cells} width={cols} inverse={isSelected} />;
      })}
      {interactive && (
        <ListFooter hint="↑↓/jk move · ⌃d/⌃u page · ⏎ attrs · ⌃g open · a all/active · r refresh · ⇥ tab · q quit" cols={cols} start={start} below={below} total={rows.length} />
      )}
    </Box>
  );
}

// The singleton merge sentinel (land workflow): at most one active kind=merge-lock
// strand exists, acquired at land sign-off and released at cleanup/abort. Its
// owner is the land root id, land/run-id is the feature, and created_at is when
// the lock was acquired. The active-strand list is the cleanest read that needs no
// registered query — the board payload omits the lock — so we scan it for locks.
//
// LockState is what that scan resolves to. The dash is the coordinator's anomaly
// watch, so it never normalizes a corrupt world into a plausible banner: >1 active
// lock is corruption (break-merge-lock! throws on it), and a lock missing the
// owner/land-run-id that acquire-merge-lock! always sets together is a tampered
// record — both surface as their own loud state rather than a clean "MERGE LOCK"
// strip. A failed scan is likewise isolated so the board still renders (see refresh).
type MergeLock = { id: string; owner: string; feature: string; acquiredAt: string };
type LockState =
  | { kind: "none" }
  | { kind: "ok"; lock: MergeLock }
  | { kind: "corrupt"; count: number }
  | { kind: "malformed"; id: string; missing: string[] }
  | { kind: "error"; message: string };

async function fetchMergeLock(): Promise<LockState> {
  let items: StrandRecord[];
  try {
    items = (await strandJson(["list", "--state", "active"])) as StrandRecord[];
  } catch (e) {
    return { kind: "error", message: e instanceof Error ? e.message : String(e) };
  }
  const locks = items.filter((s) => str(s.attributes["kind"]) === "merge-lock");
  if (locks.length === 0) return { kind: "none" };
  if (locks.length > 1) return { kind: "corrupt", count: locks.length };
  const lock = locks[0]!;
  const owner = str(lock.attributes["owner"]);
  const feature = str(lock.attributes["land/run-id"]);
  const missing = [owner ? "" : "owner", feature ? "" : "land/run-id"].filter(Boolean);
  if (missing.length > 0) return { kind: "malformed", id: lock.id, missing };
  return { kind: "ok", lock: { id: lock.id, owner, feature, acquiredAt: lock.created_at } };
}

// Reserved viewport lines: the banner draws one line for every state except a
// clean "no lock", so the list windows against the height it actually gets (see
// render/onKey). Kept in lockstep with MergeLockBanner returning null only here.
const lockRows = (st: LockState): number => (st.kind === "none" ? 0 : 1);

// One clipped line above the board. A healthy lock reads magenta so a coordinator
// sees a merge in flight at a glance (feature, owning land run, how long held);
// every anomaly reads red and loud so corruption is never mistaken for a normal
// in-flight merge. Null only when no lock is active, matching lockRows's 0.
function MergeLockBanner(st: LockState, ctx: RenderCtx) {
  const line = (color: string, text: string) => (
    <Text bold color={color}>
      {clip(text, ctx.cols)}
    </Text>
  );
  switch (st.kind) {
    case "none":
      return null;
    case "ok": {
      const held = age(parseInstant(st.lock.acquiredAt), new Date());
      return line("magenta", `MERGE LOCK · ${st.lock.feature} · owner ${st.lock.owner} · held ${held}`);
    }
    case "corrupt":
      return line("red", `${st.count} ACTIVE MERGE LOCKS · state corrupt · run land break-lock`);
    case "malformed":
      return line("red", `MERGE LOCK ${st.id} malformed · missing ${st.missing.join(", ")} · inspect the strand`);
    case "error":
      return line("red", `merge-lock check failed · ${oneLine(st.message)}`);
  }
}

// kanban is a plain list+detail tab with a status strip, so it composes the shared
// movement/scroll reducers directly (like agents/devflow) rather than a shared list
// helper. Owning the tab locally is what lets the banner scan fail without blanking
// the board and lets its reserved row flow into the paging math, not just the render.
type KanbanView = { rows: KanbanRow[]; lock: LockState; loaded: boolean; failure: string | null; s: ListState };

const keyOf = (r: KanbanRow): string => r.id;

export const kanbanTab = defineTab<KanbanView>({
  id: "kanban",
  label: "KANBAN",
  init: () => ({ rows: [], lock: { kind: "none" }, loaded: false, failure: null, s: emptyListState() }),
  fetchKey: () => "",
  allApplies: () => true,
  inDetail: (v) => v.s.view === "detail",
  editTarget: (v) => v.rows[v.s.selected] ?? null,
  refresh: async (_v, all) => {
    // The board is the tab's primary data: a kanban-cards failure surfaces as the
    // full-pane <Failure>. The merge-lock scan is isolated inside fetchMergeLock
    // (it resolves failures to a LockState.error rather than throwing), so a flaky
    // active-strand scan degrades to a loud banner and never blanks a good board.
    try {
      const [rows, lock] = await Promise.all([fetchKanban(all), fetchMergeLock()]);
      return (latest) => ({ rows, lock, loaded: true, failure: null, s: followSelection(latest.s, rows, keyOf) });
    } catch (e) {
      const failure = e instanceof Error ? e.message : String(e);
      return (latest) => ({ ...latest, loaded: true, failure });
    }
  },
  onKey: (v, ctx) => {
    const { input, key } = ctx;
    if (v.s.view === "detail") {
      const r = reduceScrollKeys(v.s.detailScroll, input, key, detailMaxScroll(v.rows[v.s.selected], ctx.cols, ctx.termRows), detailPage(ctx.termRows));
      if (r === "back") return { ...v, s: { ...v.s, view: "list" } };
      if (r !== null) return { ...v, s: { ...v.s, detailScroll: r } };
      if (input === "r") ctx.refresh();
      return v;
    }
    // The banner steals a list row when it draws, so page jumps size against the
    // same reduced viewport the list renders into (below), not the raw terminal.
    const listRows = ctx.termRows - lockRows(v.lock);
    const moved = reduceListKeys(v.s, input, key, v.rows, keyOf, listPage(listRows));
    if (moved) return { ...v, s: moved };
    if (key.return || key.rightArrow || input === "l")
      return v.rows[v.s.selected] ? { ...v, s: { ...v.s, view: "detail", detailScroll: 0 } } : v;
    if (input === "r") ctx.refresh();
    return v;
  },
  render: (v, ctx) => {
    if (v.failure) return <Failure failure={v.failure} cols={ctx.cols} />;
    if (v.s.view === "detail" && ctx.interactive)
      return <DetailView row={v.rows[v.s.selected]} scroll={v.s.detailScroll} cols={ctx.cols} termRows={ctx.termRows} />;
    // The banner reserves its row so the list windows against the height it gets and
    // the stacked heights still sum to the pinned frame (detail view hides the strip).
    const reserved = lockRows(v.lock);
    return (
      <Box flexDirection="column">
        {MergeLockBanner(v.lock, ctx)}
        <KanbanList
          rows={v.rows}
          selected={v.s.selected}
          interactive={ctx.interactive}
          cols={ctx.cols}
          termRows={ctx.termRows - reserved}
          all={ctx.all}
          loaded={v.loaded}
        />
      </Box>
    );
  },
});
