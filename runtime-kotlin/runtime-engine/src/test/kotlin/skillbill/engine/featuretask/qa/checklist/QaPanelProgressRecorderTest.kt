package skillbill.engine.featuretask.qa.checklist

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecords
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaRetainedScreenshot
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.taskruntime.model.qa.QaRunOccurrence
import skillbill.workflow.taskruntime.model.qa.QaRunScreenshot
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QaPanelProgressRecorderTest {
  @TempDir lateinit var home: Path

  @Test
  fun `interruption during screenshot retention rejects the late screenshot and stage without changing old evidence`() {
    val clock = Clock.fixed(Instant.parse("2026-11-08T09:00:00Z"), ZoneOffset.UTC)
    val database = phaseRunDatabase(home, clock)
    val outcomes = FeatureTaskQaOutcomeRecorder(database, clock)
    val screenshot = QaRunScreenshot("Earlier frame", "/qa/old/frame.png", clock.instant().toString(), "png")
    val occurrence =
      QaRunOccurrence(
        "qa:old",
        "qa",
        1,
        "old",
        clock.instant().toString(),
        frameReference = screenshot.path,
        frameAvailability = "captured",
        screenshots = listOf(screenshot),
      )
    database.transaction { unit ->
      unit.featureTaskQaOutcomes.upsert(
        FeatureTaskQaOutcomeRecords.attempted("goal", null, clock.instant()).copy(
          details = QaRunDetails(historyAvailability = "recorded", occurrences = listOf(occurrence)),
        ),
      )
    }
    val workspaces =
      object : QaWorkspacePort by InMemoryQaWorkspaces() {
        override fun retainScreenshot(
          repoRoot: Path,
          invocationId: String,
          sourcePath: Path,
          label: String,
          asFrame: Boolean,
        ): QaRetainedScreenshot {
          outcomes.recordRepairStarted("goal", 1)
          return QaRetainedScreenshot("/qa/old/late.png", "png", 100, 10, 10)
        }
      }
    val recorder = QaChecklistRecorder(database, clock, NoopRuntimeDiagnostics, workspaces)
    assertIs<QaChecklistOutcome.Recorded>(recorder.recordStage(QaStageReport("old", "build", "running")))

    assertIs<QaChecklistOutcome.Rejected>(
      recorder.recordScreenshot(home, "old", home.resolve("late.png"), "Late", true),
    )
    assertIs<QaChecklistOutcome.Rejected>(recorder.recordStage(QaStageReport("old", "build", "done")))

    val interrupted = assertNotNull(outcomes.find("goal")?.details?.occurrences?.first())
    assertEquals("interrupted", interrupted.disposition)
    assertNull(interrupted.finishedAt)
    assertEquals(listOf(screenshot), interrupted.screenshots)
    assertEquals(screenshot.path, interrupted.frameReference)
    assertEquals("interrupted", interrupted.stages.single().state)
    assertNull(interrupted.stages.single().finishedAt)
  }
}
