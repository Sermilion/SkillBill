# Prototype state coverage

The desktop app in `desktop/` defines shared styling and behavior. Keep added mock functionality for later implementation. This pass changes only the prototype and its handoff notes.

Open `index.html#states`. Every example opens the same detail or popup renderer used by the prototype. Sample records stay out of the everyday Work board and History lists. Each work example names its board section or History in the catalog. Idle Verify is detail-only because the runtime gives it no live section.

## Source inventory

| Contract | Source relative to the repository root |
| --- | --- |
| Seven lifecycle values and the three work kinds | `desktop/core/domain/src/commonMain/kotlin/dev/skillbill/desktop/core/domain/model/WorkModels.kt` |
| Planning and stalled over Active, Idle displayed as Pending | `desktop/core/ui/src/commonMain/kotlin/dev/skillbill/desktop/core/ui/status/StatusKindMapping.kt` |
| Subtask states, including completed without changes and skipped | `desktop/core/designsystem/src/commonMain/kotlin/dev/skillbill/desktop/core/designsystem/status/StatusKind.kt` |
| Standalone lifecycle conversion, goal and Verify Idle, board membership | `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/idestatus/model/IdeStatusPolicy.kt` |
| Standalone phase discovery | `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/phase/PhaseCommand.kt`, `PhaseInvocationParser.phaseNames()` |
| Registered operations | `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/operation/`, the seven operation implementations' `id` declarations |
| Operation outcomes and confirmation | `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/operation/core/OperationOutcome.kt`, `OperationConfirmationGate.kt` |
| Update check's completed result, including unavailable information | `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/operation/updatecheck/UpdateCheckOperation.kt` |
| Verify pending, running, parked, superseded, stopped and finished | `desktop/feature/detail/src/commonMain/kotlin/dev/skillbill/desktop/feature/detail/VerifyRunState.kt` |
| Verify workflow writes | `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/operation/verify/VerifyOperation.kt`, `VerifyStepSequence.kt` |
| Goal finish's Assessment / QA switch and missing-result messages | `desktop/feature/detail/src/commonMain/kotlin/dev/skillbill/desktop/feature/detail/DetailsPane.kt` |
| Goal finish pane width | `desktop/feature/detail/src/commonMain/kotlin/dev/skillbill/desktop/feature/detail/DetailScreen.kt` |
| First-run readiness and pending home | `desktop/feature/chat/src/jvmMain/kotlin/dev/skillbill/desktop/feature/chat/newtask/NewTaskHome.kt`, `NewTaskHomeContent.kt`, `NewTaskScreen.kt` |

The catalog has 14 work types. The six standalone phases are plan, review, validation, QA, PR and monitor. The seven operations are verify, PR review fix, unit test value check, feature guard, feature guard cleanup, release and update check.

The lifecycle table has 70 linked cells. N/A cells explain why the runtime does not produce that combination. Ordinary operations have no paused or canceled outcome. Verify stops or parks rather than writing a paused workflow. Update check returns a completed check result, including when release information is unavailable. Standalone phases do not use Idle.

## Result variants

The catalog includes the existing plan, validation, QA and Verify scenarios. It adds CI running and failing, a PR opened, clean and finding-bearing reviews, approval, pending Verify and per-type stalled displays. Goal QA has direct links for review needed, passed, not tested, cleanup needed, skipped, unfinished and older reports. Goal assessment has missing, running and open-finding examples.

Goal links ending in `/finish/qa` or `/finish/assessment` select Goal finish and its result tab. The wide pane shows the selected result before Execution, Runs and fixes, and spec files. The main document does not repeat it. When the pane is unavailable, the selected Goal finish row shows the same switch inline.

The catalog also links chat tab counts and overflow, the close confirmation, queued messages, all repository-picker scenarios, saved and CLI chat resume, no matching or saved chats, more than 30 saved chats, all seven device lists, and every spec control state and project read state.

## App alignment and retained additions

- Shared headers, menus, status icons and Resume styling follow the app.
- Verify confirmation uses amber, and a stopped run uses red. The section is called Criteria and its grades read PASS, PARTIAL and FAIL. The design keeps its filters and collapsible evidence rows.
- Marked criteria leave fix selection and all shared-code hints, including the marked row's own hint.
- Validation uses the app's LIVE label and green completed tone. Its header keeps the lifecycle label unless reported checks provide an outcome.
- Completed generic details drop the freshness pill. Key-value lists have explicit zero margins instead of browser defaults.
- Confirm criteria, Stop, Resume, Discard, Adjust intake and the fix-routing controls remain available in the design. Chat remains a confirmation path.
- The queue popup, device picker, process program name in QA leftovers and plan retry control remain design additions awaiting implementation where the app has no equivalent.

Standalone mocks remain in place. Delete each only after the operator confirms its synced version.

## Verification

Browser verification uses Chromium at 1440 by 1000, with a 900 px check for the inline Goal finish result. All 187 links in States passed checks for lookup identity, detail rendering, popup presence, and missing values such as undefined or NaN. Assessment / QA switching, absence of duplicate goal results in the main document, and the inline narrow layout also passed. The checked routes had no JavaScript errors or horizontal overflow.

A separate New task interaction check covers queue removal and Undo, clear confirmation, Send now and Stop, picker keyboard navigation and filtering, resume filtering and preview, tab close and Reopen, saved-chat discard and Undo, command menu and navigation.

Components and Foundations rendered through their full scroll heights without JavaScript errors. JavaScript syntax checks and `git diff --check` passed.

These checks verify prototype behavior and browser layout. They do not prove pixel parity with a running Compose app. No desktop build or runtime run was launched for this pass.
