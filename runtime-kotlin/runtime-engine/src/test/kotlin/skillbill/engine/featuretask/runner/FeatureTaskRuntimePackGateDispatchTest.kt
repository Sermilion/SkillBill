package skillbill.engine.featuretask.runner

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.validation.repoLocalConfig
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.scaffold.model.PlatformManifest
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimePackGateDispatchTest {
  @Test
  fun missingRequiredBuildDeclarationsBlockDurablyWithoutGateOrFallbackLaunch() {
    val pack = kotlinPackWithBuildGate()
    val gate = requireNotNull(pack.validationGate)
    listOf(null, gate.copy(buildCommand = null, cacheBypassingBuildCommand = null)).forEach { missing ->
      val repo = Files.createTempDirectory("required-build-declaration")
      try {
        val branch = committedRepoBranchSetup()
        branch.gitOperations.currentBranchValue = "feat/SKILL-384-gates"
        branch.gitOperations.changedPathsBetweenCommitsValue = listOf("src/Foo.kt")
        val launcher = satisfiedAuditLauncher()
        val harness =
          telemetryRunnerHarness(
            RuntimeHarnessConfig(
              repoRoot = repo,
              branchSetup = branch,
              launcher = launcher,
              goalContinuation =
                FeatureTaskRuntimeGoalContinuationContext(
                  parentIssueKey = "SKILL-384",
                  subtaskId = 1,
                  subtaskName = "gate evidence",
                  goalBranch = branch.gitOperations.currentBranchValue,
                  suppressPr = true,
                  parentWorkflowId = "goal-skill-384",
                  reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
                  qualityGateSelection = FeatureTaskRuntimeQualityGateSelection.BUILD,
                ),
              validationGatePlatformManifests = listOf(pack.copy(validationGate = missing)),
              validationGateRunner =
                object : ValidationGateRunner {
                  override fun run(request: ValidationGateRunRequest): ValidationGateRunResult =
                    error("Missing required command must block before gate execution")
                },
            ),
          )

        assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request))

        val record = assertNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("build"))
        assertTrue(
          record.blockedReason.orEmpty().contains("Required") || record.blockedReason.orEmpty().contains("required"),
        )
        assertTrue(record.blockedReason.orEmpty().length < 512)
        assertEquals(FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION, record.failureDisposition)
        assertTrue(record.outputArtifact == null)
        assertFalse(
          launcher.requests.any {
            phaseIdFromPrompt(it.skillRunRequest.promptOverride.orEmpty()) in setOf("build", "validate")
          },
        )
        assertTrue(branch.gitOperations.pushedBranches.isEmpty())
      } finally {
        repo.toFile().deleteRecursively()
      }
    }
  }

  @Test
  fun goalChildBuildRunsOnlyItsWrapperResolvedBuildCommandsAndRetainsDiscovery() {
    val repo = Files.createTempDirectory("goal-child-build-dispatch")
    try {
      val requests = mutableListOf<ValidationGateRunRequest>()
      val branch = committedRepoBranchSetup()
      branch.gitOperations.repositoryFingerprintValue = "build-checkpoint"
      branch.gitOperations.currentBranchValue = "feat/SKILL-384-gates"
      branch.gitOperations.changedPathsBetweenCommitsValue = listOf("src/Foo.kt")
      val pack = buildDispatchPack()
      val launcher = satisfiedAuditLauncher()
      val harness =
        telemetryRunnerHarness(
          RuntimeHarnessConfig(
            repoRoot = repo,
            branchSetup = branch,
            launcher = launcher,
            goalContinuation =
              FeatureTaskRuntimeGoalContinuationContext(
                parentIssueKey = "SKILL-384",
                subtaskId = 1,
                subtaskName = "gate evidence",
                goalBranch = branch.gitOperations.currentBranchValue,
                suppressPr = true,
                parentWorkflowId = "goal-skill-384",
                reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
                qualityGateSelection = FeatureTaskRuntimeQualityGateSelection.BUILD,
              ),
            validationGatePlatformManifests = listOf(pack),
            gateRepoLocalConfig = repoLocalConfig("./tools/gradlew"),
            validationGateRunner =
              object : ValidationGateRunner {
                override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
                  requests += request
                  val failed = requests.size == 1
                  return ValidationGateRunResult(
                    exitCode = if (failed) 1 else 0,
                    durationMs = 1,
                    outcome = if (failed) ValidationGateRunOutcome.FAILED else ValidationGateRunOutcome.PASSED,
                    cacheMode = request.cacheMode,
                    executedWorkUnits = 0,
                    executedCheckIdentities = emptyList(),
                    findings =
                      if (failed) {
                        listOf(
                          ValidationGateFinding("engine", "compile", "broken", "src/Foo.kt"),
                        )
                      } else {
                        emptyList()
                      },
                    command = request.argv.joinToString(" "),
                  )
                }
              },
          ),
        )

      val report = harness.runner.run(harness.request)

      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
      assertEquals(
        listOf(
          listOf("./tools/gradlew", "-p", "./tools", "build-discovery"),
          listOf("./tools/gradlew", "-p", "./tools", "build-verification"),
        ),
        requests.map { it.argv },
      )
      val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
      val build = assertNotNull(records["build"])
      val envelope = assertIs<Map<*, *>>(JsonCodec.parseValue(assertNotNull(build.outputArtifact)))
      val produced = assertIs<Map<*, *>>(envelope["produced_outputs"])
      assertTrue("build_receipt" in produced)
      assertFalse("validation_result" in produced)
      assertEquals("build", envelope["phase_id"])
      assertFalse("validate" in records)
      val progress = assertNotNull(harness.recorder.loadBuildGateProgress(WORKFLOW_ID))
      assertEquals(listOf(1, 0), progress.gateRuns.map { it.exitCode })
      assertEquals(requests.map { it.argv.joinToString(" ") }, progress.gateRuns.map { it.command })
      assertEquals(
        listOf("build"),
        launcher.requests.mapNotNull { it.skillRunRequest.promptOverride?.let(::phaseIdFromPrompt) }
          .filter { it == "build" || it == "validate" },
      )
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  private fun buildDispatchPack(): PlatformManifest {
    return kotlinPackWithBuildGate().let { manifest ->
      manifest.copy(
        validationGate =
          requireNotNull(manifest.validationGate).copy(
            buildCommand = listOf("./gradlew", "build-discovery"),
            cacheBypassingBuildCommand = listOf("./gradlew", "build-verification"),
            collectAllFullGateCommand = listOf("./gradlew", "validation-discovery"),
            cacheBypassingCollectAllFullGateCommand = listOf("./gradlew", "validation-verification"),
          ),
      )
    }
  }
}
