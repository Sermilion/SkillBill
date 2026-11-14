# Sona: mobile study

An interactive HTML prototype of a Sona phone app in Linear's mobile patterns: a bottom tab bar, large titles, grouped rows, bottom sheets and swipe actions. The phone observes and controls work running on a computer. Goals, chats and agents never run on the phone.

It reuses the desktop study's tokens, icons, status shapes, avatars, repository marks and brand mark by loading `../linear-study/ds/`. Mobile adds a touch-size type ramp and its own components.

[HANDOFF.md](HANDOFF.md) records the decisions, the mapping to desktop features and the open questions.

## Open it

Open `index.html` in a browser. No server or build step is needed.

```sh
xdg-open design/mobile-study/index.html    # Linux
open design/mobile-study/index.html        # macOS
```

The top bar has three views:

- **Prototype** shows one phone with the side panel's jump links. Tap a composer to fill a sample draft. Pull to refresh is drawn but not simulated, so use the refresh button on a run.
- **Screens** shows every screen and state as a phone, grouped by flow: pairing, tabs, run detail, changes, chats, plans, settings, connection and push. Each phone works on its own.
- **Scope** lists which desktop features came over, what changed, and what stays desktop only.

## Files

| Path | Role |
| --- | --- |
| `ds/mobile.css` | Mobile sizes and one block of rules per component. Reads `../linear-study/ds/tokens.css`. |
| `ds/components.js` | Mobile components (`window.MB`). Icons and status shapes come from the desktop library (`window.SB`). |
| `app/data.js` | Sample board, chats, plans, machines and usage, shaped like the desktop data. Example values only. |
| `app/detail-data.js` | The rest of each run's desktop detail facts: location, run options, metrics, run reason, incident evidence and report, Execution, Runs and fixes, the run events and the activity ledger. Adds a goal waiting for an operator decision. |
| `app/kinds.js` | Validation, Verify, monitor, plan, standalone QA and standalone review runs, and their documents. |
| `app/run-chats.js` | Run chats: one per run, with the Verify proposal card and the operator decision card. |
| `app/peek.js` | Subtask and Goal finish sample data: inspector properties, phase states and one card per phase that ran. |
| `app/screens.js` | Screens composed from components. `render(st)` returns one phone's body and overlays. |
| `app/main.js` | Per-phone state, actions, the gallery list and the prototype jump links. |
| `index.html` | Page shell, documentation chrome and the Scope page. |

Screens use `MB` and `SB` components plus the desktop's layout and text utilities (`l-row`, `l-col`, `g-N`, `t-faint` and so on). A new visual shape gets a component in `ds/components.js` first.

## Status

Design proposal only. Nothing here ships. Per the desktop handoff's design sync rule, this stays a standalone mock until the operator confirms it.
