package skillbill.engine.featuretask.qa.checklist

import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaStepStatus
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaRetainedScreenshot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QaChecklistRecorderTest {
  private val warnings = mutableListOf<String>()
  private val diagnostics =
    object : RuntimeDiagnostics {
      override fun warning(
        message: String,
        error: Throwable?,
      ) {
        warnings += message
      }

      override fun error(
        message: String,
        error: Throwable?,
      ) = Unit

      override fun info(message: String) = Unit
    }
  private val database =
    sqliteDatabaseSessionFactory(
      Files.createTempDirectory("qa-checklist"),
      environment = mapOf("TEST" to "1"),
      diagnostics = diagnostics,
    )
  private val recorder =
    QaChecklistRecorder(database, Clock.fixed(NOW, ZoneOffset.UTC), diagnostics, InMemoryQaWorkspaces())

  @Test
  fun `re-registering a plan keeps a result only on a matching case, number and text`() {
    register()
    recorder.seed(SEED)
    assertIs<QaChecklistOutcome.Recorded>(recorder.registerPlan(RUN, plan("first", "second")))
    recorder.recordStep(QaStepReport(RUN, "login", 1, StandaloneQaStepStatus.PASSED, note = "ok"))
    recorder.recordStep(QaStepReport(RUN, "login", 2, StandaloneQaStepStatus.FAILED))

    assertIs<QaChecklistOutcome.Recorded>(recorder.registerPlan(RUN, plan("first", "changed")))

    val steps = checklist().cases.single().steps
    assertEquals(StandaloneQaStepStatus.PASSED, steps[0].status)
    assertEquals("ok", steps[0].note)
    assertEquals(NOW, steps[0].updatedAt)
    assertEquals(StandaloneQaStepStatus.PENDING, steps[1].status)
    assertNull(steps[1].updatedAt)
    assertEquals(NOW, checklist().planRegisteredAt)
  }

  @Test
  fun `an unknown step is rejected, changes nothing and records no agent text`() {
    register()
    recorder.registerPlan(RUN, plan("first"))
    val before = checklist()

    val outcome = recorder.recordStep(QaStepReport(RUN, "login", 2, StandaloneQaStepStatus.PASSED, note = "SECRET"))

    assertIs<QaChecklistOutcome.Rejected>(outcome)
    assertEquals(before, checklist())
    assertTrue(warnings.single().startsWith("seam=qa_progress kind=rejected"))
    assertFalse("SECRET" in warnings.single())
  }

  @Test
  fun `bad evidence is dropped and reported while the status and valid paths record`() {
    register()
    recorder.seed(SEED)
    recorder.registerPlan(RUN, plan("first"))
    val good = "$DIRECTORY/shot.png"
    val outside = "/elsewhere/shot.png"
    val escaping = "$DIRECTORY/../shot.png"

    val outcome =
      recorder.recordStep(
        QaStepReport(
          RUN,
          "login",
          1,
          StandaloneQaStepStatus.FAILED,
          findingId = "F-1",
          evidence = listOf(good, outside, escaping),
        ),
      )

    assertEquals(listOf(outside, escaping), assertIs<QaChecklistOutcome.Recorded>(outcome).rejectedEvidence)
    val stored = checklist()
    val step = stored.cases.single().steps.single()
    assertEquals(StandaloneQaStepStatus.FAILED, step.status)
    assertEquals(listOf(good), step.evidence)
    assertEquals(NOW, stored.lastStepUpdateAt)
    assertEquals("F-1", stored.findings.single().title)
    assertFalse(outside in warnings.single())
    assertTrue("kind=evidence_rejected" in warnings.single())
  }

  @Test
  fun `evidence is dropped when no invocation directory is known`() {
    register()
    recorder.registerPlan(RUN, plan("first"))
    val path = "$DIRECTORY/shot.png"

    val outcome =
      recorder.recordStep(QaStepReport(RUN, "login", 1, StandaloneQaStepStatus.PASSED, evidence = listOf(path)))

    assertEquals(listOf(path), assertIs<QaChecklistOutcome.Recorded>(outcome).rejectedEvidence)
    assertEquals(StandaloneQaStepStatus.PASSED, checklist().cases.single().steps.single().status)
  }

  @Test
  fun `a missing row, a row of another phase and a duplicate case id are rejected`() {
    register(phaseId = "review")

    assertIs<QaChecklistOutcome.Rejected>(recorder.registerPlan("absent", plan("first")))
    assertIs<QaChecklistOutcome.Rejected>(recorder.registerPlan(RUN, plan("first")))
    val duplicate =
      """{"contract_version":"0.1","cases":[
        {"id":"a","title":"A","steps":[{"text":"x"}]},{"id":"a","title":"B","steps":[{"text":"y"}]}]}"""
    assertIs<QaChecklistOutcome.Rejected>(recorder.registerPlan(RUN, duplicate))
  }

  @Test
  fun `a screenshot of an active standalone QA run is retained and one of another phase is rejected`() {
    val workspaces =
      object : QaWorkspacePort by InMemoryQaWorkspaces() {
        override fun retainScreenshot(
          repoRoot: Path,
          invocationId: String,
          sourcePath: Path,
          label: String,
          asFrame: Boolean,
        ): QaRetainedScreenshot = QaRetainedScreenshot("$DIRECTORY/retained/s1.png", "png", 100, 10, 10)
      }
    val screenshots = QaChecklistRecorder(database, Clock.fixed(NOW, ZoneOffset.UTC), diagnostics, workspaces)
    val source = Path.of("$DIRECTORY/evidence/s1.png")
    register()

    val recorded = screenshots.recordScreenshot(Path.of("/repo"), RUN, source, "Edit kept", false)

    assertEquals(QaChecklistOutcome.Recorded(retainedPath = "$DIRECTORY/retained/s1.png"), recorded)
    assertIs<QaChecklistOutcome.Rejected>(
      screenshots.recordScreenshot(Path.of("/repo"), "absent", source, "Edit kept", false),
    )
  }

  private fun checklist(): StandaloneQaChecklist =
    checkNotNull(database.read { it.standalonePhaseStatuses.readByInvocation(RUN) }?.qaChecklist)

  private fun plan(vararg steps: String): String =
    """{"contract_version":"0.1","cases":[{"id":"login","title":"Login","steps":[""" +
      steps.joinToString(",") { """{"text":"$it"}""" } + "]}]}"

  private fun register(phaseId: String = SkeletonDefinition.QA.id) {
    database.transaction {
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
          invocationId = RUN, phaseId = phaseId, executionId = "execution", lifecycleState = "active",
          currentStep = "qa", startedAt = Instant.EPOCH, leaseOwner = "owner", leaseGeneration = 1,
          leaseExpiresAt = Instant.EPOCH.plusSeconds(3600),
        ),
      )
    }
  }

  private companion object {
    const val RUN = "qa-run"
    const val DIRECTORY = "/state/qa/repo/qa-run"
    val NOW: Instant = Instant.parse("2026-11-01T10:00:00Z")
    val SEED = QaChecklistSeed(RUN, DIRECTORY, "$DIRECTORY/report.md", "abc", listOf("web"), null)
  }
}
