---
name: bill-kmp-code-check
description: Test skill.
internal-for: skill-bill
---

# bill-kmp-code-check

## Purpose

Check kmp changes.

## Execution Steps

Determine the files in scope. Discover commands from build files, wrappers,
  and CI configuration before falling back to defaults.
Run the pack's quality-check entrypoint and capture failures.

## Fix Strategy

Follow the priority-ordered fix ladder and never suppress failures.

### Repair Window

Collect one complete finding set before repairing anything. While that set is open, do not invoke any check, test,
  compile, format-task, quality-check command, pack checker, `bill-code-check`,
    or delegated subagent check. Allowed work is read, search, and source edits only.
Escalate to the full suite when targeted checks cannot establish safety.
