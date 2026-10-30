## [2026-10-30] Supply dialog text styles explicitly
Context: Dialogs need muted body text while accepting composable content.
Decision: Provide SbDialogText and SbDialogTextStyle alongside the composable body slot.
Reason: Foundation BasicText does not inherit the dialog's provided text style.

## [2026-10-30] Keep desktop APIs behind the design-system platform bridge
Context: Common composites need a resize cursor and focus restoration after popup disposal.
Decision: Declare expect members in common code and implement them with AWT and Swing in jvmMain.
Reason: The shared components avoid JVM-only imports and another target can supply its own bridge.

