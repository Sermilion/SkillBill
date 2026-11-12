# Sona: Linear study

An interactive HTML prototype of the Sona desktop app redesigned in Linear's design language. It keeps the app's near-black and amber palette. The desktop app in `desktop/` defines shared styling and behavior. Added mock functionality stays in the prototype for later implementation.

Sona uses the Ping logo from [the logo study](../sona-logo-linear.html). The shared `BrandMark` draws its three clipped bands in one color at every size. Amber remains the interface accent for actions and status. Runtime commands, paths, repository names and recorded patches keep their existing identities.

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
| `app/flow.js` | Adapts the selected run or chat to Activity and Changes, and parses patches for inline comments. |
| `app/main.js` | State, actions, keyboard model, command menu, menus, dialogs, toasts, terminal tabs, live clocks. |
| `app/surfaces.js`, `app/activity.js`, `app/changes.js`, `app/comments.js` | Scoped Activity, Changes and Diff comments views, sample records and interactions. |
| `ds/surfaces.css`, `ds/activity.css`, `ds/changes.css`, `ds/comments.css` | Shared token aliases and styles for those views. |
| `index.html` | Page shell and documentation chrome styles. |

## Status

Design reference only. Nothing here ships. The Compose implementation built to this design lives in `desktop/`, and the prototype describes that app as built (see the revision note in the handoff). The current app in `desktop/` is the behavior reference. Design additions await implementation. Duplicate standalone mocks have been removed. The Activity, Changes window and Diff comments designs are now part of this prototype; their standalone copies have been removed.

## Activity, Changes and Diff comments

Open `index.html`, choose a goal on Work and browse its detail. Activity is embedded below Changes, with the selected goal's subtasks, recorded phases, blocks and recent updates. Expand a group or use the timeline to inspect its phases.

Click Open changes in the Changes overview. The window opens over that same goal. Choose a scope and file, then hover a diff line and click + to draft a comment. Pending comments stay in one review while you switch files in that scope. Add review publishes them together locally. Close changes or press Escape to return to the goal. Comments and scope choices survive closing and reopening the window.

The Work board includes "Changes window with subtask scopes", a 33-subtask example with recorded patches for trying the picker, ranges and comments. The IDE status goal also has an illustrative patch. Other runs and chats show their own sample file list. A file without a patch says "No text diff was recorded for this file." Chat changes and Verify's Open changes action use the same window with their own context.

States keeps the extra examples for comparing variants:

- Recorded activity has five historical and synthetic scenarios.
- Changes window retains the 33-subtask scope example.
- Diff comments has four scenarios covering writing a review, published threads, agent review and review fixes.

All data is part of this HTML prototype. Refreshing resets local interactions. No files, reviews or runs are changed.

`app/surfaces.js` owns the scoped view lifecycle and in-memory snapshots. Each view has a controller under `app/` and a stylesheet under `ds/`. Their local styles inherit the shared tokens through `ds/surfaces.css`; they do not affect other screens. All modules load from the same `index.html`, including with `file://`.

## Usage limits card

[USAGE-LIMITS.md](USAGE-LIMITS.md) records the original proposal, provider data sources and refresh policy.

The shared sidebar has a usage card above its bottom shortcuts. It is always 110px tall, so the sidebar never shifts. It shows the agent name in its avatar color and two window slots, shortest window first. Each slot reads like `Weekly · 4d 6h` with the used percentage on the right. The track and the percentage run green while most of the window is left, yellow around half and red as it runs out. With no windows, one short line fills the first slot. Only a provider that reports a sign-in failure says "Not signed in"; anything else without numbers says "No usage data".

Click the card for the details popup. It has provider tabs, every window with its reset as a countdown and a clock time (`Resets in 2h 14m · 16:30`), a Default agent row, and Refresh and Dashboard in the footer. There is no account, source or status text. Escape or Close returns focus to the card.

The card follows context. Opening a run shows its agent's usage, and New task follows the selected chat. A run or chat without a known agent keeps the last provider shown. The tabs always work: a tab picked in a run or chat holds until that context's agent changes, and on Work and History the pick is remembered.

Default agent sets the agent new chats, goals and phases start with. Clicking the active chip clears it. In this prototype a new chat in New task starts on the default.

The tabs cover Codex, Claude, Cursor and Junie. Windows come from the provider's live reading, never a fixed list. Codex and Claude usually report a 5-hour and a weekly window; an account that reports only a weekly window shows just that, with the second slot empty. A model-specific window names its model, as in Weekly (Opus). Cursor and Junie have no readable usage source, so they show "No usage data", and Junie has no Dashboard button.

Context examples use the existing run screens: [Codex](index.html#detail/INF-7), [Claude](index.html#detail/DSN-31), [Cursor](index.html#detail/ATL-402), and [Junie](index.html#detail/ph-rk-validation). [New task](index.html#new-task) follows its selected chat.

Review the card in the app layout with these links. Each opens the details popup. Close it to inspect the card alone.

- [Available](index.html#usage/normal)
- [Running low](index.html#usage/low)
- [Limit reached](index.html#usage/exhausted)
- [Weekly window only](index.html#usage/weekly)
- [Not signed in](index.html#usage/signedout)
- [No usage data](index.html#usage/nodata)
- [Loading](index.html#usage/loading)

The Components view also has UsageCard and UsagePanel examples.

## Verify detail

Verify keeps its outcome card brief. Full findings and the complete work summary live in the expandable Verdict step. The work overview shows at most three lines and links to that step.

Non-goals, Technical constraints and Rollout use purpose-specific context panels. Each collapsed panel shows a one-line preview with an exclusion count, requirement count or release label. Expanded panels separate entries with rules and preserve report formatting. Non-goals use a muted cross, constraints a shield, and rollout an arrow. The same hierarchy applies at narrow widths.

Criteria totals and row badges use the same per-criterion audit results. Missing grades display as ungraded, with the original report in Completeness audit. An empty audit never claims that all criteria are met.
