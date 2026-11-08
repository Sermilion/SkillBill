## [2026-11-08] SKILL-522 build indicator components
Areas: desktop/core/designsystem component and platform bridge
- MEDIUM feature. SbBuild presentation models, BuildChip and BuildStrip are reusable components with no runtime or domain dependency.
- Pure derivations advance elapsed snapshots, mark running builds slow only after usual time, cap the duration bar at 100 percent and retain known exit codes including zero.
- Components use theme tokens, the shared UI ticker, command tooltips, reduced-motion behavior and polite strip live-region semantics. A private build spinner preserves existing SbSpinner behavior.
- formatActiveDuration is public with unchanged output; system-zone HH:mm formatting stays behind the platform bridge.
- Limits: unknown exit codes omit the exit segment; finished builds without an end show no fabricated duration. Domain mapping and placement live outside the design system.
Feature flag: N/A
Acceptance criteria: 3/3 component subtask criteria implemented; covers parent criterion 4

## [2026-10-30] SKILL-447 desktop design system
Areas: desktop/core/designsystem, desktop/core/ui
- Added reusable dark theme tokens, fonts, icons, accessible status shapes, controls and layout composites. Components accept values and slots without runtime or domain dependencies.
- A shared window ticker serves live clocks; dialog body defaults require SbDialogText or an explicit SbDialogTextStyle. Desktop-only focus and cursor calls use an expect/actual bridge.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

