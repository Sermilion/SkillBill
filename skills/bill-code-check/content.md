---
name: bill-code-check
description: Dominant-stack quality-check entry point. Use when running checks, lint, format, or quality validation.
---

# Quality Check Router

## Purpose

Route dominant-stack quality checks through the winning platform pack `validation_gate`. Standalone and orchestrated invocations share one repair-window contract on `bill-code-check`.

## Repair Window

Run the pack collect-all gate once and read that output. Fix every finding in the same session. Do not invoke the full gate, collect-all gate, `bill-code-check`, or any targeted compile, test, format, or analysis proof after each individual finding or between findings. When the set looks clean, run one cache-bypassing collect-all confirmation. If that fails, its output is the new complete finding set.

## Pack validation_gate

Select the dominant pack with manifest-driven routing. Collect-all is exactly that pack's `validation_gate.collect_all_full_gate_command`. Confirmation is exactly that pack's `validation_gate.cache_bypassing_collect_all_full_gate_command`. Do not read a pack quality-check sidecar, sibling `<name>.md`, or rediscover a different full-suite command.

When the dominant pack declares no `validation_gate`, stop with the typed missing-gate error from routing. Do not fall back to another pack, a sidecar, or a conventional task name.

## Routing

Auto-route to the dominant pack for the current unit of work. Telemetry `routed_skill` is always `bill-code-check`. Honor this shell's Repair Window and fix strategy in-session; stack-specific argv live only in the pack manifest gate.
