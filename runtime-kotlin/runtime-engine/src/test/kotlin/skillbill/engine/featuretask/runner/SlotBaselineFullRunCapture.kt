package skillbill.engine.featuretask.runner

import skillbill.application.seedHarnessSpecIntentProjection
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.slot.runner.DefaultPhaseRunner
import skillbill.engine.featuretask.validation.passed
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertIs

internal object SlotBaselineFullRunCapture {
  const val SPEC_REFERENCE = ".feature-specs/SKILL-380-phase-slot-strategies/spec.md"
  const val PARENT_WORKFLOW_ID = "wfl-skill-380-parent"
  private const val GOAL_BRANCH = "feat/SKILL-380-phase-slot-strategies"

  val sqliteClock: Clock = Clock.fixed(Instant.parse("2026-06-02T12:00:00Z"), ZoneOffset.UTC)

  fun standalone(): SlotBaselineDurableBundle = run(goalContinuation = null, useBuildPack = false)

  fun goalChildBuild(): SlotBaselineDurableBundle =
    run(
      goalContinuation =
        goalChildContinuation(1, "slot baseline build child", FeatureTaskRuntimeQualityGateSelection.BUILD),
      useBuildPack = true,
    )

  fun goalChildValidate(): SlotBaselineDurableBundle =
    run(
      goalContinuation =
        goalChildContinuation(2, "slot baseline validate child", FeatureTaskRuntimeQualityGateSelection.VALIDATE),
      useBuildPack = false,
    )

  fun sqliteDatabase(home: Path): SQLiteDatabaseSessionFactory =
    sqliteSessionFactoryForTests(
      userHome = home,
      dbPathOverride = home.resolve("metrics.db").toString(),
      environment = emptyMap(),
      clock = sqliteClock,
    )

  fun seededRepoRoot(): Path =
    SlotBaselineNormalizer.newRepoRoot().also { seedHarnessSpecIntentProjection(it, SPEC_REFERENCE) }

  private fun goalChildContinuation(
    subtaskId: Int,
    subtaskName: String,
    qualityGate: FeatureTaskRuntimeQualityGateSelection,
  ): FeatureTaskRuntimeGoalContinuationContext =
    FeatureTaskRuntimeGoalContinuationContext(
      parentIssueKey = "SKILL-380",
      subtaskId = subtaskId,
      subtaskName = subtaskName,
      goalBranch = GOAL_BRANCH,
      suppressPr = true,
      parentWorkflowId = PARENT_WORKFLOW_ID,
      reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
      qualityGateSelection = qualityGate,
    )

  private fun run(
    goalContinuation: FeatureTaskRuntimeGoalContinuationContext?,
    useBuildPack: Boolean,
  ): SlotBaselineDurableBundle {
    val repoRoot = seededRepoRoot()
    val databaseHome = SlotBaselineNormalizer.newTempHome()
    try {
      val git =
        committedRepoBranchSetup().gitOperations.also {
          it.currentBranchValue = GOAL_BRANCH
          it.repositoryFingerprintValue = "slot-baseline-checkpoint"
          if (useBuildPack) it.changedPathsBetweenCommitsValue = listOf("src/Foo.kt")
        }
      val phaseLauncher = satisfiedAuditLauncher()
      val reviewLauncher = RuntimeRecordingLauncher { facts(VALID_REVIEW_OUTPUT) }
      val config =
        RuntimeHarnessConfig(
          branchSetup = BranchSetupTestConfig(gitOperations = git, specReference = SPEC_REFERENCE),
          repoRoot = repoRoot,
          goalContinuation = goalContinuation,
          agentAssignment = phasePerAgentAssignment(),
          validationGatePlatformManifests =
            listOf(if (useBuildPack) kotlinPackWithBuildGate() else kotlinPackWithValidationGate()),
          validationGateRunner =
            object : ValidationGateRunner {
              override fun run(request: ValidationGateRunRequest) =
                passed().copy(command = request.argv.joinToString(" "))
            },
          launcher = phaseLauncher,
          reviewRunner = DefaultPhaseRunner(reviewLauncher, git),
        )
      val harness =
        telemetryRunnerHarness(
          launcher = phaseLauncher,
          runtimeConfig = config,
          databaseFactory = { sqliteDatabase(databaseHome) },
        )
      val report = harness.runner.run(harness.request)
      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
      return SlotBaselineDurableBundle.fromRun(harness.recorder, harness.database, phaseLauncher, reviewLauncher)
    } finally {
      repoRoot.toFile().deleteRecursively()
      databaseHome.toFile().deleteRecursively()
    }
  }
}
