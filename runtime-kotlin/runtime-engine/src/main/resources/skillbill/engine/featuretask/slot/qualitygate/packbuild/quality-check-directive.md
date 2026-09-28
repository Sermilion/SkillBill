## Purpose

Route dominant-stack quality checks through the winning platform pack `validation_gate`. Standalone and orchestrated invocations share one repair-window contract on `skill-bill phase validation`.

## Repair Window

The runtime runs the pack gate once and hands you that output. Fix every finding in the same session. Do not invoke the full gate, collect-all gate, `skill-bill phase validation`, or any targeted compile, test, format, or analysis proof after each individual finding or between findings. When the set looks clean, stop: the runtime reruns the gate. If that fails, its output is the new complete finding set.

## Pack validation_gate

The runtime selects the dominant pack with manifest-driven routing and runs exactly that pack's `validation_gate` commands. Do not read a pack quality-check sidecar, sibling `<name>.md`, or rediscover a different full-suite command.

When the dominant pack declares no `validation_gate`, stop with the typed missing-gate error from routing. Do not fall back to another pack, a sidecar, or a conventional task name.
