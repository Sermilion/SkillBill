---
name: bill-kmp-code-review
description: Use when conducting a thorough Android/KMP PR code review. Preserve mobile review depth by flattening the manifest-declared Kotlin baseline into direct specialist lanes alongside KMP-specific specialists. Produces a structured review with risk register and prioritized action items. Use when user mentions Android review, KMP review, mobile review, or asks to review Android/KMP changes.
internal-for: skill-bill
---

# Android/KMP PR Review

Treat the generated flattened Review Composition plan as authoritative. Launch its Kotlin and KMP specialists directly; never launch the Kotlin baseline orchestrator as a nested worker.

## Classification Rules

- If `kotlin("multiplatform")`, `org.jetbrains.kotlin.multiplatform`, `commonMain`, `androidMain`, `iosMain`, or paired `expect`/`actual` declarations dominate, select the KMP pack.
- If Android markers coexist with KMP source sets, keep the KMP route because it retains the Kotlin baseline and KMP specialists.
- Otherwise, select the adjacent Kotlin pack when no multiplatform boundary is present.
- Exclude generated, vendored, and non-stack files from routing dominance.

## Diff-Signal Routing Table

- Gradle module boundaries, `build.gradle.kts` dependency direction, Hilt or Koin scope ownership, `ViewModel` ownership of long-lived work, repository, use-case, or sync-engine authority, `WorkManager` enqueue ownership, or offline single-source-of-truth writes -> `architecture` specialist.
- `expect`/`actual`, `commonMain`, `androidMain`, `iosMain`, source-set dependencies, or target-boundary declarations -> `platform-correctness` specialist.
- `kotlinx.serialization`, polymorphic registration, `kotlinx-datetime`, timezone behavior, `Dispatchers.Main`, iOS dispatchers, ObjC exports, Skie `Flow`, or suspend cancellation -> `platform-correctness` specialist.
- Room entities, DAOs, `@Transaction`, `.sq` files, SQLDelight drivers, `DataStore` edits, schema migrations, or offline sync cursors and idempotency keys -> `persistence` specialist.
- `WorkManager` enqueue, `CoroutineWorker`, backoff policy, work constraints, foreground services, process-death recovery, or long-lived collector supervision -> `reliability` specialist.
- `@Composable`, UI state, `Modifier`, `remember`, `LaunchedEffect`, navigation, previews, or loading/content/error/empty states -> `ui` specialist.
- `AndroidManifest.xml` exported components, `intent-filter` deeplinks, `PendingIntent` flags, `WebView` settings, network security configuration, `Keystore` or `EncryptedSharedPreferences` key handling, `allowBackup`, clipboard use, or logged user data -> `security` specialist.
- Compose semantics, labels, focus, TalkBack, keyboard behavior, localization, or touch targets -> `ux-accessibility` specialist.
- `proguard-rules.pro`, `consumer-rules.pro`, `isMinifyEnabled`, `minifyEnabled`, or R8 configuration -> `platform-correctness` specialist; include the baseline `android-r8` add-on.
- Navigation graphs, `NavController`, `SavedStateHandle`, `popUpTo`, `launchSingleTop`, or dialog destinations -> `ui` specialist; include the UI `android-navigation` add-on.

## Mixed Diffs

- Keep KMP routing for the whole review when strong multiplatform signals coexist with backend, Android, or generic Kotlin files.
- Use lightweight file-level classification to scope each specialist to matching files without dropping a selected lane.
- Exclude generated, vendored, and non-stack files from specialist scope unless a generated contract is itself the reviewed artifact.
- Preserve the manifest-declared Kotlin baseline as direct lanes for the same whole-review scope, then merge and deduplicate findings without losing the `kmp -> kotlin` attribution chain.
- Launch every selected lane as a subagent in this harness in a deterministic order and retain every selected result.

## Finding Discipline

- Report only reachable failures with a concrete precondition and observed consequence.
- Assign severity from impact, keep every finding attributed to its baseline, specialist, or add-on lane, and deduplicate without erasing ownership.
- Read selected specialist sidecars from the installed `skill-bill` directory; do not invoke internal specialists through a skill command.
