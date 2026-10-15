package skillbill.engine.featuretask.persist.acceptance

import skillbill.agent.model.PhaseOutput
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
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
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseStateRecorder
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.infrastructure.contracts.FeatureTaskRuntimeWireArtifactValidator
import skillbill.infrastructure.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.AcceptanceCriterionRepairProjection
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

internal class SerialRepairSqliteFixture(archiveRevision: Int? = null) : AutoCloseable {
  val root: Path = Files.createTempDirectory("serial-repair-sqlite")
  val clock: Clock = Clock.fixed(Instant.parse("2026-10-14T09:00:00Z"), ZoneOffset.UTC)
  val database = phaseRunDatabase(root, clock)
  val execution = ExecutionPlanAdmissionFixture(database = database)
  val workflowId = "wftr-serial-repair"
  val binding =
    AcceptanceCriterionRepairBinding(
      sha256Hex(JsonCodec.valueToJsonString(listOf("first", "second"))),
      workflowId,
      "a".repeat(64),
      listOf("first", "second"),
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
      NoopFeatureTaskRuntimeWorkerSupervisor,
      clock,
      execution.admission,
    )

  init {
    val descriptor =
      if (archiveRevision == null || archiveRevision == 3) {
        execution.descriptor()
      } else {
        execution.validator.read(
          ArchivedAuditExecutionPlanFixture.archive(execution, "standalone", archiveRevision),
          "archive",
        )
      }
    database.transaction { execution.seed(it.workflowStates, workflowId, descriptor = descriptor) }
  }

  fun owned(block: (AcquiredFeatureTaskRuntimeWorker) -> Unit) =
    coordinator.runOwned(workflowId, execution.inputs, execution.identity(workflowId)) { _, worker -> block(worker) }

  fun service(
    db: DatabaseSessionFactory = database,
    gitOperations: WorkflowGitOperations =
      NoopWorkflowGitOperations,
  ): AcceptanceCriterionRepairPersistence {
    val validator = FeatureTaskRuntimeWireArtifactValidator()
    val snapshots = WorkflowStateSchemaValidator()
    return AcceptanceCriterionRepairPersistence(
      RuntimeOwnedPersistenceBoundary(db, diagnostics),
      FeatureTaskRuntimeWorkflowPersistence(db, snapshots, validator),
      FeatureTaskRuntimePhaseStateRecorder(
        db,
        FeatureTaskRuntimeWorkflowPersistence(db, snapshots, validator),
        RuntimeOwnedPersistenceBoundary(db, diagnostics),
        validator,
        clock,
      ),
      validator,
      projection,
      diagnostics,
      clock,
      AcceptanceCriterionRepairLegacyAdoption(
        execution.codec,
        execution.compatibility,
        ContractFeatureTaskRuntimePhaseOutputMigration(),
        gitOperations,
        source,
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
        audit(
          AcceptanceCriterionRepairMode.INITIAL_DISCOVERY,
        ),
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
    return FeatureTaskRuntimePhaseStateRequest(
      workflowId,
      id,
      "running",
      1,
      "codex",
      false,
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

  fun audit(
    mode: AcceptanceCriterionRepairMode,
    state: AcceptanceCriterionRepairState = initial,
    outputDigest: String = "b".repeat(64),
    outcome: AcceptanceCriterionRepairOutcome? = null,
    regressed: Set<String> = emptySet(),
  ): AcceptanceCriterionRepairAuditEvidence {
    val selected =
      if (mode == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED) {
        state
          .criteria.filter { it.canonicalRef == state.currentCriterionRef }
      } else {
        state.criteria
      }
    return AcceptanceCriterionRepairAuditEvidence(
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        mapOf(
          Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION,
          Keys.MODE to mode.wireValue, Keys.CATALOG_ID to binding.catalogId,
          Keys
            .EXECUTION_ID to workflowId,
          Keys.SPEC_DIGEST to binding.specDigest,
          Keys.TREE_DIGEST to (state.admittedTreeDigest ?: "d".repeat(64)),
          Keys.CHECKPOINT_ID to (
            state
              .admittedCheckpointId ?: "checkpoint:1"
          ),
          Keys.PRODUCING_OUTPUT_ID to (outcome?.outcomeId ?: "audit-output"),
          Keys.PRODUCING_OUTPUT_DIGEST to (
            outcome?.digest ?: outputDigest
          ),
          Keys.EXPECTED_REVISION to
            state.revision.takeIf {
              mode ==
                AcceptanceCriterionRepairMode
                  .CURRENT_CRITERION_TARGETED
            },
          Keys.CURRENT_CRITERION_REF to
            state.currentCriterionRef.takeIf {
              mode == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED
            },
          Keys.RESERVATION_ID to
            state.reservation?.reservationId.takeIf {
              mode == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED
            },
          Keys.CRITERIA to
            selected.map { criterion ->
              mapOf(
                Keys.CANONICAL_REF to criterion.canonicalRef,
                Keys.SOURCE_DISPOSITION to
                  when {
                    criterion.canonicalRef in regressed ->
                      AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED.wireValue
                    outcome?.successful == true -> AcceptanceCriterionSourceDisposition.SOURCE_CLOSED.wireValue
                    else -> criterion.sourceDisposition.wireValue
                  },
                Keys.VALIDATION_OBLIGATION to criterion.validationObligation.wireValue,
                Keys.VALIDATION_OWNER_AVAILABLE to criterion.validationOwnerAvailable,
                Keys
                  .EVIDENCE_REF to (outcome?.evidenceRef ?: criterion.evidenceRef),
              )
            },
          Keys.DEPENDENCIES to state.asRepairWireMap()[Keys.DEPENDENCIES],
          Keys
            .EDGE_CHANGES to emptyList<Any>(),
          Keys.REGRESSED_REFS to regressed.toList(),
        ),
      ),
    )
  }

  override fun close() {
    root.toFile().deleteRecursively()
  }
}
