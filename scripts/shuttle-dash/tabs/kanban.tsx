// KANBAN tab: the user↔agent work board as strands (spools/kanban.md). Polls
// `strand list --query kanban-cards` and renders lane/type/owner columns.
// Self-contained: the row type, fetcher, colour maps, and list component all
// live here so the tab is edited in exactly one place.

import { Box, Text } from "ink";
import { str, strandJson, type DetailRow, type StrandRecord } from "../data";
import { clip, fitCol, ListFooter, oneLine, pad, TableRow, windowRows, type Cell, type ListProps } from "../ui";
import { listDetailTab } from "../app";

export type KanbanRow = DetailRow & {
  lane: string;
  type: string;
  owner: string;
};

const LANE_COLOR: Record<string, string | undefined> = { claimed: "green", pending: "yellow", refinement: "cyan" };

// Board lane order: claimed work first, then the actionable queue, then ideas
// still in refinement. Closed strands sink regardless of their kanban/status —
// migrated cards can carry a stale lane attr after close — and show their
// outcome (done/abandoned/...) dimmed.
const LANE_RANK: Record<string, number> = { claimed: 0, pending: 1, refinement: 2 };
const laneRank = (r: KanbanRow): number => (r.state === "closed" ? 3 : (LANE_RANK[r.lane] ?? 3));

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
      createdAt: s.created_at,
      updatedAt: s.updated_at,
      attrs,
    };
  });
  // created_at is "YYYY-MM-DD HH:MM:SS" (UTC), so lexical order is chronological.
  // Active lanes are queues and list oldest-first to agree with `kanban next`
  // (spools/kanban.md); the closed bucket lists newest-first so fresh outcomes
  // stay in reach.
  return rows.sort((a, b) => {
    const rank = laneRank(a) - laneRank(b);
    if (rank !== 0) return rank;
    return a.state === "closed" ? b.createdAt.localeCompare(a.createdAt) : a.createdAt.localeCompare(b.createdAt);
  });
}

function KanbanList({ rows, selected, interactive, cols, termRows, all, loaded }: ListProps<KanbanRow>) {
  if (rows.length === 0) {
    return <Text dimColor>{clip(loaded ? `no ${all ? "" : "active "}cards` : "loading board…", cols)}</Text>;
  }
  const w = {
    id: fitCol("ID", rows.map((r) => r.id), 12),
    lane: fitCol("LANE", rows.map((r) => r.lane), 12),
    type: fitCol("TYPE", rows.map((r) => r.type), 8),
    owner: fitCol("OWNER", rows.map((r) => r.owner), 14),
    branch: fitCol("BRANCH", rows.map((r) => r.branch), 24),
  };
  const titleWidth = Math.max(0, cols - 10 - w.id - w.lane - w.type - w.owner - w.branch);
  const { start, visible, below } = windowRows(rows, selected, interactive, termRows);

  return (
    <Box flexDirection="column">
      <TableRow
        width={cols}
        bold
        cells={[
          { text: pad("ID", w.id) }, { text: "  " },
          { text: pad("LANE", w.lane) }, { text: "  " },
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
        <ListFooter hint="↑↓/jk move · ⏎ attrs · a all/active · r refresh · ⇥ tab · q quit" cols={cols} start={start} below={below} total={rows.length} />
      )}
    </Box>
  );
}

export const kanbanTab = listDetailTab<KanbanRow>({ id: "kanban", label: "KANBAN", fetch: fetchKanban, List: KanbanList });
