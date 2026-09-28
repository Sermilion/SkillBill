---
name: bill-ios-code-review-ux-accessibility
description: Use when reviewing iOS accessibility, localization, input, and task-completion risks.
internal-for: skill-bill
---

# UX And Accessibility Review Specialist

Review only concrete accessibility or task-completion failures.

## Focus

- Dynamic Type and adaptive layout
- VoiceOver semantics, focus, keyboard, and feedback
- Localization and completion across supported OS versions

## Ignore

- Subjective copy or aesthetic preferences without a user-impact contract
- APIs unavailable to the detected deployment target without considering fallback
- General rendering and navigation ownership, which belongs to the ui lane
- Authentication and private-data exposure, which belongs to the security lane

## Applicability

Apply to user-facing SwiftUI and UIKit changes, considering assistive technologies, input methods, locales, device sizes, and deployment-version availability.

## Project-Specific Rules

### Semantics And Adaptation Rules

- Text must use Dynamic Type styles such as `.font(.body)` or `UIFontMetrics`; reject fixed sizes that cause layout performance failure, clip content, and prevent task completion at accessibility categories.
- Layout must reflow rather than truncate essential controls when `dynamicTypeSize` grows; reject fixed heights that hide state or actions.
- Images and custom controls must provide meaningful `accessibilityLabel` values or be hidden when decorative; reject unlabeled elements that make VoiceOver state invalid.
- Combined rows must set deliberate `.accessibilityElement(children:)` behavior and reading order; reject duplicated or scrambled announcements that break navigation.
- Custom actions and gestures must expose `.accessibilityAction` or UIKit equivalents; reject gesture-only behavior that blocks VoiceOver users.
- State changes requiring attention must use `UIAccessibility.post(notification:argument:)` or SwiftUI accessibility announcements when available; reject silent failures after async work.

### Input Localization And Feedback Rules

- Modal and navigation transitions must move accessibility focus to the new heading or error; reject stale focus that leaves users interacting with hidden lifecycle state.
- Forms must set appropriate `textContentType`, submit behavior, and keyboard dismissal; reject focus traps that prevent task completion with hardware or software keyboards.
- Keyboard shortcuts must not shadow system accessibility commands and must expose discoverable `UIKeyCommand` titles; reject collisions that cause navigation failure.
- User-facing strings must use `String(localized:)`, string catalogs, or the detected localization system; reject interpolation that creates invalid grammar or untranslated-output failure.
- Localized layouts must support longer strings and right-to-left direction through `leadingAnchor` and `trailingAnchor`; reject left/right assumptions that cause clipping failure or reverse meaning.
- Success, warning, selection, and validation state must not rely on color alone; require text, symbol, haptic, or semantic feedback because color-only output creates accessibility failure.
- Newer accessibility APIs must use `#available` with an equivalent supported-OS fallback; reject feature loss that blocks task completion on the minimum deployment version.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
