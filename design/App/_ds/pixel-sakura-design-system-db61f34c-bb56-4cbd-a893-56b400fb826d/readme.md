# Pixel Sakura — Design System

A design system for **Pixel Sakura**, a server-rendered editorial news site covering Asian PC and console games (news, reviews, articles, imports, platform updates, topic stories). It has two surfaces: a **public reader-facing site** and an **authenticated admin CMS** for writing, previewing, organizing, and publishing posts.

The product is a Kotlin/Ktor app using kotlinx.html and light htmx — **not** a SPA. Designs assume normal full-page loads with small htmx fragments (pagination, preview, table-row updates, upload results). Accordingly, this system's primary deliverable is a set of **CSS classes** that map cleanly to server-rendered HTML, with thin React components on top for prototyping.

Tone target: a real gaming-news publication — fast to scan, dense for frequent readers, strong article hierarchy, good treatment of game key art, with a **subtle sakura identity** (not decorative overload, not childish/anime).

## Sources

- **Figma:** *Pixel Sakura Design System.fig* (attached) — the source of truth for tokens, type scale, and the button/chip/card/table/form specs. Pages: Cover, Foundations, Components, Public Page Examples, Admin CMS Examples.
- **GitHub:** `Sermilion/pixel-sakura` — the production Kotlin/Ktor codebase. It was **not accessible** to this build (private / SAML; the import App isn't installed on the `Sermilion` org). All values here come from the Figma file. Connect the repo (Install banner in chat) to let a future pass refine exact CSS values, taxonomy routes, and htmx fragment shapes against real code: https://github.com/Sermilion/pixel-sakura
- **Brief:** Pixel Sakura Design Brief (product spec for all pages and components).

> If you have access to the GitHub repo, explore it to build higher-fidelity designs — confirm the real palette, routes (`/news`, `/platform/{slug}`, `/topic/{slug}`), form names, and htmx targets before shipping production CSS.

---

## CONTENT FUNDAMENTALS

How Pixel Sakura writes. This is a **publication**, so copy reads like an editorial desk, not a marketing site or a blog template.

- **Voice:** third-person editorial / reportorial. The newsroom is "Pixel Sakura desk" or a named author (e.g. "Aoi Tanaka"). Avoid "we"/"you" in stories. Admin UI may address the editor directly and briefly ("Signed in as admin", "Auto-generated if left blank").
- **Headlines:** factual and specific, sentence case, no clickbait. e.g. *"New JRPG trailer confirms global PC release window"*, *"Switch import roundup: this week's notable Asian releases"*. They state what happened; they don't tease.
- **Excerpts/dek:** one or two sentences that give enough context to decide whether to open the article. Informative, not breathless.
- **Bylines & meta:** `By {Author} · {Month D, YYYY}` on articles; `{Author} · {Date}` compact on cards. Dates are written out on articles ("June 16, 2026"), relative or short in admin tables ("2h ago", "Yesterday", "Jun 15").
- **Casing:** sentence case for headlines, titles, and buttons ("Save post", "New post", "Insert image"). UPPERCASE only for tiny eyebrow labels (`.ps-eyebrow`) and platform chips (PS5, PC). Field labels are short, Title Case-ish but terse ("Body Markdown", "Hero image URL").
- **Buttons/actions:** verb-first and concrete — "Save post", "Create post", "Publish", "Unpublish", "Delete post", "Create author", "Add tag", "Save nav", "Save order". No "Submit".
- **Taxonomy language:** post **type** (News / Review / Article), **platform** (PS5 / Switch / PC / Xbox), **topic** (JRPG / visual novel / fighting games / trailers). Platform pages use full names as headings ("PlayStation 5", "Nintendo Switch") while chips use short forms.
- **Empty / error states:** calm and helpful, never cute-to-a-fault. *"No published stories yet — when the desk publishes its first story, it will appear here."* / *"This tag is in use by 12 posts and can't be deleted."* / 404: *"The story you're looking for may have moved or never existed."*
- **Emoji:** none. The brand identity is the sakura mark and the red/gold accents, not emoji.
- **Numbers/stats:** only when they carry meaning (story counts on taxonomy pages, sort order, "12 posts"). No decorative metrics.

---

## VISUAL FOUNDATIONS

The look: **"Night Sakura" — a warm near-black canvas, neon sakura-pink that glows, gold as a spark, and a sharp Space Grotesk display face.** Modern gaming-editorial: immersive key art, glow-on-hover, blurred sticky chrome, confident type. Dark-first by design (gaming media lives in the dark); restraint keeps it editorial rather than gamer-RGB.

- **Color & vibe:** the canvas is a warm purple-charcoal near-black (`#0D0B11`) with a faint fixed radial glow (sakura top-right, gold top-left). Surfaces step up `#17141C` → `#1F1B27` → `#2A2434`. Text is near-white `#F5F1F8`, muted `#A79FB4`. The brand red is **neon sakura `#FF4D8D`** (primary actions, accents), hover `#FF6FA3`, link/on-dark `#FF9CC0`, with translucent pink tints for fills and chips. **Gold `#F5B544`** is the secondary spark. Danger is bright `#FF6B6B`; success a poppy **mint `#2EE6A6`**. Bright accents carry **dark ink text** (`#1A0B12`) for a punchy, modern "highlighter" button.
- **Glow:** the signature move. Primary buttons, focus rings, active nav, published badges, and card hovers emit a soft sakura glow (`0 0 24px rgba(255,77,141,0.35)`). Deliberate, not everywhere.
- **Imagery:** game key art sits on a deep base (`#08070A`) with a neon-sakura radial glow + gold orb baked into the placeholder art, plus a dark bottom gradient (`--media-tint`) so overlaid text stays legible. Lead/feature cards put the headline **over** the art (magazine style); grid cards zoom the art 1.04× on hover. 16:9 cards, 2:1 article heroes, 14px radius.
- **Backgrounds:** flat near-black + the one ambient radial glow. **No** blobs, no glass panels (except blurred header/topbar chrome), no generic-tech gradients.
- **Type:** **Space Grotesk** (display — brand, headlines, card/section/page titles, big numbers; 500/700, tracking `-0.02`→`-0.03em`) + **Inter** (body, UI, dense reading; 400–800) + **JetBrains Mono** (code, slugs, eyebrow labels, metadata ticks). Hero title 28–44px clamp, article 42px, page 32px. Body 17/28 at a ~740px measure.
- **Spacing & layout:** 4px grid; 32px gutter (16px mobile). Container `1240px`, reading column `740px`. The home feed is **magazine-structured**: an immersive lead with overlaid text beside a numbered "Latest drops" ticker rail, then an accent-ruled section of grid cards. Sticky **blurred** public header (68px) and dark blurred admin topbar (58px).
- **Corner radii:** crisp `8px` controls/chips, soft `14px` cards/media, `20px` modals/auth. Chips and badges are **pills** (`999px`).
- **Cards:** dark surface, 1px hairline border (`#292233`), 14px radius, no shadow at rest. Hover: border tints sakura, `shadow-md` + sakura glow, lift 4px, art zooms. Text-only cards get a sakura accent rail + faint pink wash. **No cards-inside-cards.**
- **Borders & shadows:** hairline borders define structure; shadows are deep black (`0 10px 30px rgba(0,0,0,0.5)`) paired with glow on lift. Modals: `shadow-lg` over a blurred dark scrim (`rgba(5,4,7,0.72)`).
- **Focus & states:** keyboard focus is a **2px neon-sakura ring** + offset. Inputs focus to a sakura border + soft pink glow and lighten their fill. Hover = step-up surface or brighter shade; primary darkens to `#ED3A7B` on press with a 1px nudge. Disabled = 45% opacity.
- **Animation:** quiet, springy easing (`cubic-bezier(0.16,1,0.3,1)`), 140ms hover / 260ms lift. Glow fades in on hover. No bounces or infinite loops; respect `prefers-reduced-motion`.
- **Chips & badges:** pill, 30px. Type = sakura tint fill + pink text; Platform = gold hairline + gold text; Topic = neutral hairline; Neutral = muted. Published badge dot glows sakura; Draft neutral.

---

## ICONOGRAPHY

- **System:** the Figma file ships **no icon assets**, so the system standardizes on **Lucide** (MIT) — a clean, 24px-grid, 2px-stroke line set that suits the editorial/utilitarian tone. This is a **flagged substitution**; swap for the codebase's real icons if the repo defines a set.
- **Delivery:** icons are **inline SVG**, not an icon font or CDN — ideal for server-rendered HTML. The `Icon` component (`components/core/Icon.jsx`) holds the path data; add glyphs by pasting a Lucide path into its `PATHS` map. Icons inherit `currentColor` and default to 20px.
- **Usage:** sparingly — nav toggle, button affixes (plus/upload/image/check), table-row actions (pencil/trash), notices (alert/check-circle), admin nav (layers/tag/user/file-text), and chevrons for pagination/back links. Icons support text; they rarely stand alone except in `IconButton` (which always takes an accessible `label`).
- **Brand mark:** a custom 5-petal **sakura mark** (`assets/sakura-mark.svg`, also inlined in the `Brand`/`SakuraMark` components) in deep sakura with a gold center. ⚠️ This is a **placeholder identity** drawn from the brand colors — there is no official logo in the provided sources. Replace with a final logo when available.
- **Emoji / unicode:** not used as iconography anywhere.

---

## Index / manifest

**Global CSS** (consumers link `styles.css`, which `@import`s everything below):
- `tokens/colors.css` — base palette + semantic aliases (chips, badges, focus)
- `tokens/typography.css` — Inter scale, weights, roles
- `tokens/spacing.css` — 4px grid, radii, shadows, layout, motion
- `tokens/fonts.css` — Inter + JetBrains Mono (Google Fonts)
- `css/base.css` · `brand.css` · `buttons.css` · `chips.css` · `forms.css` · `cards.css` · `tables.css` · `article.css` · `layout.css` — the server-render-ready class layer

**Components** (`components/<group>/` — React wrappers over the CSS classes; each has `.jsx` + `.d.ts` + `.prompt.md`):
- `core/` — `Button`, `IconButton`, `Chip`, `Badge`, `Icon`
- `forms/` — `Field`, `TextInput`, `Textarea`, `Select`, `CheckboxGroup`
- `content/` — `Brand` (+`SakuraMark`), `PostCard`, `Pagination`, `ArticleBody`
- `data/` — `DataTable`
- `feedback/` — `Notice`

**UI kits** (`ui_kits/<product>/` — interactive full-screen recreations):
- `public-site/` — home feed, taxonomy listing, article page, empty + 404 states
- `admin-cms/` — login, dashboard, manage posts, **post editor (live preview)**, taxonomy, authors

**Guidelines** (`guidelines/*.card.html`) — foundation specimen cards for the Design System tab (colors, type, spacing, brand).

**Assets** (`assets/`) — `sakura-mark.svg` (brand mark, placeholder).

**Other:** `SKILL.md` (portable Agent Skill), this `readme.md`. The compiler generates `_ds_bundle.js`, `_ds_manifest.json`, `_adherence.oxlintrc.json` — do not edit.
