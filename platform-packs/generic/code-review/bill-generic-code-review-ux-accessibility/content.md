---
name: bill-generic-code-review-ux-accessibility
description: Technology-neutral UX and accessibility review for task completion, semantics, input, focus, and localization.
internal-for: skill-bill
---

# Generic UX and Accessibility Review

## Focus

Verify essential tasks work with keyboard or equivalent non-pointer input, meaningful names and roles, predictable focus, readable scaling, sufficient non-color cues, localized text, stable announcements, and recoverable errors.

## Ignore

- UI rendering correctness and security concerns owned by their respective specialists.

## Applicability

Use when changes affect task completion, semantics, keyboard input, focus, or localization.

## Project-Specific Rules

### UX and Accessibility Rules

- Require `accessible-name-source` for every interactive control; icon-only or placeholder-derived names create an identification failure for assistive users.
- Verify `keyboard-task-path` reaches, operates, and exits each control without pointer input; missing traversal is a task-completion failure.
- Reject `focus-order-map` that follows incidental render order when visual order differs because navigation becomes incorrect and confusing.
- Ensure `focus-error-target` moves only when it helps recovery and preserves the user's context; unconditional jumps risk a focus trap.
- Require `semantic-state-announcement` for expanded, selected, busy, invalid, and completed states; silent changes expose incorrect assistive context.
- Verify `validation-message-link` associates each failure with its field and summary; detached errors can prevent users from repairing invalid data.
- Reject `color-only-status-cue` because users with limited color perception may miss failures or destructive state.
- Ensure `text-scale-layout` reflows without clipping controls or hiding content at supported zoom; fixed dimensions create a layout regression and can exhaust screen resources.
- Require `localized-message-source` for visible and announced text; concatenated fragments can create invalid grammar and break translation.
- Verify `live-update-priority` avoids repeated or interrupting announcements; noisy status streams risk feedback starvation and operational failure.
- Reject `motion-without-alternative` for essential state changes because animation sensitivity or disabled motion can cause an invalid understanding of the resulting state.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
