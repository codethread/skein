# Stripped Task API

**Document ID:** `PROP-001` **Status:** Shipped **Created:** 2026-06-24 **Shipped:** 2026-06-24

## Problem

The current CLI/REPL surface exposes graph internals and narrow query helpers. The desired public surface is smaller: create tasks, update tasks, inspect/list tasks, and ask for ready work.

## Goals

- Reduce CLI commands to `init`, `add`, `update`, `show`, `list`, and `ready`.
- Reduce REPL helpers to the same small conceptual surface.
- Promote task lifecycle state from attributes into first-class fields: `status`, `created_at`, `updated_at`, and `final_at`.
- Keep userland fields under `attributes`.
- Ensure dev helpers and smoke workflows under `dev/` work after the change.

## Non-goals

- Define the future query DSL for `list`/`ready`; tracked by [RFC-002](../../rfcs/2026-06-24-task-query-dsl.md).
- Preserve backwards-compatible CLI commands during this alpha simplification.

## Outcome

Shipped. Root specs now describe the stripped API surface and first-class task lifecycle fields.
