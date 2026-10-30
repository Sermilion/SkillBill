package skillbill.engine.diagnostics.block

import skillbill.engine.diagnostics.block.investigation.BlockIncidentAgentOutputParser
import skillbill.engine.diagnostics.block.investigation.BlockIncidentAgentPass
import skillbill.engine.diagnostics.block.investigation.BlockIncidentEvidenceCollector
import skillbill.engine.diagnostics.block.investigation.EvidenceBlockIncidentInvestigator
import skillbill.install.model.SupportedAgent
import skillbill.model.RepositoryRoot
import skillbill.model.RuntimeVersion
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.passThroughResolvedLaunchModel
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.DatabasePathProtectionResult
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.UnavailableRejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.model.ExactOutputRead
import skillbill.ports.diagnostics.model.ExactOutputReadRequest
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowStateRepositoryDefaults
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDegradation
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisEvidenceKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceProvenance
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReference
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReferenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFailure
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentIdentityAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentInvestigatorKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReasonRetention
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReport
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentResponsibleCategory
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentScope
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentState
import skillbill.workflow.taskruntime.model.blockincident.validation
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvidenceBlockIncidentInvestigatorTest {
  @Test
  fun `no recorded agent settles unavailable agent and keeps the deterministic record`() {
    val launches = mutableListOf<AgentRunLaunchRequest>()
    val result =
      investigator(launcher(launches) { error("unreachable") }, UnavailableRejectedOutputDiagnosticRepository)
        .investigate(incident(evidenceReferences = emptyList()), emptySet())

    assertEquals(BlockIncidentDiagnosis.UNAVAILABLE_AGENT, result.diagnosis)
    assertTrue(launches.isEmpty())
    assertEquals(BlockIncidentResponsibleCategory.UNRESOLVED, result.record.responsibleCategory)
    assertEquals(BlockIncidentInvestigatorKind.DETERMINISTIC, result.record.investigatorKind)
    assertTrue(BlockIncidentDegradation.AGENT_UNAVAILABLE in result.record.degradations)
    assertTrue(BlockIncidentDegradation.EVIDENCE_UNAVAILABLE in result.record.degradations)
    val workflowState = result.record.evidence.single { it.kind == BlockIncidentDiagnosisEvidenceKind.WORKFLOW_STATE }
    assertTrue(workflowState.summary.contains(BlockIncidentDiagnosisEvidenceKind.WORKFLOW_STATE.wireValue))
    assertTrue(result.record.validation().isEmpty())
  }

  @Test
  fun `timed out agent keeps the classifier category and oversized producer output keeps its full digest`() {
    val launches = mutableListOf<AgentRunLaunchRequest>()
    val payload = "x".repeat(10 * 1024)
    val outputs = producerOutputs(payload)
    val result =
      investigator(
        launcher(launches) { agentRunLaunchFacts(SupportedAgent.CLAUDE, AgentRunTermination.TimedOut) },
        outputs,
      ).investigate(incident(evidenceReferences = listOf(producerReference(sha256HexUtf8(payload)))), emptySet())

    assertEquals(BlockIncidentDiagnosis.PARTIAL, result.diagnosis)
    assertEquals(BlockIncidentResponsibleCategory.SKILL_BILL_DEFECT, result.record.responsibleCategory)
    assertEquals(BlockIncidentInvestigatorKind.DETERMINISTIC_WITH_AGENT, result.record.investigatorKind)
    assertTrue(
      result.record.degradations.containsAll(
        setOf(
          BlockIncidentDegradation.AGENT_TIMED_OUT,
          BlockIncidentDegradation.READ_ONLY_NOT_ENFORCED,
          BlockIncidentDegradation.EVIDENCE_TRUNCATED,
        ),
      ),
    )
    val producer = result.record.evidence.single { it.kind == BlockIncidentDiagnosisEvidenceKind.PRODUCER_OUTPUT }
    assertEquals(sha256HexUtf8(payload), producer.sha256)
    val request = launches.single().skillRunRequest
    assertTrue(request.readOnlyPhase)
    assertEquals(listOf(STORE_ROOT), request.additionalWritableDirectories)
    assertTrue(assertNotNull(request.promptOverride).contains(producer.reference))
  }

  @Test
  fun `agent output parser accepts only the two fields citing collected references`() {
    val known = setOf("workflow_state:workflow")

    assertEquals(
      "missing credentials",
      BlockIncidentAgentOutputParser.parse(
        """{"suspected_cause":"missing credentials","evidence_refs":["workflow_state:workflow"]}""",
        known,
      ),
    )
    assertNull(
      BlockIncidentAgentOutputParser.parse(
        """{"suspected_cause":"missing credentials","evidence_refs":["workflow_state:other"]}""",
        known,
      ),
    )
    assertNull(
      BlockIncidentAgentOutputParser.parse(
        """{"suspected_cause":"x","evidence_refs":["workflow_state:workflow"],"responsible_category":"x"}""",
        known,
      ),
    )
    assertNull(BlockIncidentAgentOutputParser.parse("not json", known))
  }

  private fun investigator(
    launcher: AgentRunLauncher,
    outputs: RejectedOutputDiagnosticRepository,
  ) = EvidenceBlockIncidentInvestigator(
    FakeDatabase(outputs),
    CLOCK,
    BlockIncidentEvidenceCollector(RuntimeVersion("9.9.9")),
    BlockIncidentAgentPass(
      launcher,
      RepositoryRoot(Path.of("/tmp/repository")),
      CLOCK,
      FixedProjectStorePort(STORE_ROOT),
    ),
    launchFactsReader = null,
  )

  private fun launcher(
    launches: MutableList<AgentRunLaunchRequest>,
    outcome: () -> AgentRunLaunchOutcome,
  ) = object : AgentRunLauncher {
    override fun resolveLaunchModel(request: AgentRunLaunchModelRequest) = passThroughResolvedLaunchModel(request)

    override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
      launches += request
      return outcome()
    }
  }

  private fun producerOutputs(payload: String): RejectedOutputDiagnosticRepository =
    object : RejectedOutputDiagnosticRepository by UnavailableRejectedOutputDiagnosticRepository {
      override fun readProducerOutputExact(request: ExactOutputReadRequest): ExactOutputRead {
        assertEquals(
          ExactOutputReadRequest.Producer(
            workflowId = "workflow",
            phaseId = "implement",
            attempt = 1,
            agentId = "claude",
            generation = 1,
            repairTurn = 0,
            expectedDigest = sha256HexUtf8(payload),
            expectedByteSize = payload.encodeToByteArray().size.toLong(),
            retainedIdentity = "workflow:implement:1:1:0:claude",
          ),
          request,
        )
        return ExactOutputRead.Found(payload.encodeToByteArray(), "claude")
      }
    }

  private fun producerReference(digest: String) =
    BlockIncidentEvidenceReference(
      source = "producer_output",
      reference = "workflow:implement:1:1:0:claude",
      digest = digest,
      availability = BlockIncidentEvidenceReferenceAvailability.AVAILABLE,
      kind = BlockIncidentEvidenceKind.PRODUCER_OUTPUT,
      provenance = BlockIncidentEvidenceProvenance.ORIGINAL_PRODUCER_OUTPUT,
      sourceIdentity = "claude",
      outputGeneration = 1,
      repairTurn = 0,
      byteSize = 10 * 1024L,
      retainedReferenceId = "workflow:implement:1:1:0:claude",
    )

  private fun incident(evidenceReferences: List<BlockIncidentEvidenceReference>) =
    BlockIncident(
      BlockIncidentOccurrence(
        occurrenceToken = "bi_${"e".repeat(64)}",
        scope = BlockIncidentScope(workflowId = "workflow", phaseId = "implement", attempt = 1, generation = 1),
        failure =
          BlockIncidentFailure(
            origin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
            reason = "output rejected",
            reasonRetention = BlockIncidentReasonRetention.COMPLETE,
            failureDisposition = "process_failure",
            exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
          ),
        observedAt = NOW,
        evidenceAvailability = BlockIncidentEvidenceAvailability.DATABASE,
        identityAvailability = BlockIncidentIdentityAvailability.PARTIAL,
        fieldAvailability = emptyMap(),
        retainedEvidenceReferences = evidenceReferences,
      ),
      listOf(BlockIncidentAlias(BlockIncidentAliasKind.PHASE_TRANSITION, "workflow:implement:1")),
      BlockIncidentState(
        BlockIncidentDiagnosis.INVESTIGATING,
        BlockIncidentRecovery.UNAVAILABLE,
        BlockIncidentReport.UNAVAILABLE,
        2,
        NOW.plusSeconds(300),
        0,
        null,
        null,
      ),
    )

  private class FakeDatabase(private val outputs: RejectedOutputDiagnosticRepository) : DatabaseSessionFactory {
    override fun acquireDatabasePathProtection() =
      DatabasePathProtectionResult.Unavailable(
        "Database path protection is not available.",
      )

    override fun resolveDbPath(): Path = Path.of("/tmp/block-incidents.db")

    override fun databaseExists(): Boolean = true

    override fun <T> read(block: (UnitOfWork) -> T): T = block(unit())

    override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unit())

    override fun <T> owningTransaction(block: (UnitOfWork) -> T): OwningTransactionResult<T> =
      error("Owning transactions are not exercised.")

    override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = block(unit())

    private fun unit(): UnitOfWork =
      object : UnitOfWorkDefaults() {
        override val dbPath: Path = resolveDbPath()
        override val rejectedOutputDiagnostics: RejectedOutputDiagnosticRepository = outputs
        override val workflowStates: WorkflowStateRepository = object : WorkflowStateRepositoryDefaults() {}
        override val learnings: LearningRepository
          get() = error("Learnings are not exercised.")
        override val reviews: ReviewRepository
          get() = error("Reviews are not exercised.")
        override val lifecycleTelemetry: LifecycleTelemetryRepository
          get() = error("Lifecycle telemetry is not exercised.")
        override val telemetryReconciliation: TelemetryReconciliationRepository
          get() = error("Telemetry reconciliation is not exercised.")
        override val telemetryOutbox: TelemetryOutboxRepository
          get() = error("Telemetry outbox is not exercised.")
        override val workList: WorkListRepository
          get() = error("Work list is not exercised.")
        override val goalPlanningPreparations: GoalPlanningPreparationRepository
          get() = error("Goal planning preparations are not exercised.")
        override val goalRunnerControls: GoalRunnerControlRepository
          get() = error("Goal runner controls are not exercised.")
      }
  }

  private companion object {
    val NOW: Instant = Instant.parse("2026-10-22T12:00:00Z")
    val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    val STORE_ROOT: Path = Path.of("/tmp/project-store")
  }
}
