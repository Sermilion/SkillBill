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
