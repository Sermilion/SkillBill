## [2026-11-08] Keep the build indicator spinner private
Context: The build design needs a borderStrong track, a top-border arc, its own stroke rules and a 900 millisecond turn.
Decision: BuildChip and BuildStrip share a private BuildSpinner with their own arc, track and reduced-motion rules.
Reason: SbSpinner couples track and arc color and uses different stroke and start-angle rules. A private spinner matches the build design while preserving existing callers, including the plan-stage extension.

## [2026-11-07] Share presentation without transferring feature behavior
Context: SKILL-516 merges repeated drawing across desktop modules whose domain mappings and interaction lifetimes differ.
Decision: Accept plain values and slots in shared components. Row and overlay surfaces own drawing; callers keep activation, focus, keyboard handling, dismissal and feature decorations. Row callers supply the shared MutableInteractionSource and choose the shape, using RectangleShape for normal ListRow, inbox and file rows. Status and bar callers map domain states; skeleton callers own loading semantics and animation.
Reason: A drawing API can serve passive validation rows as well as clickable rows without making passive content actionable. Sharing the interaction source keeps hover observation with the caller's interactions; explicit rectangular shapes prevent default rounding from clipping full-width rows. Moving feature state into the design system would introduce domain dependencies and duplicate existing behavior owners.

## [2026-11-07] Use compact density without shrinking existing controls
Context: The spec chooses the compact implementation when duplicates differ only in density, and otherwise chooses design-system rendering.
Decision: Use 6dp dots, 18dp tags, 5dp bars and 12dp metric padding with 3dp gaps. Add Small labels while keeping Regular unchanged. XSmall gives icon buttons a 16dp frame; text buttons and labels map it to Small.
Reason: Compact defaults remove density-only copies. Explicit size options retain existing label dimensions and avoid introducing an unrequested extra-small text-control appearance.

## [2026-11-07] Let box segments own fill and frame
Context: Feature boxes repeated frames, while review and audit groups painted outer backgrounds around already-framed segments.
Decision: Apply optional fill inside sbBoxSegment's inset clip and draw its configurable border after child content. Keep review and audit grouping Columns unpainted around the segments.
Reason: The shared modifier keeps fill inside segment corners and prevents child backgrounds from covering the border. Retaining plain Columns keeps parent spacing from inserting gaps between contiguous segments.

## [2026-11-07] Let the box header own the first tool separator
Context: SbBox's header draws a bottom hairline where the first ChatToolRow previously drew its top rule.
Decision: Disable the first tool row's top rule inside a group and retain subsequent rules. Standalone ChatToolRow keeps its top rule by default.
Reason: Drawing both separators would produce a 2dp line above the first tool. The collapsed group's header rule still sits beside its bottom frame.

## [2026-11-07] Keep MetricCell valueContent last
Context: Existing metrics pass live clocks and custom values through trailing lambdas, while the shared cell now also accepts rich content.
Decision: Place lead, tone and content before valueContent and leave valueContent as the final constructor parameter.
Reason: A new final composable parameter would silently capture existing trailing lambdas and replace the whole value row instead of its value.

## [2026-11-07] Preserve the original SbInput overload
Context: The input spec places readOnly after enabled, ahead of existing onSubmit, multiline and visualTransformation arguments.
Decision: Keep the original signature as a forwarding overload with readOnly false and render through the read-only-capable overload.
Reason: Existing positional, named and trailing-lambda calls must retain their meaning while callers needing read-only behavior can name readOnly.

## [2026-11-07] Keep read-only inputs enabled
Context: A saving comment must reject edits while retaining enabled appearance and focusability. Changes already owns focus lifecycle and submission shortcuts.
Decision: Forward readOnly independently of enabled. Carry focus, semantics, key handlers and minimum height through caller modifiers; comment fields use multiline with onSubmit null.
Reason: Disabling the input would change focus and semantics. Caller modifiers preserve the existing extension mechanism, and a null submit callback lets plain Enter insert a newline while the caller handles Ctrl/Cmd+Enter.

## [2026-11-07] Distinguish static pickers from disabled pickers
Context: The New Task repository pill becomes static after starting, while other picker states remain interactive controls that can be disabled.
Decision: A null SbPickerButton onClick removes click handling, button role and chevron. A disabled interactive picker keeps disabled semantics. Accept an optional chevronDescription in both overloads.
Reason: The shared control must retain the repository pill's static presentation and spoken chevron description without treating every unavailable action as a static label.

## [2026-11-07] Own the shared content frame in the design system
Context: The shell, runtime refusal window and Settings share a panel frame, and Settings cannot depend on app.
Decision: Put ContentPanel in design-system navigation. Apply caller size and outer padding before its frame and a 1dp content inset after its border.
Reason: A design-system owner avoids an app dependency from Settings. The inner inset keeps content clear of Compose's border, which draws over content.

## [2026-11-07] Filter nonpositive segment weights before spacing
Context: Zero and negative progress segments must consume neither width nor gaps, while an empty visible list must retain its described track.
Decision: Filter weights before emitting spaced children. Keep the track background and content description on the container.
Reason: Skipped entries would otherwise reserve gaps or remove the track in zero-total states.
## [2026-11-07] Extend SbSpinner without changing existing callers
Context: The plan stage strip needs a track ring, a shorter arc and different turn durations. QA, validation and chat already use SbSpinner.
Decision: Add durationMillis, trackAlpha and arcSweep with defaults of 1000 milliseconds, no track and a 270-degree arc. Plan callers supply their own values; reduced motion holds rotation at zero.
Reason: These defaults preserve the previous drawing for existing callers while the plan strip matches its mock.

## [2026-10-30] Supply dialog text styles explicitly
Context: Dialogs need muted body text while accepting composable content.
Decision: Provide SbDialogText and SbDialogTextStyle alongside the composable body slot.
Reason: Foundation BasicText does not inherit the dialog's provided text style.

## [2026-10-30] Keep desktop APIs behind the design-system platform bridge
Context: Common composites need a resize cursor and focus restoration after popup disposal.
Decision: Declare expect members in common code and implement them with AWT and Swing in jvmMain.
Reason: The shared components avoid JVM-only imports and another target can supply its own bridge.

