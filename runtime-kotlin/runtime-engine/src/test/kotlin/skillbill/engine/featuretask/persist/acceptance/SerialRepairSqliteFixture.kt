package skillbill.engine.featuretask.persist.acceptance

import skillbill.agent.model.PhaseOutput
import skillbill.application.testProjectStore
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
import skillbill.engine.featuretask.lifecycle.core.absentDatabaseCheckpoint
import skillbill.engine.featuretask.lifecycle.execution.ArchivedAuditExecutionPlanFixture
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairAuditEvidence
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairCommit
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.engine.featuretask.model.audit.ExpectedAcceptanceCriterionRepairState
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.phase.core.InMemoryFeatureTaskPhaseSettlementRepository
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.core.testPhaseSettlementService
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseStateRecorder
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.infrastructure.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.infrastructure.contracts.workflow.featuretask.wire.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.AcceptanceCriterionRepairProjection
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.AcceptanceCriterionRepairProjectionRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

internal class SerialRepairSqliteFixture(
  archiveRevision: Int? = null,
  supervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  archiveMode: String = "goal-child-validate",
) : AutoCloseable {
  val root: Path = Files.createTempDirectory("serial-repair-sqlite")
  val clock: Clock = Clock.fixed(Instant.parse("2026-10-14T09:00:00Z"), ZoneOffset.UTC)
  val database = phaseRunDatabase(root, clock)
  val execution =
    ExecutionPlanAdmissionFixture(
      database = database,
      definition =
        if (archiveRevision == null) {
          if (archiveMode == "standalone") SkeletonDefinition.STANDALONE else SkeletonDefinition.GOAL_CHILD
        } else {
          if (archiveMode == "standalone") {
            SkeletonDefinition.STANDALONE_REVISION_2
          } else {
            SkeletonDefinition.GOAL_CHILD_REVISION_2
          }
        },
      qualityGate =
        when (archiveMode) {
          "goal-child-build" -> FeatureTaskRuntimeQualityGateSelection.BUILD
          "goal-child-validate" -> FeatureTaskRuntimeQualityGateSelection.VALIDATE
          else -> null
        },
    )
  val workflowId = "wftr-serial-repair"
  val binding =
    AcceptanceCriterionRepairBinding(
      sha256Hex(JsonCodec.valueToJsonString(listOf("first", "second"))),
      workflowId,
      "a".repeat(64),
      listOf("first", "second"),
      setOf("audit", "audit_plan_fix", "audit_implement_fix"),
    )
  val admission =
    AcceptanceCriterionRepairAdmission(
      binding.catalogId,
      workflowId,
      binding.specDigest,
      binding.authoredCriteria,
      binding.authoredCriteria.mapIndexed { index, text ->
        AcceptanceCriterionRepairCriterion(
          "AC-%03d".format(index + 1),
          null,
          text,
          index + 1,
          if (index == 0) {
            AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED
          } else {
            AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
          },
          AcceptanceCriterionValidationObligation.DEFERRED,
          true,
          "discovery:$index",
        )
      },
      emptyList(),
    )
  val initial = assertNotNull(AcceptanceCriterionRepairState.admit(admission))
  var sourceDigest = binding.specDigest
  val source =
    object : FeatureTaskRuntimeRunInvariantsSource {
      override fun read(specPath: Path) =
        FeatureTaskRuntimeRunInvariantsRead.Read(
          FeatureTaskRuntimeRunInvariants(
            specPath.toString(),
            acceptanceCriteria =
              binding
                .authoredCriteria,
            mandatesAndOverrides = emptyList(),
          ),
          sourceDigest,
        )
    }
  val warnings = mutableListOf<String>()
  internal val diagnostics =
    object : RuntimeDiagnostics {
      override fun info(message: String) = Unit

      override fun warning(
        message: String,
        error: Throwable?,
      ) {
        warnings += message
      }

      override fun error(
        message: String,
        error: Throwable?,
      ) {
        warnings += message
      }
    }
  var projections = 0
  var project: (AcceptanceCriterionRepairProjectionRequest) -> Unit = {}
  private val projection =
    object : AcceptanceCriterionRepairProjection {
      override fun write(request: AcceptanceCriterionRepairProjectionRequest) {
        projections++
        project(request)
      }
    }
  val persistence = service()
  val coordinator =
    FeatureTaskRuntimeWorkerCoordinator(
      database,
      supervisor,
      clock,
      execution.admission,
      NoopRuntimeDiagnostics,
      absentDatabaseCheckpoint,
    )

  init {
    val descriptor =
      if (archiveRevision == null) {
        execution.descriptor()
      } else {
        execution.validator.read(
          ArchivedAuditExecutionPlanFixture.legacyAuditPlan(execution, archiveRevision),
          "archive",
        )
      }
    database.transaction { execution.seed(it.workflowStates, workflowId, descriptor = descriptor) }
  }

  fun owned(block: (AcquiredFeatureTaskRuntimeWorker) -> Unit) =
    coordinator.runOwned(workflowId, execution.inputs, execution.identity(workflowId)) { _, worker -> block(worker) }

  val workflows: FeatureTaskRuntimeWorkflowPersistence
    get() =
      FeatureTaskRuntimeWorkflowPersistence(
        database,
        WorkflowStateSchemaValidator(),
        FeatureTaskRuntimeWireArtifactValidator(execution.validator, ContractFeatureTaskRuntimePhaseOutputMigration()),
      )

  fun service(
    db: DatabaseSessionFactory = database,
    gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  ): AcceptanceCriterionRepairPersistence {
    val phaseOutputs = ContractFeatureTaskRuntimePhaseOutputMigration()
    val validator = FeatureTaskRuntimeWireArtifactValidator(execution.validator, phaseOutputs)
    val snapshots = WorkflowStateSchemaValidator()
    val workflowPersistence = FeatureTaskRuntimeWorkflowPersistence(db, snapshots, validator)
    return AcceptanceCriterionRepairPersistence(
      RuntimeOwnedPersistenceBoundary(db, diagnostics),
      workflowPersistence,
      FeatureTaskRuntimePhaseStateRecorder(
        db,
        workflowPersistence,
        RuntimeOwnedPersistenceBoundary(db, diagnostics),
        validator,
        testPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), clock),
        clock,
      ),
      validator,
      projection,
      diagnostics,
      clock,
      AcceptanceCriterionRepairLegacyAdoption(
        execution.codec,
        execution.compatibility,
        phaseOutputs,
        gitOperations,
        source,
        testProjectStore,
      ),
      AcceptanceCriterionRepairAuditAdmission(validator),
    )
  }

  fun snapshot(): WorkflowStateSnapshot =
    database.read {
      assertNotNull(
        it.workflowStates.get(
          WorkflowFamily.TASK_RUNTIME,
          workflowId,
        ),
      )
    }

  fun write(
    worker: AcquiredFeatureTaskRuntimeWorker,
    state: AcceptanceCriterionRepairState?,
    mutation: AcceptanceCriterionRepairMutation,
    evidence: AcceptanceCriterionRepairAuditEvidence? = null,
    service: AcceptanceCriterionRepairPersistence = persistence,
  ): AcceptanceCriterionRepairWriteResult =
    service.write(
      worker,
      binding,
      AcceptanceCriterionRepairCommit(
        ExpectedAcceptanceCriterionRepairState(
          state?.revision,
          state?.currentCriterionRef,
          state?.reservation?.reservationId,
        ),
        mutation,
        phase(mutation),
        evidence,
      ),
      root,
    )

  fun discover(worker: AcquiredFeatureTaskRuntimeWorker): AcceptanceCriterionRepairState =
    assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
      write(
        worker,
        null,
        AcceptanceCriterionRepairMutation.Discover(admission),
        audit(),
      ),
    ).state

  fun prepareRepair(
    worker: AcquiredFeatureTaskRuntimeWorker,
    state: AcceptanceCriterionRepairState,
    reservationId: String,
  ): AcceptanceCriterionRepairState {
    val reserved =
      assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
        write(
          worker,
          state,
          AcceptanceCriterionRepairMutation.Reserve(reservationId),
        ),
      ).state
    return assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
      write(
        worker,
        reserved,
        AcceptanceCriterionRepairMutation.RepairCompleted(
          reservationId,
          "repair:$reservationId",
          "d".repeat(64),
          "checkpoint:1",
        ),
      ),
    ).state
  }

  fun phase(mutation: AcceptanceCriterionRepairMutation): FeatureTaskRuntimePhaseStateRequest {
    val mutating = mutation is AcceptanceCriterionRepairMutation.RepairCompleted
    val id = if (mutating || mutation is AcceptanceCriterionRepairMutation.Reserve) "audit_implement_fix" else "audit"
    val finished =
      mutation !is AcceptanceCriterionRepairMutation.Discover &&
        mutation !is AcceptanceCriterionRepairMutation.Reserve
    val priorAttempt = decodePhaseRecords(snapshot().artifacts)[id]?.attemptCount ?: 0
    val attempt =
      if (mutation is AcceptanceCriterionRepairMutation.Reserve) {
        priorAttempt + 1
      } else {
        priorAttempt.coerceAtLeast(1)
      }
    return FeatureTaskRuntimePhaseStateRequest(
      workflowId,
      id,
      if (finished) "completed" else "running",
      attempt,
      "codex",
      finished,
      mutating = mutating,
      normalizedOutput =
        if (mutating) {
          NormalizedFeatureTaskRuntimePhaseOutput(
            id,
            "completed",
            "repair",
            PhaseOutput("source repair evidence"),
          )
        } else {
          null
        },
    )
  }

  fun audit(): AcceptanceCriterionRepairAuditEvidence =
    auditResult(
      AcceptanceCriterionRepairMode.INITIAL_DISCOVERY,
      initial,
      mapOf(
        Keys.TREE_DIGEST to "d".repeat(64),
        Keys.CHECKPOINT_ID to "checkpoint:discovery",
        Keys.PRODUCING_OUTPUT_ID to "discovery",
        Keys.PRODUCING_OUTPUT_DIGEST to "e".repeat(64),
      ),
      initial.criteria.map { it to it.evidenceRef },
    )

  fun audit(
    verifying: AcceptanceCriterionRepairState,
    outcome: AcceptanceCriterionRepairOutcome,
  ): AcceptanceCriterionRepairAuditEvidence {
    val criterion = verifying.criteria.single { it.canonicalRef == outcome.criterionRef }
    val closed =
      if (outcome.successful) {
        criterion.copy(sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED)
      } else {
        criterion
      }
    return auditResult(
      AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED,
      verifying,
      mapOf(
        Keys.TREE_DIGEST to requireNotNull(verifying.admittedTreeDigest),
        Keys.CHECKPOINT_ID to requireNotNull(verifying.admittedCheckpointId),
        Keys.PRODUCING_OUTPUT_ID to outcome.outcomeId,
        Keys.PRODUCING_OUTPUT_DIGEST to outcome.digest,
        Keys.EXPECTED_REVISION to verifying.revision,
        Keys.CURRENT_CRITERION_REF to outcome.criterionRef,
        Keys.RESERVATION_ID to outcome.reservationId,
      ),
      listOf(closed to outcome.evidenceRef),
    )
  }

  private fun auditResult(
    mode: AcceptanceCriterionRepairMode,
    state: AcceptanceCriterionRepairState,
    source: Map<String, Any?>,
    criteria: List<Pair<AcceptanceCriterionRepairCriterion, String>>,
  ) = AcceptanceCriterionRepairAuditEvidence(
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        SharedPayloadKeys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION,
        Keys.MODE to mode.wireValue,
        Keys.CATALOG_ID to state.catalogId,
        Keys.SPEC_DIGEST to state.specDigest,
        Keys.EXECUTION_ID to state.executionId,
        Keys.EXPECTED_REVISION to null,
        Keys.CURRENT_CRITERION_REF to null,
        Keys.RESERVATION_ID to null,
      ) + source +
        mapOf(
          Keys.CRITERIA to
            criteria.map { (criterion, evidenceRef) ->
              mapOf(
                Keys.CANONICAL_REF to criterion.canonicalRef,
                Keys.SOURCE_DISPOSITION to criterion.sourceDisposition.wireValue,
                Keys.VALIDATION_OBLIGATION to criterion.validationObligation.wireValue,
                Keys.VALIDATION_OWNER_AVAILABLE to criterion.validationOwnerAvailable,
                Keys.EVIDENCE_REF to evidenceRef,
              )
            },
          Keys.DEPENDENCIES to state.asRepairWireMap()[Keys.DEPENDENCIES],
          Keys.REGRESSED_REFS to emptyList<String>(),
          Keys.EDGE_CHANGES to emptyList<Any>(),
          Keys.INTERACTIONS to emptyList<Any>(),
          Keys.RECHECKS to emptyList<Any>(),
        ),
    ),
  )

  override fun close() {
    root.toFile().deleteRecursively()
  }
}
