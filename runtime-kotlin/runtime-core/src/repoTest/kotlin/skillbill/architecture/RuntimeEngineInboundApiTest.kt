package skillbill.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuntimeEngineInboundApiTest {
  @Test
  fun `consumer modules reference only pinned engine inbound api types`() {
    val violations =
      engineInboundApiViolations(
        consumerSourceRoots =
          listOf(
            moduleMainKotlinRootRelative("runtime-application"),
            moduleMainKotlinRootRelative("runtime-cli"),
            moduleMainKotlinRootRelative("runtime-mcp"),
          ),
        allowedTypes = PINNED_ENGINE_INBOUND_API_TYPES,
      )
    assertEquals(
      emptyList(),
      violations,
      "runtime-application, runtime-cli, and runtime-mcp may reference only the pinned engine inbound API.",
    )
  }

  @Test
  fun `pinned engine inbound api entries name existing engine declarations`() {
    val declarations =
      kotlinFilesUnderWithArchitectureAsserts(moduleMainKotlinRoot("runtime-engine"))
        .flatMap { path ->
          val source = Files.readString(path)
          val packageName = ArchitectureScanSupport.declaredPackage(source).orEmpty()
          TOP_LEVEL_DECLARATION.findAll(source).map { match ->
            "$packageName.${match.groupValues[1]}"
          }.toList()
        }
        .toSet()
    assertEquals(
      emptyList(),
      PINNED_ENGINE_INBOUND_API_TYPES.filterNot(declarations::contains).sorted(),
      "Every pinned inbound API entry must name an existing engine declaration.",
    )
  }

  @Test
  fun `engine inbound api scanner rejects synthetic unpinned engine type reference`() {
    val root = Files.createTempDirectory("engine-inbound-api-fixture")
    val sourceRoot = root.resolve("runtime-cli/src/main/kotlin")
    Files.createDirectories(sourceRoot.resolve("skillbill/cli/fixture"))
    val sourceFile = sourceRoot.resolve("skillbill/cli/fixture/SyntheticLeak.kt")
    Files.writeString(
      sourceFile,
      """
      import skillbill.engine.featuretask.FeatureTaskRuntimeRunnerPolicies

      class SyntheticLeak(private val policies: FeatureTaskRuntimeRunnerPolicies)
      """.trimIndent(),
    )
    val violation =
      engineInboundApiViolations(
        consumerSourceRoots = listOf(sourceRoot.toString()),
        allowedTypes = PINNED_ENGINE_INBOUND_API_TYPES,
      ).singleOrNull()
    assertNotNull(
      violation,
      "Regression if an unpinned engine type reference is not reported.",
    )
    assertTrue(
      violation.contains("FeatureTaskRuntimeRunnerPolicies") &&
        violation.contains("SyntheticLeak.kt") &&
        violation.contains(sourceRoot.toString()),
      "Violation must name the unpinned engine type.",
    )
  }

  internal companion object {
    val TOP_LEVEL_DECLARATION =
      Regex(
        """(?m)^\s*(?:(?:public|private|protected|internal|abstract|sealed|data|enum|value|open|""" +
          """final|inline|suspend|operator|infix|tailrec|const|expect|actual|fun)\s+)*""" +
          """(?:class|object|interface|typealias|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\b""",
      )

    val PINNED_ENGINE_INBOUND_API_TYPES: Set<String> =
      setOf(
        "skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService",
        "skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService",
        "skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver",
        "skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeModelResolver",
        "skillbill.engine.featuretask.runner.FeatureTaskRuntimeRunEntry",
        "skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService",
        "skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate",
        "skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult",
        "skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementBlockRequest",
        "skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementCompleteRequest",
        "skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementAcknowledgment",
        "skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment",
        "skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext",
        "skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment",
        "skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStatus",
        "skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent",
        "skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink",
        "skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput",
        "skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport",
        "skillbill.engine.featuretask.model.review.ReviewInvocation",
        "skillbill.engine.featuretask.model.review.ReviewTarget",
        "skillbill.engine.featuretask.phaserun.PhaseRunEntry",
        "skillbill.engine.featuretask.phaserun.PhaseRunRequest",
        "skillbill.engine.featuretask.phaserun.PhaseRunResult",
        "skillbill.engine.operation.core.OperationArguments",
        "skillbill.engine.operation.core.OperationOutputFormat",
        "skillbill.engine.operation.core.OperationExecutor",
        "skillbill.engine.operation.core.OperationOutcome",
        "skillbill.engine.operation.core.OperationRegistry",
        "skillbill.engine.operation.core.OperationRequest",
        "skillbill.engine.operation.core.OperationResult",
        "skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusProjection",
        "skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest",
        "skillbill.engine.featuretask.model.core.FeatureTaskRuntimeSubtaskOutcome",
        "skillbill.engine.goalrunner.GoalOperatorDecisionService",
        "skillbill.engine.goalrunner.preflight.GoalPreflightService",
        "skillbill.engine.goalrunner.model.GoalPreflightGateBlock",
        "skillbill.engine.goalrunner.GoalRunner",
        "skillbill.engine.goalrunner.status.GoalRunnerStatusService",
        "skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService",
        "skillbill.engine.goalrunner.model.DEFAULT_GOAL_PLANNING_BUDGET",
        "skillbill.engine.goalrunner.model.GoalIntakeAdmission",
        "skillbill.engine.goalrunner.model.GoalIntakeMissingInput",
        "skillbill.engine.goalrunner.model.GoalPreflightRequest",
        "skillbill.engine.goalrunner.model.GoalPreflightResult",
        "skillbill.engine.goalrunner.model.GoalRunnerAcceptRequest",
        "skillbill.engine.goalrunner.model.GoalRunnerAcceptResult",
        "skillbill.engine.goalrunner.model.GoalRunnerEventSink",
        "skillbill.engine.goalrunner.model.GoalRunnerOperatorDecisionRequest",
        "skillbill.engine.goalrunner.model.GoalRunnerOperatorDecisionResult",
        "skillbill.engine.goalrunner.model.GoalRunnerPauseResult",
        "skillbill.engine.goalrunner.model.GoalRunnerPauseStatus",
        "skillbill.engine.goalrunner.model.GoalRunnerPurgeRequest",
        "skillbill.engine.goalrunner.model.GoalRunnerPurgeResult",
        "skillbill.engine.goalrunner.model.GoalRunnerAppliedRepair",
        "skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosis",
        "skillbill.engine.goalrunner.model.GoalRunnerWedgeFinding",
        "skillbill.engine.goalrunner.model.GoalRunnerResetSubtaskSnapshot",
        "skillbill.engine.goalrunner.model.GoalRunnerRepairRequest",
        "skillbill.engine.goalrunner.model.GoalRunnerRepairResult",
        "skillbill.engine.goalrunner.model.GoalRunnerRepairStatus",
        "skillbill.engine.goalrunner.model.GoalRunnerReplanRequest",
        "skillbill.engine.goalrunner.model.GoalRunnerReplanResult",
        "skillbill.engine.goalrunner.model.GoalRunnerReplanSnapshot",
        "skillbill.engine.goalrunner.model.GoalRunnerResetRequest",
        "skillbill.engine.goalrunner.model.GoalRunnerResetResult",
        "skillbill.engine.goalrunner.model.GoalRunnerResetSnapshot",
        "skillbill.engine.goalrunner.model.GoalRunnerResumeResult",
        "skillbill.engine.goalrunner.model.GoalRunnerResumeStatus",
        "skillbill.engine.goalrunner.model.GoalRunnerRunRequest",
        "skillbill.engine.goalrunner.model.GoalRunnerStatusRequest",
        "skillbill.engine.goalrunner.model.GoalRunnerStopStatus",
        "skillbill.engine.goalrunner.model.GoalRunnerStopVerbResult",
        "skillbill.engine.goalrunner.planning.GoalPlanningLogService",
        "skillbill.engine.goalrunner.planning.model.GoalPlanningLog",
        "skillbill.engine.goalrunner.planning.model.GoalPlanningLogAttempt",
        "skillbill.engine.goalrunner.planning.model.GoalPlanningLogRequest",
        "skillbill.engine.work.IdeStatusService",
        "skillbill.engine.work.model.IdeStatusRequest",
        "skillbill.engine.operation.updatecheck.updateCheckContract",
      )
  }
}
