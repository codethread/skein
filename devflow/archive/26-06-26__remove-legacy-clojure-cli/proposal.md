# Remove legacy Clojure CLI

**Document ID:** `RCLC-PROP-001` **Last Updated:** 2026-06-26

## Problem

The repository still carries a legacy Clojure CLI entrypoint (`skein.cli`) and its tests, even though the public scripted CLI is now the Go `strand` binary and the Clojure runtime surface is already covered by the weaver, REPL, and libs layers. This creates dead code and dead-spec maintenance with no product value.

## Goal

Delete the legacy Clojure CLI implementation, its tests, and any remaining documentation/spec references that imply it is a supported surface.

## Non-goals

- Do not re-create the Go CLI in Clojure.
- Do not add replacement helpers unless they are needed by weaver, REPL, or libs tests.
- Do not preserve compatibility aliases or fallback entrypoints.
