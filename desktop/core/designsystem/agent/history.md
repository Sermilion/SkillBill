## [2026-11-08] SKILL-522 build indicator components
Areas: desktop/core/designsystem component and platform bridge
- MEDIUM feature. SbBuild presentation models, BuildChip and BuildStrip are reusable components with no runtime or domain dependency.
- Pure derivations advance elapsed snapshots, mark running builds slow only after usual time, cap the duration bar at 100 percent and retain known exit codes including zero.
- Components use theme tokens, the shared UI ticker, command tooltips, reduced-motion behavior and polite strip live-region semantics. A private build spinner preserves existing SbSpinner behavior.
- formatActiveDuration is public with unchanged output; system-zone HH:mm formatting stays behind the platform bridge.
- Limits: unknown exit codes omit the exit segment; finished builds without an end show no fabricated duration. Domain mapping and placement live outside the design system.
Feature flag: N/A
Acceptance criteria: 3/3 component subtask criteria implemented; covers parent criterion 4

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
- QA coverage ends at the tree before validation changed code. The published change differs from that QA-tested tree, and QA does not rerun.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-10-30] SKILL-447 desktop design system
Areas: desktop/core/designsystem, desktop/core/ui
- Added reusable dark theme tokens, fonts, icons, accessible status shapes, controls and layout composites. Components accept values and slots without runtime or domain dependencies.
- A shared window ticker serves live clocks; dialog body defaults require SbDialogText or an explicit SbDialogTextStyle. Desktop-only focus and cursor calls use an expect/actual bridge.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

