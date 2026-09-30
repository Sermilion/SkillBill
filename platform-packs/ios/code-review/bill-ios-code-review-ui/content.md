---
name: bill-ios-code-review-ui
description: Use when reviewing iOS ownership, navigation, presentation, animation, and adaptive layout.
internal-for: skill-bill
---

# UI Review Specialist

Review only user-visible interaction or rendering failures.

## Focus

- SwiftUI and UIKit ownership boundaries
- Navigation, presentation, identity, and animation
- Layout, adaptation, and deployment-version fallback

## Ignore

- Visual preferences without a specified design or behavior contract
- Framework migration suggestions without a reachable defect
- UX-accessibility semantics and announcements, which belong to the ux-accessibility lane
- Authentication, privacy, and sensitive presentation, which belong to the security lane

## Applicability

Apply SwiftUI or UIKit rules to detected surfaces and respect deployment targets, device classes, scenes, and supported OS versions.

## Project-Specific Rules

### Ownership And Navigation Rules

- For deployment targets before iOS 17, reference-type view models created by a SwiftUI view must use `@StateObject`; reject `@ObservedObject` construction that loses state on recomputation.
- On supported Observation targets, `@Observable` models owned by a view must use stable `@State` storage; reject body-local creation that resets user state.
- External models must remain `@ObservedObject`, `@Environment`, or injected Observation references; reject duplicate ownership that races and renders incorrect data.
- `NavigationStack` path elements must have stable `Hashable` identity and restorable payloads; reject transient identity that breaks back navigation after state changes.
- Sheet and popover presentation must have one source of truth such as `.sheet(item:)`; reject competing booleans that present incorrect content or trigger UIKit warnings.
- UIKit presentation must use the active scene and visible `UIViewController`; reject `UIApplication.shared.windows.first` because multi-window lifecycle changes cause presentation failure.

### Rendering And Adaptation Rules

- SwiftUI collection identity must use domain identifiers rather than array offsets; reject index-based `ForEach` that corrupts row state during insertion or deletion.
- Animations must scope `.animation(_:value:)` to the intended value and respect Reduce Motion when motion conveys state; reject global animation that produces incorrect transitions.
- Layout must adapt with safe areas, size classes, or `ViewThatFits` where applicable; reject fixed screen assumptions that clip content on supported devices.
- UIKit constraints must avoid ambiguous or unsatisfiable layouts under rotation and split view; reject missing anchors that create runtime layout failures.
- Presentation detents and newer navigation APIs must use `#available` or a deployment-compatible fallback; reject unconditional calls that fail compilation or behavior on supported OS versions.
- Async image and cell updates must verify current identity before assignment; reject stale completion races that render another item's content.
- UI state restoration must serialize only valid navigation and presentation data contracts through `Codable` with bounded resource use; reject restoration payloads that crash decoding or reopen an invalid destination after relaunch.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
