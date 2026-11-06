package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.infrastructure.workflow.featuretask.FileSystemPhaseInstructionFileSource
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaTargetKind
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs

class QaStandalonePhaseInstructionsTest {
  private val repoRoot = Files.createTempDirectory("skillbill-qa-instructions-repo")
  private val home = Files.createTempDirectory("skillbill-qa-instructions-home")
  private val storeRoot = Files.createTempDirectory("skillbill-qa-instructions-store")
  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
  private val database = phaseRunDatabase(home, clock)
  private val marker = "qa-operator-instruction-marker-510"

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
    storeRoot.toFile().deleteRecursively()
  }

  @Test
  fun `standalone QA includes repository phase instructions in its prompt`() {
    Files.createDirectories(repoRoot.resolve(".skill-bill/phases"))
    Files.writeString(repoRoot.resolve(".skill-bill/phases/qa.md"), "Follow setup note $marker.\n")
    val branchSetup =
      committedRepoBranchSetup().also {
        it.gitOperations.runtimePhaseHeadCommitSequence += List(20) { "a".repeat(40) }
        it.gitOperations.repositoryFingerprintValue = "b".repeat(64)
        it.gitOperations.trackedPathsValue = listOf("src/Foo.kt")
      }
    val launcher = RuntimeRecordingLauncher { _ -> facts(validJsonOutput(SkeletonDefinition.QA.id)) }
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        branchSetup = branchSetup,
        repoRoot = repoRoot,
        launcher = launcher,
        phaseInstructionSource = FileSystemPhaseInstructionFileSource(FixedProjectStorePort(storeRoot)),
      )
    val harness = telemetryRunnerHarness(runtimeConfig = config, databaseFactory = { database })
    val pack =
      kotlinPackWithValidationGate().copy(
        qaCapability = QaCapabilityDeclaration(setOf(QaTargetKind("android")), "qa/android-qa.md"),
      )
    val entry =
      PhaseRunEntryFixture(
        harness.strategies,
        config.harnessGitOperations,
        database,
        clock,
        harness.runLoopEntry,
        qaManifests = listOf(pack),
      ).entry()

    assertIs<PhaseRunResult.Completed>(entry.run(PhaseRunRequest(SkeletonDefinition.QA.id, repoRoot, "claude")))

    val prompt = requireNotNull(launcher.requests.first().skillRunRequest.promptOverride)
    assertContains(prompt, "### From .skill-bill/phases/qa.md")
    assertContains(prompt, marker)
    assertContains(prompt, "## Unblock")
  }
}
