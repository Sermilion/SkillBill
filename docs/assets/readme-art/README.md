# README art

`index.html` is the source for the README hero and the three desktop app images in `docs/assets/`. It renders the app in the Sona design system with sample data. The images are not screenshots of a real session.

| View | Output |
|------|--------|
| `?view=hero` | `sona-readme-hero.png` (1280×720) |
| `?view=goal` | `desktop-goal-run.png` (1440×900) |
| `?view=work` | `desktop-work-list.png` (1440×900) |
| `?view=details` | `desktop-goal-activity.png` (1440×900) |

Open `index.html?view=goal` in a browser to preview a view. Add `&scale=2` to see it at 2×.

## Re-render

Requires Firefox and ImageMagick 7 (`magick`). Fonts load from Google Fonts, so rendering needs network access.

```bash
docs/assets/readme-art/render.sh docs/assets/readme-art/index.html docs/assets
```

The script renders each view at 2× and rounds the corners with a transparent mask so the images sit on GitHub's light and dark themes.
