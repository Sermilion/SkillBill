package skillbill.engine.featuretask.qa.report

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
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

  private fun database() = phaseRunDatabase(home, clock)

  private fun recorder() = FeatureTaskQaOutcomeRecorder(database(), clock)
}
