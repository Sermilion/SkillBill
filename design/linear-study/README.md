# Skill Bill: Linear study

An interactive HTML prototype of the Skill Bill desktop app redesigned in Linear's design language. It keeps the existing near-black and amber palette and shows only functionality that the desktop app (`desktop-tray`) or the runtime (`runtime-kotlin`) provides today, plus a config screen over existing runtime config keys.

[HANDOFF.md](HANDOFF.md) is the implementation handoff. Read it before planning work.

## Open it

Open `index.html` in a browser. No server or build step is needed; the scripts load by relative path, so `file://` works.

```sh
open design/linear-study/index.html        # macOS
xdg-open design/linear-study/index.html    # Linux
```

Inter and JetBrains Mono load from Google Fonts. Offline, the page falls back to system fonts and the layout stays intact.

The top bar switches between three views:

- **Prototype**: the app. Press `?` for shortcuts and `⌘K` / `Ctrl+K` for the command menu. Right-click a row for its actions.
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
| `app/main.js` | State, actions, keyboard model, command menu, menus, dialogs, toasts, terminal tabs, live clocks. |
| `index.html` | Page shell and documentation chrome styles. |

## Status

Design reference only. Nothing here ships. The Compose implementation lives in `desktop-tray`, and the config write path belongs in `runtime-kotlin`; both are described in the handoff.
