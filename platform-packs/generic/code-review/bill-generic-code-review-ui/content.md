---
name: bill-generic-code-review-ui
description: Technology-neutral UI review for state ownership, rendering identity, navigation, feedback, and interactions.
internal-for: skill-bill
---

# Generic UI Review

## Focus

Check state ownership, identity, navigation, loading and error states, optimistic updates, stale responses, destructive confirmation, focus restoration, and feedback after actions. Verify repeated rendering or event delivery cannot duplicate user-visible effects.

## Ignore

- UX-accessibility semantics and security concerns owned by their respective specialists.

## Applicability

Use when changes affect visual state, rendering identity, navigation, feedback, or interaction correctness.

## Project-Specific Rules

### UI Rules

- Require `screen-state-owner` to be singular across rendering and events; duplicated mutable state can race and display an invalid combination.
- Verify `render-item-identity` remains stable across insertions, deletion, and reordering; positional identity can corrupt local state and user actions.
- Reject `async-result-application` without request identity or cancellation because stale responses can overwrite newer data.
- Ensure `loading-error-empty-model` represents each user-visible state explicitly; collapsing them can hide failures or strand the interaction.
- Require `optimistic-update-rollback` for rejected mutations; absent rollback leaves incorrect state and false success feedback.
- Verify `navigation-back-stack` preserves required task context and cannot duplicate destinations; broken ordering can lose edits or trap users.
- Reject `destructive-action-trigger` without scoped confirmation or undo when the effect is hard to recover; accidental activation risks data loss.
- Ensure `submit-enabled-condition` matches validation and in-flight state; duplicate submission can race effects or create conflicting records.
- Require `focus-restoration-target` after dialogs, navigation, or errors; lost focus breaks keyboard interaction and task continuity.
- Verify `event-consumption-owner` prevents repeated rendering from replaying one-shot effects; duplicate alerts or navigation are visible correctness bugs.
- Reject `ui-resource-lifetime` ownership that outlives its screen without cleanup because observers and jobs can leak memory or mutate stale views.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
