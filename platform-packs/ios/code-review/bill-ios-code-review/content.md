---
name: bill-ios-code-review
description: Use when reviewing native iOS/Swift changes (UIKit/SwiftUI, Xcode projects, local SPM packages).
internal-for: skill-bill
---

# Adaptive iOS PR Review

## Classification Rules

- If native Swift sources, Xcode workspace/project metadata, Apple platform configuration, and iOS framework imports dominate the changed product surface, select this pack.
- If a local SPM package is consumed by an iOS Xcode workspace/project, keep it in the iOS review even when the package itself has no UIKit or SwiftUI import.
- Otherwise, select the adjacent stack whose declared signals dominate; `Package.swift` alone is not sufficient to classify a repository as iOS.

## Diff-Signal Routing Table

- Swift module/package boundaries, DI registrations, `Provider` protocols, or SPM ownership crossings -> `architecture` specialist.
- Hot reducers, SwiftUI recomputation, image/PDF decoding, or large import loops -> `performance` specialist.
- Actor isolation, `Sendable` crossings, Combine effects, task cancellation, or lifecycle changes -> `platform-correctness` specialist.
- Keychain, auth/session, ATS, sensitive `Logger` output, deep links, or pasteboard data -> `security` specialist.
- XCTest, snapshots such as `assertSnapshotsOf`, reducer tests, SQL tests, fixtures, or missing regression coverage -> `testing` specialist.
- REST requests, `Codable` serialization, GraphQL/Apollo operations, schema/codegen, cache, or field policies -> `api-contracts` specialist.
- Core Data, SwiftData, GRDB/SQLite, migrations, transactions, SQL statements, or offline sync -> `persistence` specialist.
- `BGTaskScheduler`, background `URLSession`, retry/expiration handling, interrupted sync, or failure visibility -> `reliability` specialist.
- SwiftUI/UIKit views, rendering identity, shared components, navigation such as `Scene+Feature.swift`, or deployment fallbacks -> `ui` specialist.
- `Strings.swift`, `.lproj` resources, VoiceOver semantics, Dynamic Type, localization, or task completion -> `ux-accessibility` specialist.

## Mixed Diffs

- Keep `architecture` plus at least one other selected specialist for the whole review; default the second lane to `platform-correctness`, include `testing` for material test changes, and cap selection at ten specialists.
- Use lightweight file-level classification to build a focused scope for each selected specialist from the routing signals.
- Exclude generated `API.swift`, Pods, `.build`, snapshot baselines, incidental `project.pbxproj` churn, vendored dependencies, build outputs, and non-stack/non-iOS files from specialist scope and dominance scoring.
- Launch selected specialists as subagents in this harness in a deterministic order and retain every selected specialist result; do not drop a lane merely because another lane found the same issue.

## Finding Discipline

- Verify the triggering precondition before asserting a consequence, including deployment configuration, reachable state, lifecycle, nullability, and guarding behavior.
- Inspect removed lines as well as additions so dropped calls, effects, guards, fallbacks, or events remain visible.
- Preserve Swift language semantics: a `do` without `catch` propagates errors, eligible enums synthesize conformance, and a non-optional coalesced value cannot be nil.
- Calibrate severity to demonstrated impact; reserve Blocker or Major for a concrete reachable crash, data loss, wrong result, exposure, or equivalent production failure.
- Perform an attributed merge of findings and deduplicate overlaps without losing evidence, preconditions, or area-specific remediation detail.
