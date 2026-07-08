// KANBAN tab: the user↔agent work board as strands (spools/kanban.md). Polls
// `strand list --query kanban-cards` and renders lane/type/owner columns.
// Self-contained: the row type, fetcher, colour maps, and list component all
// live here so the tab is edited in exactly one place.

import { Box, Text } from "ink";
import { parseInstant, str, strandJson, type DetailRow, type StrandRecord } from "../data";
import { age, clip, fitCol, ListFooter, oneLine, pad, TableRow, windowRows, type Cell, type ListProps } from "../ui";
import { listDetailTab, type RenderCtx } from "../app";

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

// Board lane order follows the card lifecycle: claimed work, then the cards under
// review on their way out (claimed -> in_review -> closed), then the actionable
// queue, then ideas still in refinement. Closed strands sink regardless of their
// kanban/status — migrated cards can carry a stale lane attr after close — and
// show their outcome (done/abandoned/...) dimmed.
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
// registered query — the board payload omits the lock — so we scan it for the one
// active lock.
type MergeLock = { id: string; owner: string; feature: string; acquiredAt: string };

async function fetchMergeLock(): Promise<MergeLock | null> {
  const items = (await strandJson(["list", "--state", "active"])) as StrandRecord[];
  const lock = items.find((s) => str(s.attributes["kind"]) === "merge-lock");
  if (!lock) return null;
  return {
    id: lock.id,
    owner: str(lock.attributes["owner"], "-"),
    feature: str(lock.attributes["land/run-id"], "-"),
    acquiredAt: lock.created_at,
  };
}

// One clipped line so a coordinator sees a merge in flight at a glance: the feature
// landing, the land run that owns the lock, and how long it has been held. Null
// when no lock is active, so the tab reserves no row for it (see listDetailTab).
function MergeLockBanner(lock: MergeLock | null, ctx: RenderCtx) {
  if (!lock) return null;
  const held = age(parseInstant(lock.acquiredAt), new Date());
  const text = `MERGE LOCK · ${lock.feature} · owner ${lock.owner} · held ${held}`;
  return (
    <Text bold color="magenta">
      {clip(text, ctx.cols)}
    </Text>
  );
}

export const kanbanTab = listDetailTab<KanbanRow, MergeLock | null>({
  id: "kanban",
  label: "KANBAN",
  fetch: fetchKanban,
  List: KanbanList,
  banner: { fetch: fetchMergeLock, rows: () => 1, render: MergeLockBanner },
});
