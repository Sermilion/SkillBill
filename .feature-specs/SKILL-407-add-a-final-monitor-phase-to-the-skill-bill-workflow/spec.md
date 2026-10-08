# SKILL-407 - add-a-final-monitor-phase-to-the-skill-bill-workflow

## Mode

single_spec

## Intended Outcome

SKILL-407 Add a final "monitor" phase to the skill-bill workflow

Add a new phase to the skill-bill workflow. It is the last phase of the workflow, named "monitor".

Behavior:
- It watches the PR created by the workflow and makes sure its CI passes.
- If CI fails, it fixes the failure and monitors again.
- It repeats this up to 3 times.
- After 3 failed fix attempts, it blocks and reports why it can't fix the CI.

## Overview

SKILL-407 Add a final "monitor" phase to the skill-bill workflow

Add a new phase to the skill-bill workflow. It is the last phase of the workflow, named "monitor".

Behavior:
- It watches the PR created by the workflow and makes sure its CI passes.
- If CI fails, it fixes the failure and monitors again.
- It repeats this up to 3 times.
- After 3 failed fix attempts, it blocks and reports why it can't fix the CI.

## Acceptance Criteria

1. SKILL-407 Add a final "monitor" phase to the skill-bill workflow Add a new phase to the skill-bill workflow. It is the last phase of the workflow, named "monitor". Behavior: - It watches the PR created by the workflow and makes sure its CI passes. - If CI fails, it fixes the failure and monitors again. - It repeats this up to 3 times. - After 3 failed fix attempts, it blocks and reports why it can't fix the CI.

## Constraints

- Supplied requirements are authoritative and need no tracker lookup. Locally allocated issue keys do not require a tracker connection. Only an explicit unresolved tracker reference without requirements needs lookup through its connected tracker before planning. Use the returned requirements, not the URL title. If that lookup fails, block with the returned reason before implementation; never infer or substitute requirements.

## Non-Goals

- None

## Validation Strategy

Run the repository's required checks and verify every supplied acceptance criterion.
