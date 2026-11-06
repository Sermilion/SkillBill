# SKILL-511 brief: several New Task chats at once, plus transcript replay on agent switch

Design reference: `design/new-task-chat-tabs/index.html` (open it in a browser; the top bar switches between Tabs, Side list and Settings, and the State menu covers idle, background reply, mixed repositories, 9 and 20 open chats, an empty chat, and closing a busy chat). The mock is sample data only.

## Today

- `NewTaskDraft` holds one chat (`chatState: MutableStateFlow<NewTaskChat?>`) plus the composer state for it: repository, base branch, agent, draft text and offer state.
- `NewTaskDraft.resume()` calls `close()` first, which ends the current agent session. Only one chat can be live.
- `NewTaskDraft.rebindChat()` runs when the agent, repository or base branch changes on an open chat. It ends the session, opens a new one, copies the transcript onto the screen and adds a "Switched to ..." notice. The new agent session never sees the earlier lines; its first turn carries only the operator's next message.

## Part 1: several open chats

Done means:

- Any number of New Task chats can be open at once. No cap.
- Each open chat has its own repository, base branch, agent, draft text, offer state and agent session.
- A chat's agent keeps streaming when another chat is selected. Selecting a chat never stops or restarts another chat's session.
- Two layouts, picked by a new setting:
  - **Tabs (default).** A tab strip under the New task header. Each tab shows the chat title and a status marker, with a close button on hover and on the selected tab. A "+" button opens a new chat.
  - **Side list.** A scrolling list of open chats on the left of the New task panel. Each row shows the title, then repository and status, with a close button on hover. A "+" button sits in the list header. A footer counts chats that are replying and chats with a new reply.
- Status markers, same in both layouts: a spinner while the agent is replying; an accent dot and bold title when a reply finished while that chat was not selected (cleared when it is selected); nothing when idle.
- Tab overflow: tabs shrink to a minimum width (about 112dp), then the strip scrolls horizontally. Vertical mouse wheel scrolls it horizontally. The clipped edge fades. A chevron button showing the open-chat count appears only when tabs overflow. It carries a dot when a chat out of view is replying or has a new reply. It opens a menu listing every open chat, with a filter field once more than 8 are open. Selecting a chat scrolls its tab into view.
- Shortcuts while New task has focus: Ctrl+T new chat, Ctrl+W close the selected chat, Ctrl+Tab and Ctrl+Shift+Tab cycle, Ctrl+1 to Ctrl+9 jump (Cmd on macOS where the app already maps Ctrl to Cmd).
- A new chat opens on the existing New task home (heading, Recent chats, Needs you or examples) with the composer below.
- Closing a chat keeps it saved in Recent chats and shows a toast with a Reopen action. Closing a chat whose agent is replying first asks "Close ...? &lt;agent&gt; is still replying. Closing stops the reply." with Keep open and Stop and close. Discard in the header still deletes the selected chat.
- Resuming from Recent chats or the /resume palette: if that chat is already open, select it; otherwise load it into the selected chat when that chat is empty, or open it as a new chat. Recent chats rows for chats that are already open show an "open" badge.
- The sidebar "New task" item shows the open-chat count when more than one is open.
- Open chats are not restored after an app restart. They come back only through Recent chats and /resume.
- Settings › General gets a "New task" group with one row: label "Open chats", description "Show the chats you have open as tabs above the chat, or as a list beside it.", and a Segmented control with Tabs and Side list. It uses the same pattern as the History retention row. The value lives in `AppSettings` (default Tabs), persists through `DataStoreAppSettingsRepository`, is covered in `FakeAppSettingsRepository`, and applies immediately without touching open chats.

## Part 2: transcript replay after an agent switch

Done means:

- When `rebindChat()` opens a new session because the agent, repository or base branch changed, the first turn sent on that new session carries the earlier conversation ahead of the operator's message. Example framing: "Earlier in this chat you were talking with claude. Here is that conversation: ... Continue from here." When the repository or branch changed, the preamble says so.
- The replay includes operator and agent messages only. Notices, state lines and errors are left out. Image attachments and screenshot paths stay as paths.
- Long transcripts are trimmed from the oldest end to a size limit, with a line saying older messages were left out.
- Replay happens once, on the first turn after the switch. Switching agents back and forth without sending costs nothing and does not stack preambles.
- The replay is not shown as a visible message in the transcript, and it is not saved as an operator line.
- The switch notice changes to say the new agent gets the earlier conversation with the next message, for example "Switched to codex in SkillBill. It gets the earlier conversation with your next message."
- A model change within the same agent keeps using `--resume` on the same session as today and does not replay.

## Out of scope

- Split panes or showing several chats side by side.
- Run-detail chats (`ChatComposer`) and their tabs.
- Restoring open chats after restart.
- Changes to the /resume palette beyond opening into a new or empty chat.
- An agent-written handoff summary.
