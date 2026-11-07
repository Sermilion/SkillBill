# Skill Bill: Linear study

An interactive HTML prototype of the Skill Bill desktop app redesigned in Linear's design language. It keeps the app's near-black and amber palette. The desktop app in `desktop/` defines shared styling and behavior. Added mock functionality stays in the prototype for later implementation.

[HANDOFF.md](HANDOFF.md) is the implementation handoff. Read it before planning work.

## Open it

Open `index.html` in a browser. No server or build step is needed; the scripts load by relative path, so `file://` works.

```sh
open design/linear-study/index.html        # macOS
xdg-open design/linear-study/index.html    # Linux
```

Inter and JetBrains Mono load from Google Fonts. Offline, the page falls back to system fonts and the layout stays intact.

The top bar switches between four views:

- **Prototype**: the app. Press `?` for shortcuts and `⌘K` / `Ctrl+K` for the command menu. Right-click a row for its actions.
- **States**: a linked catalog of work lifecycles, result variants, chats, devices and specs. Open `index.html#states`. [STATE-COVERAGE.md](STATE-COVERAGE.md) records the source inventory and checks.
- **Components**: every component with live examples. Each card's "Used in" list is computed at load from the screens that call it.
- **Foundations**: principles, the source-of-truth table, tokens, status vocabulary, type, spacing, radius, elevation and icons.

## Files

| Path | Role |
| --- | --- |
| `ds/tokens.css` | Design tokens: color, type scale, spacing, radius, elevation, motion, sizes. |
| `ds/components.css` | One block of rules per component. Screens add no styling. |
| `ds/components.js` | The component library: each component is a function returning HTML and recording where it was used. |
| `app/data.js` | Sample data shaped like the desktop domain model and runtime config models. Example values only. |
| `app/screens.js` | Screens composed from components and layout primitives only. |
| `app/library.js` | The Components and Foundations pages, the tray popover and OS notification surfaces. |
| `app/states.js` | State examples and the States catalog. Examples use the same detail renderers and stay out of the everyday board sample. |
| `app/main.js` | State, actions, keyboard model, command menu, menus, dialogs, toasts, terminal tabs, live clocks. |
| `index.html` | Page shell and documentation chrome styles. |

## Status

Design reference only. Nothing here ships. The Compose implementation built to this design lives in `desktop/`, and the prototype describes that app as built (see the revision note in the handoff). The current app in `desktop/` is the behavior reference. Design additions await implementation. Standalone mocks stay until the operator confirms their synced versions.

## Usage limits card

[USAGE-LIMITS.md](USAGE-LIMITS.md) records the implementation proposal, provider data sources, refresh policy and unresolved API coverage.

The shared sidebar includes a usage card above its bottom shortcuts. It uses the existing palette, type, spacing and sidebar width. The navigation scrolls separately so the card stays visible. Click the card for provider selection and reset times. Escape or Close returns focus to the card.

This design assumes provider subscription allowances. Account labels, percentages, window names and reset times are sample data. Refresh simulates a fresh reading. The design does not add automatic pausing or provider switching for runs. Reading live provider usage remains implementation work.

The provider picker includes Codex, Claude and Cursor. The Cursor example uses a legacy plan with separate Cursor models and Other models pools and a billing-cycle reset. Cursor's allowance structure depends on the account's plan, so live data must supply the pool labels and reset dates. See [Cursor usage and limits](https://cursor.com/help/models-and-usage/usage-limits).

The card follows context. Opening a run shows the provider recorded on that run, including completed runs. New task follows the selected chat's agent and updates when you switch chats or agents. A small "This run" or "This chat" label explains the selection. The details popup follows the same provider; its other provider buttons are disabled while context controls the card. Work and History keep the manual provider picker and remember its last selection. Junie has no usage card, and runs without a known provider also hide it. Junie remains available elsewhere in the prototype.

Context examples use the existing run screens: [Codex](index.html#detail/INF-7), [Claude](index.html#detail/DSN-31), [Cursor](index.html#detail/ATL-402), and [Junie without a usage card](index.html#detail/ph-rk-validation). [New task](index.html#new-task) follows its selected chat.

Review the card in the existing app layout with these links. Each opens the details popup. Close it to inspect the card alone.

- [Available](index.html#usage/normal)
- [Running low](index.html#usage/low)
- [Limit reached](index.html#usage/exhausted)
- [Out of date](index.html#usage/stale)
- [Unavailable](index.html#usage/unavailable)
- [Checking](index.html#usage/loading)

The existing Components view also includes UsageCard and UsagePanel examples.
