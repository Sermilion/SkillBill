package skillbill.engine.featuretask.qa.report

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.taskruntime.model.qa.QaRunOccurrence
import skillbill.workflow.taskruntime.model.qa.QaRunPreview
import skillbill.workflow.taskruntime.model.qa.QaRunRetainedResource
import skillbill.workflow.taskruntime.model.qa.QaRunScreenshot
import skillbill.workflow.taskruntime.model.qa.QaRunStage
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTaskQaOutcomeRecorderTest {
  @TempDir lateinit var home: Path

  private val clock = Clock.fixed(Instant.parse("2026-10-24T09:00:00Z"), ZoneOffset.UTC)

  @Test
  fun `a warning or contained failure with no recorded outcome creates an incomplete row`() {
    val recorder = recorder()

    recorder.recordWarning("wfl-warned", "validate changed code after QA settled.")
    recorder.recordContainedFailure("wfl-failed", FeatureTaskQaFailureKind.ENVIRONMENT, "device lease failed")

    assertEquals(
      FeatureTaskQaOutcome(
        "wfl-warned",
        StandaloneQaOutcome.INCOMPLETE,
        "validate changed code after QA settled.",
        0,
        null,
        "2026-10-24T09:00:00Z",
      ),
      recorder.find("wfl-warned"),
    )
    assertEquals(FeatureTaskQaFailureKind.ENVIRONMENT, recorder.find("wfl-failed")?.failureKind)
    assertEquals("device lease failed", recorder.find("wfl-failed")?.reason)
  }

  @Test
  fun `a contained failure keeps the settled findings, appends its reason and the latest kind wins`() {
    val recorder = recorder()
    val finding =
      FeatureTaskQaFinding("F-1", "Login breaks.", emptyList(), "/qa/report.md", FeatureTaskQaFindingStatus.FIXED)
    database().transaction { unitOfWork ->
      unitOfWork.featureTaskQaOutcomes.upsert(
        FeatureTaskQaOutcome(
          "wfl-goal",
          StandaloneQaOutcome.PASSED,
          null,
          2,
          "tree-1",
          "2026-10-20T00:00:00Z",
          listOf(finding),
          "report",
        ),
      )
    }

    recorder.recordContainedFailure("wfl-goal", FeatureTaskQaFailureKind.ENVIRONMENT, "first")
    recorder.recordContainedFailure("wfl-goal", FeatureTaskQaFailureKind.DEFECT, "second")
    recorder.recordContainedFailure("wfl-goal", FeatureTaskQaFailureKind.DEFECT, "second")
    recorder.recordWarning("wfl-goal", "third")

    assertEquals(
      FeatureTaskQaOutcome(
        "wfl-goal",
        StandaloneQaOutcome.INCOMPLETE,
        "first; second; third",
        2,
        "tree-1",
        "2026-10-24T09:00:00Z",
        listOf(finding),
        "report",
        FeatureTaskQaFailureKind.DEFECT,
      ),
      recorder.find("wfl-goal"),
    )
  }

  @Test
  fun `a new QA attempt clears the recorded failure kind and reason`() {
    val recorder = recorder()
    recorder.recordContainedFailure("wfl-goal", FeatureTaskQaFailureKind.DEFECT, "boom")

    recorder.recordAttempted("wfl-goal")

    val attempted = recorder.find("wfl-goal")
    assertEquals(StandaloneQaOutcome.ATTEMPTED, attempted?.outcome)
    assertNull(attempted?.failureKind)
    assertNull(attempted?.reason)
  }

  @Test
  fun `retained resources create a row, union by identity, and survive a later settle`() {
    val recorder = recorder()
    val process = QaRetainedResource("process", "48213")
    val volume = QaRetainedResource("volume", "qa-data")

    recorder.recordRetainedResources("wfl-goal", emptyList())
    assertNull(recorder.find("wfl-goal"))

    recorder.recordRetainedResources("wfl-goal", listOf(process))
    recorder.recordAttempted("wfl-goal")
    recorder.recordRetainedResources("wfl-goal", listOf(process, volume))
    recorder.recordSettled("wfl-goal", QaVerdict.INCOMPLETE, "stopped", 0, QaSettledRun("tree"))

    assertEquals(
      listOf(QaRunRetainedResource("process", "48213"), QaRunRetainedResource("volume", "qa-data")),
      recorder.find("wfl-goal")?.details?.retainedResources,
    )
  }

  @Test
  fun `ending an earlier preview cannot stop the replacement and incomplete settlement preserves its identity`() {
    val recorder = recorder()
    val preview = QaRunPreview("qa-current", "/qa/current", listOf("desktop"), active = true)
    database().transaction { unit ->
      unit.featureTaskQaOutcomes.upsert(
        FeatureTaskQaOutcomeRecords.attempted("wfl-goal", null, clock.instant()).copy(
          details = QaRunDetails(preview = preview),
        ),
      )
    }

    recorder.recordPreviewEnded("wfl-goal", "qa-earlier", "interrupted")
    assertEquals(preview, recorder.find("wfl-goal")?.details?.preview)
    recorder.recordSettled("wfl-goal", QaVerdict.INCOMPLETE, "stopped", 0, QaSettledRun("tree"))
    recorder.recordPreviewEnded("wfl-goal", "qa-current", "interrupted")

    assertEquals(preview.copy(active = false), recorder.find("wfl-goal")?.details?.preview)
  }

  @Test
  fun `settling QA preserves report screenshots on the matching round and leaves earlier rounds unchanged`() {
    val recorder = recorder()
    val earlier =
      QaRunOccurrence("qa:earlier", "qa", 1, "earlier", clock.instant().toString(), disposition = "passed")
    val current =
      QaRunOccurrence(
        "qa:current",
        "qa",
        2,
        "current",
        clock.instant().toString(),
        stages = listOf(QaRunStage("capture", "running", clock.instant().toString())),
      )
    val screenshot = QaRunScreenshot("Final view", "/qa/current/final.png", clock.instant().toString(), "png")
    database().transaction { unit ->
      unit.featureTaskQaOutcomes.upsert(
        FeatureTaskQaOutcomeRecords.attempted("wfl-goal", null, clock.instant()).copy(
          details = QaRunDetails(occurrences = listOf(earlier, current)),
        ),
      )
    }
    val report =
      QaReport("Verdict: passed", QaVerdict.PASSED, null, emptyList(), null, screenshots = listOf(screenshot))

    recorder.recordSettled(
      "wfl-goal",
      QaVerdict.PASSED,
      null,
      0,
      QaSettledRun("tree", QaVerdict.PASSED, report, "/qa/current/report.md", invocationId = "current"),
    )

    val rounds = recorder.find("wfl-goal")?.details?.occurrences.orEmpty()
    assertEquals(earlier, rounds.first())
    assertEquals(listOf(screenshot), rounds.last().screenshots)
    assertEquals("passed", rounds.last().disposition)
    assertEquals(clock.instant().toString(), rounds.last().finishedAt)
    assertEquals("/qa/current/report.md", rounds.last().resultReference)
    assertEquals("interrupted", rounds.last().stages.single().state)
    assertEquals(clock.instant().toString(), rounds.last().stages.single().finishedAt)
  }

  @Test
  fun `fresh history is recorded while an existing outcome retains its legacy gap`() {
    val recorder = recorder()
    database().transaction { unit ->
      unit.featureTaskQaOutcomes.upsert(FeatureTaskQaOutcomeRecords.attempted("legacy", null, clock.instant()))
    }

    fun context(id: String): QaExecutionContext =
      QaExecutionContext(
        InMemoryQaWorkspaces().open(home, id),
        qaTestExpected(id),
        QaExecutionTarget.Skipped(QaSkipReason.DISABLED_POLICY, "QA is off"),
      )
    recorder.recordAttempted("fresh")
    recorder.recordPreviewStarted("fresh", context("fresh"), 1)
    recorder.recordRepairStarted("fresh", 1)
    recorder.recordRepairSettled("fresh", 1, "accepted_output")
    recorder.recordAttempted("fresh")
    recorder.recordAttempted("legacy")
    recorder.recordPreviewStarted("legacy", context("legacy"), 1)
    recorder.recordRepairStarted("legacy", 2)
    recorder.recordAttempted("missing-history")
    recorder.recordPreviewStarted("missing-history", context("missing-history"), 3)

    assertEquals("not_recorded", recorder.find("missing-history")?.details?.historyAvailability)
    assertEquals("recorded", recorder.find("fresh")?.details?.historyAvailability)
    assertEquals("not_recorded", recorder.find("legacy")?.details?.historyAvailability)
  }

  @Test
  fun `a replacement repair interrupts abandoned history without inventing its stop time or losing evidence`() {
    val recorder = recorder()
    val screenshot = QaRunScreenshot("Last frame", "/qa/old/frame.png", clock.instant().toString(), "png")
    val abandoned =
      QaRunOccurrence(
        "qa:old", "qa", 1, "old", "2026-10-23T00:00:00Z",
        frameReference = screenshot.path,
        frameAvailability = "available",
        screenshots = listOf(screenshot),
        stages = listOf(QaRunStage("build", "running", "2026-10-23T00:00:00Z")),
      )
    val terminal = abandoned.copy(occurrenceId = "qa:done", workspaceInvocationId = "done", disposition = "passed")
    database().transaction { unit ->
      unit.featureTaskQaOutcomes.upsert(
        FeatureTaskQaOutcomeRecords.attempted("goal", null, clock.instant()).copy(
          details = QaRunDetails(occurrences = listOf(terminal, abandoned)),
        ),
      )
    }

    recorder.recordRepairStarted("goal", 1)
    recorder.recordRepairStarted("goal", 2)
    recorder.recordRepairSettled("goal", 1, "accepted_output")
    recorder.recordPreviewEnded("goal", "old", "passed")
    recorder.recordRepairStarted("goal", 1)
    recorder.recordRepairSettled("goal", 2, "accepted_output")

    val occurrences = recorder.find("goal")?.details?.occurrences.orEmpty()
    assertEquals(terminal, occurrences[0])
    assertEquals(
      abandoned.copy(disposition = "interrupted", stages = abandoned.stages.map { it.copy(state = "interrupted") }),
      occurrences[1],
    )
    assertEquals("interrupted", occurrences[2].disposition)
    assertNull(occurrences[2].finishedAt)
    assertEquals(2, occurrences[3].phaseAttempt)
    assertEquals("accepted_output", occurrences[3].disposition)
    assertEquals(clock.instant().toString(), occurrences[3].finishedAt)
  }

  private fun database() = phaseRunDatabase(home, clock)

  private fun recorder() = FeatureTaskQaOutcomeRecorder(database(), clock)
}
