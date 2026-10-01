package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanningProjectionNoopValidatorGuardTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) workingDir.parent else workingDir
    }

  private val noopSymbol = "AcceptingFeatureTaskRuntimeWireArtifactValidator"

  private val permittedConsumers: Map<String, String> =
    mapOf(
      "WorkerTakeoverFencingTest.kt" to
        "Worker lease fencing uses real execution-plan validation; planning payload shape is outside this test.",
      "CheckpointHistoryRefusalTest.kt" to
        "Tests retained checkpoint history without accepting or executing planning outputs.",
      "QuarantinedProducerRecoveryRefusalTest.kt" to
        "Tests immutable receipt evidence and recovery boundaries with real execution-plan validation.",
      "FeatureTaskContinuationAdmissionTest.kt" to
        "Tests transactional continuation admission with real execution-plan validation.",
      "FeatureTaskExecutionPlanCreationTest.kt" to
        "Tests atomic descriptor and planning import persistence; descriptor validation uses the real schema.",
      "FeatureTaskRuntimeRunnerTestSupport.kt" to
        "Shared run-loop harness default; runner-behavior tests do not assert schema-projection " +
        "enforcement (covered by the RealValidator* integration suites).",
      "FeatureTaskRuntimeBuildGateProgressStoreIsolationTest.kt" to "test fixture",
      "FeatureTaskRuntimeGoalContinuationAdoptionPersistenceTest.kt" to "test fixture",
      "FeatureTaskRuntimeDiagnosticDegradationTest.kt" to "test fixture",
      "FeatureTaskRuntimeSharedEvidenceRecorderTest.kt" to "test fixture",
      "FeatureTaskRuntimeFindingVerificationDurableDecodeTest.kt" to "test fixture",
      "FeatureTaskRuntimeRunStateReconstructionTest.kt" to "test fixture",
      "GoalPlanningRefreshLivenessTest.kt" to "test fixture",
      "GoalRunnerRepairTest.kt" to "test fixture",
      "GoalRunnerTest.kt" to "test fixture",
      "IdeStatusServiceTestSupport.kt" to "test fixture",
      "FeatureTaskRuntimeStatusServiceTest.kt" to "test fixture",
      "WorkflowIssueKeyPersistenceTest.kt" to "test fixture",
      "WorkflowServiceTest.kt" to "test fixture",
      "FeatureTaskContinuationLookupServiceTest.kt" to "test fixture",
      "FeatureTaskRouterContinuationTest.kt" to "test fixture",
    )

  @Test
  fun `only the enumerated tests may use the Noop planning-projection validator`() {
    val testRoots =
      listOf(
        runtimeRoot.resolve("runtime-application/src/test"),
        runtimeRoot.resolve("runtime-domain/src/test"),
        runtimeRoot.resolve("runtime-cli/src/test"),
        runtimeRoot.resolve("runtime-engine/src/test"),
      )

    val actualConsumers =
      testRoots
        .filter { Files.isDirectory(it) }
        .flatMap { root ->
          Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "kt" }
              .filter { Files.readString(it).contains(noopSymbol) }
              .map { it.name }
              .toList()
          }
        }
        .toSet()

    assertEquals(
      permittedConsumers.keys,
      actualConsumers,
      "The Noop planning-projection validator consumer set drifted from the AC-004 allow-list. A new " +
        "consumer must switch to the real validator (see RealValidator* integration suites) or be added " +
        "to permittedConsumers with an explicit rationale.",
    )
  }
}
