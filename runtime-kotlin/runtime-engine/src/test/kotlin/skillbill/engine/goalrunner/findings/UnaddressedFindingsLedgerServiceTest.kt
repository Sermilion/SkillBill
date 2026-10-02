package skillbill.engine.goalrunner.findings

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.UnaddressedFindingsRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowStateRepositoryDefaults
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnaddressedFindingsLedgerServiceTest {
  @Test
  fun `an unknown issue key returns null from every read`() {
    val service = serviceFor(InMemoryUnaddressedFindings(durableIssueKeys = setOf("SKILL-135")))

    assertNull(service.ledger("SKILL-404"))
    assertNull(service.verificationDispositions("SKILL-404"))
    assertNull(service.repairLedgersByWorkflow("SKILL-404"))
  }

  @Test
  fun `a telemetry-disabled goal with no findings returns an explicit empty ledger`() {
    val service = serviceFor(InMemoryUnaddressedFindings(durableIssueKeys = setOf("SKILL-135")))

    val ledger = assertNotNull(service.ledger("SKILL-135"))

    assertTrue(ledger.findings.isEmpty())
    assertEquals(mapOf("blocker" to 0, "major" to 0, "minor" to 0, "nit" to 0), ledger.severityBreakdown)
  }

  @Test
  fun `a valid ledger spans every subtask of the goal and accepts the writer issue-category vocabulary`() {
    val rows =
      listOf(
        finding(subtaskId = 1, workflowId = "wf-1", ordinal = 1, severity = "major", category = "behavior_correctness"),
        finding(subtaskId = 3, workflowId = "wf-3", ordinal = 1, severity = "minor", category = "data_persistence"),
      )
    val service = serviceFor(InMemoryUnaddressedFindings(setOf("SKILL-135"), rows))

    val ledger = assertNotNull(service.ledger("SKILL-135"))

    assertEquals(listOf(1, 3), ledger.findings.map { it.subtaskId })
    assertEquals(mapOf("blocker" to 0, "major" to 1, "minor" to 1, "nit" to 0), ledger.severityBreakdown)
  }

  @Test
  fun `a row outside the severity taxonomy raises the typed malformed error`() {
    val malformed = finding(subtaskId = 1, workflowId = "wf-1", ordinal = 1, severity = "catastrophic")
    val service = serviceFor(InMemoryUnaddressedFindings(setOf("SKILL-135"), listOf(malformed)))

    val error = assertFailsWith<SkillBillRuntimeException> { service.ledger("SKILL-135") }
    assertEquals(GovernedReviewFailureCode.INVALID_LEDGER_SCHEMA, error.code)
  }

  @Test
  fun `a row outside the issue-category vocabulary raises the typed malformed error`() {
    val malformed = finding(subtaskId = 1, workflowId = "wf-1", ordinal = 1, category = "platform_correctness")
    val service = serviceFor(InMemoryUnaddressedFindings(setOf("SKILL-135"), listOf(malformed)))

    val error = assertFailsWith<SkillBillRuntimeException> { service.ledger("SKILL-135") }
    assertEquals(GovernedReviewFailureCode.INVALID_LEDGER_SCHEMA, error.code)
  }

  @Test
  fun `Major findings are recorded in the ledger without triggering a fix pass`() {
    val rows =
      listOf(
        finding(
          subtaskId = 1,
          workflowId = "wf-1",
          ordinal = 1,
          severity = "blocker",
          category = "behavior_correctness",
        ),
        finding(
          subtaskId = 1,
          workflowId = "wf-1",
          ordinal = 2,
          severity = "major",
          category = "concurrency_lifecycle",
        ),
        finding(subtaskId = 1, workflowId = "wf-1", ordinal = 3, severity = "minor", category = "testing_quality_gate"),
      )
    val service = serviceFor(InMemoryUnaddressedFindings(setOf("SKILL-135"), rows))

    val ledger = assertNotNull(service.ledger("SKILL-135"))

    assertEquals(3, ledger.findings.size)
    assertEquals(mapOf("blocker" to 1, "major" to 1, "minor" to 1, "nit" to 0), ledger.severityBreakdown)

    val block = ledger.findings.first { it.severity == "blocker" }
    val major = ledger.findings.first { it.severity == "major" }
    val minor = ledger.findings.first { it.severity == "minor" }

    assertEquals("behavior_correctness", block.issueCategory)
    assertEquals("concurrency_lifecycle", major.issueCategory)
    assertEquals("testing_quality_gate", minor.issueCategory)
  }

  @Test
  fun `a malformed durable review state fails typed instead of dropping the workflow`() {
    val diagnostics = RecordingLedgerDiagnostics()
    val service =
      UnaddressedFindingsLedgerService(
        LedgerOnlySessionFactory(
          InMemoryUnaddressedFindings(
            durableIssueKeys = setOf("SKILL-135"),
            rows = listOf(finding(subtaskId = 1, workflowId = "wfl-child", ordinal = 1)),
          ),
          MalformedReviewStateWorkflowStates("wfl-child"),
        ),
        diagnostics,
      )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        service.repairLedgersByWorkflow("SKILL-135")
      }
    assertEquals(GovernedReviewFailureCode.INVALID_LEDGER_SCHEMA, error.code)
    assertTrue(diagnostics.warnings.single().contains("wfl-child"), diagnostics.warnings.single())
  }

  private fun serviceFor(findings: InMemoryUnaddressedFindings) =
    UnaddressedFindingsLedgerService(LedgerOnlySessionFactory(findings), NoopRuntimeDiagnostics)

  private fun finding(
    subtaskId: Int,
    workflowId: String,
    ordinal: Int,
    severity: String = "minor",
    category: String = "behavior_correctness",
  ) = UnaddressedFinding(
    issueKey = "SKILL-135",
    subtaskId = subtaskId,
    workflowId = workflowId,
    reviewPassNumber = 1,
    findingOrdinal = ordinal,
    severity = severity,
    issueCategory = category,
    location = "src/Example.kt:$ordinal",
    summary = "Deferred finding $ordinal",
  )
}

private class InMemoryUnaddressedFindings(
  private val durableIssueKeys: Set<String>,
  private val rows: List<UnaddressedFinding> = emptyList(),
) : UnaddressedFindingsRepository {
  override fun replaceLedgerForPass(
    workflowId: String,
    reviewPassNumber: Int,
    findings: List<UnaddressedFinding>,
  ) = error("The retrieval surface does not write.")

  override fun clearWorkflowLedger(workflowId: String) = error("The retrieval surface does not write.")

  override fun recordOutcomes(outcomes: List<ReviewFindingOutcomeRecord>) =
    error("The retrieval surface does not write.")

  override fun fetchOutcomes(workflowId: String): List<ReviewFindingOutcomeRecord> = emptyList()

  override fun fetchLedger(issueKey: String): List<UnaddressedFinding> = rows.filter { it.issueKey == issueKey }

  override fun fetchWorkflowLedger(workflowId: String): List<UnaddressedFinding> =
    rows.filter { it.workflowId == workflowId }

  override fun workflowIdsForIssue(issueKey: String): List<String> =
    rows.filter { it.issueKey == issueKey }.map { it.workflowId }.distinct().sorted()

  override fun issueExists(issueKey: String): Boolean = issueKey in durableIssueKeys
}

private class RecordingLedgerDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()

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
}

private class MalformedReviewStateWorkflowStates(
  private val workflowId: String,
) : WorkflowStateRepositoryDefaults() {
  override fun get(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateSnapshot? =
    getFeatureTaskWorkflow(workflowId)?.takeIf { family == WorkflowFamily.TASK_RUNTIME }?.toSnapshot()

  override fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? =
    if (workflowId != this.workflowId) {
      null
    } else {
      WorkflowStateRecord(
        workflowId = workflowId,
        sessionId = "session",
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = "running",
        currentStepId = "review",
        stepsJson = "[]",
        artifactsJson = """{"goal_subtask_review_state":{"review_pass_number":1}}""",
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = FeatureTaskWorkflowMode.RUNTIME,
      )
    }
}

private class LedgerOnlySessionFactory(
  private val findings: UnaddressedFindingsRepository,
  private val workflowStateRepository: WorkflowStateRepository? = null,
) : DatabaseSessionFactory {
  override fun resolveDbPath(): Path = Path.of("/fake/runtime.db")

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unit())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unit())

  private fun unit(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = Path.of("/fake/runtime.db")
      override val unaddressedFindings: UnaddressedFindingsRepository = findings
      override val reviews: ReviewRepository
        get() = error("ReviewRepository is not exercised by the ledger retrieval surface.")
      override val learnings: LearningRepository
        get() = error("LearningRepository is not exercised by the ledger retrieval surface.")
      override val lifecycleTelemetry: LifecycleTelemetryRepository
        get() = error("LifecycleTelemetryRepository is not exercised by the ledger retrieval surface.")
      override val telemetryReconciliation: TelemetryReconciliationRepository
        get() = error("TelemetryReconciliationRepository is not exercised by the ledger retrieval surface.")
      override val telemetryOutbox: TelemetryOutboxRepository
        get() = error("TelemetryOutboxRepository is not exercised by the ledger retrieval surface.")
      override val workflowStates: WorkflowStateRepository
        get() =
          workflowStateRepository
            ?: error("WorkflowStateRepository is not exercised by the ledger retrieval surface.")
      override val workList: WorkListRepository
        get() = error("WorkListRepository is not exercised by the ledger retrieval surface.")
      override val goalPlanningPreparations: GoalPlanningPreparationRepository
        get() = error("GoalPlanningPreparationRepository is not exercised by the ledger retrieval surface.")
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}
