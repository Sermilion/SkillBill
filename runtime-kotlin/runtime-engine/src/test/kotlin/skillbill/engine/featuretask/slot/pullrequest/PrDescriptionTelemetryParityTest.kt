package skillbill.engine.featuretask.slot.pullrequest

import skillbill.contracts.JsonCodec
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.phaserun.outboxPayloads
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.runner.SlotBaselineJson
import skillbill.engine.featuretask.runner.SlotBaselinePaths
import skillbill.engine.featuretask.runner.SlotBaselineTestResources
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PrDescriptionTelemetryParityTest {
  private val root: Path = Files.createTempDirectory("skillbill-pr-telemetry")
  private val home: Path = Files.createTempDirectory("skillbill-pr-telemetry-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val reused =
    PullRequestIdentityLookup { _, _ -> PullRequestIdentity.Found(PR_URL, PR_NUMBER, PR_TITLE) }

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `the standalone pr step emits pr_description_generated matching the lifecycle fixture`() {
    val repoRoot = Files.createDirectories(root.resolve("standalone"))
    val git =
      standaloneGit().also {
        it.commitCountAheadValue = "3"
        it.mergeBaseWithHeadValue = "b".repeat(40)
        it.changedPathsBetweenCommitsValue = CHANGED_FILES
      }

    val run = standalonePrRun(repoRoot, database, reused, git)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(run.report, run.report.toString())
    assertSingleFixturePayload()
  }

  @Test
  fun `phase pr emits pr_description_generated matching the lifecycle fixture`() {
    val repository = PrRunRepository(Files.createDirectories(root.resolve("phase")))
    repository.initOnFeatureBranch()
    repository.commit(*CHANGED_FILES.take(3).toTypedArray())
    repository.commit(*CHANGED_FILES.drop(3).take(3).toTypedArray())
    repository.commit(*CHANGED_FILES.drop(6).toTypedArray())

    val run = phasePrRun(repository.repoRoot, database, reused, clock)

    assertIs<PhaseRunResult.Completed>(run.result, run.result.toString())
    assertSingleFixturePayload(committedPendingChanges = true)
  }

  private fun assertSingleFixturePayload(committedPendingChanges: Boolean = false) {
    val payloads = database.outboxPayloads(TelemetryOutboxEvent.PR_DESCRIPTION_GENERATED.wireValue)
    assertEquals(1, payloads.size, payloads.toString())
    val fixture =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(
          JsonCodec.parseValue(Files.readString(SlotBaselineTestResources.resolve(FIXTURE))),
        ),
      )
    val expected =
      if (committedPendingChanges) {
        fixture +
          mapOf(
            "commit_count" to 4,
            "files_changed_count" to 9,
          )
      } else {
        fixture
      }
    assertEquals(
      SlotBaselineJson.encode(expected),
      SlotBaselineJson.encode(payloads.single()),
    )
  }

  private companion object {
    const val PR_URL = "https://github.com/example/skill-bill/pull/380"
    const val PR_NUMBER = 380
    const val PR_TITLE = "SKILL-380 slot baseline"
    const val FIXTURE = "${SlotBaselinePaths.MCP_LIFECYCLE}/${SlotBaselinePaths.PR_DESCRIPTION_GENERATED}"
    val CHANGED_FILES =
      listOf(
        "src/One.kt",
        "src/Two.kt",
        "src/Three.kt",
        "src/Four.kt",
        "src/Five.kt",
        "src/Six.kt",
        "docs/Seven.md",
        "docs/Eight.md",
      )
  }
}
