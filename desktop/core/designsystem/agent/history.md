## [2026-11-07] SKILL-516 unify desktop UI components
Areas: desktop/core/designsystem, desktop/core/ui, desktop/app, desktop/feature/changes, desktop/feature/chat, desktop/feature/detail, desktop/feature/settings, desktop/feature/specs
- MEDIUM feature consolidates 16 UI families in the design system and removes the scoped feature, core/ui and app drawing copies. No color or type tokens were added.
- Published Edge and hairline; added SbDivider, StatusDot, SbTag, SbPickerButton, GroupLabel, SegmentBar, SkeletonBlock, ContentPanel, sbRowSurface and sbOverlaySurface. These plain-parameter and slot APIs are reusable.
- Extended SbLabel with outer modifiers, width caps and Small sizing; Segmented with lead and count slots; ViewTabs with an outer modifier; SbIconButton with XSmall; SbInput with independent readOnly support. Existing input and tab overloads remain.
- MetricStrip now renders plan stages, activity statistics and QA cells with 12dp padding and 3dp gaps. MetricCell adds lead, tone and rich content while retaining valueContent as its trailing lambda. reusable
- SbBox and sbBoxSegment share nullable fill and configurable borders across boxes and filled cards. SbBox adds a header band; segments own inset fills and draw borders over child content. SubtaskChip shares SbTag's internal compact frame. reusable
- Callers retain domain mappings, state, loading announcements, focus, keyboard handling and decorations. Shared controls supply their standard roles; row and overlay surface modifiers add drawing without activation or dismissal ownership.
- Compact defaults use 6dp status dots, 18dp tags, 5dp progress tracks, 22dp Segmented and 24dp ViewTabs and pickers. Labels keep Regular at 22dp and add Small at 18dp; XSmall is 16dp for icon buttons and uses Small rendering for text buttons and labels.
- Accepted shifts include shared card radii and borderSubtle frames, a solid pending-comment frame, Tint without a dot in the chat header, alpha-only PendingReviewBar pulses and layout-direction-aware End rules. Caller modifiers precede divider sizing and panel framing.
- ContentPanel serves the shell, runtime refusal and Settings. ShellFrame uses movable content across framed and drawer branches to retain remembered work-area state.
- Limits: collapsed tool groups retain the header rule beside the bottom frame; comment inputs adopt the 120dp internal-scroll cap. Unread overflow badges, target cards, timeline bars, unframed search rows, window chrome and desktop-tray remain separate families; preventing future copies with lint remains a follow-up.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-10-30] SKILL-447 desktop design system
Areas: desktop/core/designsystem, desktop/core/ui
- Added reusable dark theme tokens, fonts, icons, accessible status shapes, controls and layout composites. Components accept values and slots without runtime or domain dependencies.
- A shared window ticker serves live clocks; dialog body defaults require SbDialogText or an explicit SbDialogTextStyle. Desktop-only focus and cursor calls use an expect/actual bridge.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

