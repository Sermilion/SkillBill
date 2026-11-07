# Brief: make New task match the combined tabs and home design

Design reference: `design/new-task-tabs-home/index.html`. Open it in a browser. The review bar switches between Tabs and Side list, and the State menu covers a new empty chat, idle and busy chats, mixed repositories, 9 and 20 open chats, closing a busy chat, and a home with few items. The mock is sample data only.

It combines `design/new-task-chat-tabs/index.html` (tab strip, side list, transcript, composer) with variant A of `design/new-task-home/index.html` (home with separate Recent chats and Needs you sections).

## Today

- `NewTaskChatTabs.kt` draws each tab as a pill rounded on all corners with a border on every side, and gives the tab area `weight(1f)`, so the "+" button sits at the far right of the strip.
- The home shows one merged "Pick up where you left off" list with All, Needs you and Chats filters (`NewTaskInboxSection.kt`, from SKILL-512). The design picked variant A, two separate sections.

## Done means

### Tab strip

- Tabs are rounded on the top corners only (`--r-sm`), have no bottom border, and the selected tab uses the panel background and overlaps the strip's bottom hairline by 1dp, so it joins the content below.
- The strip has 4dp padding on top only. Tabs fill the rest of its 36dp height.
- Tab padding is 10dp start, 4dp end. Unselected title color is fg-muted, hover background is bg-hover. The close button is 20dp and shows on hover and on the selected tab.
- The tab row takes only the width its tabs need. "+" sits right after the last tab. When tabs overflow, the row shrinks to the space left, tabs shrink to 112dp minimum, then the row scrolls, and "+" stays right after the visible edge of the row.
- The overflow chevron with the open-chat count is the only control pushed to the far right, and it shows only when tabs overflow.
- Everything else about tabs from SKILL-511 stays: markers, shortcuts, wheel scrolling, edge fades, overflow menu with filter.

### Home for an empty chat

- Order: hero, step line, Recent chats, Needs you. Same spacing and type as the mock (content max width 720dp, 32dp between blocks).
- Recent chats: header "Recent chats" with the total count, and a "Search chats /resume" ghost button on the right that opens the resume palette. Below it, one card with every saved chat for the chat's repository.
- Needs you: header "Needs you" with the count, or "All clear" when nothing is blocked. The right side keeps the "N running · N finished today" link. Below it, one card with every blocked run. No card when nothing is blocked.
- Each card shows at most 5 rows (34dp each) and scrolls past that. The edge with more rows behind it fades, the same way the tab strip fades. With 5 or fewer rows the card fits its content with no fade.
- Rows keep the SKILL-512 row look: glyph, title, faint suffix, badge, time column, discard on hover for chats, orange edge and tint for blocked runs.
- A saved chat that is open in a tab shows an "open" badge and no discard button. Clicking it selects its tab. Clicking any other chat loads it into the selected empty chat.
- Up, Down, Enter and Delete work inside a focused card, and the selected row scrolls into view.
- The All, Needs you and Chats filter control and the merged list are removed.
- First-run examples stay as they are today.

### Composer on the home

- Review my branch, Run the checks, QA my branch and Open a PR chips sit above the input, as today.

## Out of scope

- Side list layout changes beyond what the mock already matches.
- Settings, the resume palette, run-detail chats.
- Transcript replay and other SKILL-511 behavior.
