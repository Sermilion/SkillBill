package skillbill.engine.featuretask.lifecycle.execution

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalPlanningPreparationPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.goalplanning.GoalPlanningMigration
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.engine.migration.RuntimeMigrationReceipt
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionError
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.error.featuretask.FeatureTaskRuntimeRegenerationRefusal
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.UnsafeFeatureTaskRuntimeRegenerationError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseExecutionOrigin
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

private const val ADMISSION_WORKFLOW_LABEL_LIMIT = 128

@Inject
class FeatureTaskRuntimeExecutionAdmission(
  private val compatibility: FeatureTaskRuntimeExecutionPlanCompatibility,
  private val diagnostics: RuntimeDiagnostics,
  private val phaseOutputMigration: FeatureTaskRuntimePhaseOutputMigration,
  private val planningMigration: GoalPlanningMigration,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
) {
  fun admit(
    states: WorkflowStateRepository,
    workflowId: String,
    inputs: EffectiveGatePolicyInputs,
    expectedIdentity: FeatureTaskExecutionIdentity? = null,
    requestedReviewSelection: RuntimeReviewSelection? = null,
  ): AdmittedFeatureTaskRuntimeExecution =
    admitStored(states, AdmissionRequest(workflowId, inputs, expectedIdentity, requestedReviewSelection), null)

  fun admit(
    session: GoalRunnerPersistenceSession,
    workflowId: String,
    inputs: EffectiveGatePolicyInputs,
    expectedIdentity: FeatureTaskExecutionIdentity? = null,
    requestedReviewSelection: RuntimeReviewSelection? = null,
  ): AdmittedFeatureTaskRuntimeExecution =
    admitStored(
      session.workflowStates,
      AdmissionRequest(workflowId, inputs, expectedIdentity, requestedReviewSelection),
      session,
    )

  private fun admitStored(
    states: WorkflowStateRepository,
    request: AdmissionRequest,
    session: GoalRunnerPersistenceSession?,
  ): AdmittedFeatureTaskRuntimeExecution =
    try {
      val workflowId = request.workflowId
      val inputs = request.inputs
      val expectedIdentity = request.expectedIdentity
      val requestedReviewSelection = request.requestedReviewSelection
      val identity =
        states.getFeatureTaskExecutionIdentity(workflowId)
          ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "missing immutable execution identity")
      FeatureTaskExecutionIdentityPolicy.validate(identity)
      val goalMigration = migrateGoalImport(states, session, identity)
      val row =
        states.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
          ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "missing workflow")
      requireMatchingIdentity(identity, row, workflowId, expectedIdentity)
      val checkedInputs = inputs.frozen()
      val initialSnapshot = row.toSnapshot()
      val ownsGoalPlanningImport =
        identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD &&
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.contains(initialSnapshot.artifacts)
      val artifacts = initialSnapshot.artifacts
      val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(artifacts)
      val plan =
        compatibility.requireSupportedExecution(
          descriptor?.let { JsonCodec.valueToJsonString(it).toByteArray(Charsets.UTF_8) },
          checkedInputs,
          onMapping = { recordMapping(workflowId) },
        )
      requireMatchingPlan(plan, identity, requestedReviewSelection)
      requireCompletedGateOutputEvidence(artifacts, plan)
      val phaseOutputs = migratePhaseOutputs(request, initialSnapshot, ownsGoalPlanningImport)
      if (phaseOutputs.migratedVersions.isNotEmpty()) {
        requireStoppedMigrationOwner(states, workflowId)
        val patch =
          mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(phaseOutputs.records))
        val migratedArtifacts = initialSnapshot.artifacts + patch
        states.migrateFeatureTaskArtifacts(row, JsonCodec.valueToJsonString(migratedArtifacts))
      }
      val receipt = preferredReceipt(phaseOutputs.receipt, goalMigration)
      AdmittedFeatureTaskRuntimeExecution(
        identity,
        plan,
        checkedInputs,
        requireNotNull(descriptor),
        receipt,
      )
    } catch (error: FeatureTaskRuntimeExecutionPlanAdmissionError) {
      warn(request.workflowId, error.reasonCode)
      throw error
    } catch (error: InvalidFeatureTaskExecutionIdentitySchemaError) {
      warn(request.workflowId, "invalid_route_identity")
      throw error
    } catch (error: UnsafeFeatureTaskRuntimeRegenerationError) {
      warn(request.workflowId, error.refusal.wireValue)
      throw error
    } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
      val refusal =
        SkillBillRuntimeException(
          FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT,
          "Persisted phase output failed its declared contract. Restore or repair the identified record and retry.",
          error,
        )
      recordMigrationFailure(refusal, null)
      throw refusal
    } catch (error: SkillBillRuntimeException) {
      recordMigrationFailure(error, request.failureFacts)
      throw error
    }

  private fun requireStoppedMigrationOwner(
    states: WorkflowStateRepository,
    workflowId: String,
  ) {
    val worker = states.getFeatureTaskRuntimeWorkerOwnership(workflowId) ?: return
    if (supervisor.inspect(worker) != FeatureTaskRuntimeProcessInspection.NotRunning) {
      throw SkillBillRuntimeException(
        FeatureTaskRuntimeMigrationFailureCode.STALE_SOURCE,
        "Phase-output migration requires the previous worker to have stopped. " +
          "Stop the original worker and retry without resetting saved state.",
      )
    }
  }

  private fun preferredReceipt(
    phaseReceipt: RuntimeMigrationReceipt?,
    goalMigration: RuntimeMigrationReceipt,
  ): RuntimeMigrationReceipt =
    phaseReceipt?.takeIf { it.result == RuntimeMigrationReceipt.Result.CONVERTED }
      ?: goalMigration.takeIf { it.result == RuntimeMigrationReceipt.Result.CONVERTED }
      ?: phaseReceipt
      ?: goalMigration

  private fun requireMatchingPlan(
    plan: ResolvedPhaseExecutionPlan,
    identity: FeatureTaskExecutionIdentity,
    requestedReviewSelection: RuntimeReviewSelection?,
  ) {
    if (requestedReviewSelection != null && plan.reviewSelection != requestedReviewSelection) {
      throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
    if (plan.definitionId != SkeletonDefinition.forRun(identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD).id) {
      throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
  }

  private fun recordMigrationFailure(
    error: SkillBillRuntimeException,
    facts: MigrationFailureFacts?,
  ) {
    val migrationCode = error.code as? FeatureTaskRuntimeMigrationFailureCode
    if (migrationCode != null) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=feature_task_phase_output_migration " +
          "source_version=${facts?.sourceVersion ?: "unknown"} " +
          "target_version=${facts?.targetVersion ?: FEATURE_TASK_RUNTIME_CONTRACT_VERSION} " +
          "result=${facts?.result ?: migrationCode.name.lowercase()}",
      )
    }
  }

  private data class AdmissionRequest(
    val workflowId: String,
    val inputs: EffectiveGatePolicyInputs,
    val expectedIdentity: FeatureTaskExecutionIdentity?,
    val requestedReviewSelection: RuntimeReviewSelection?,
  ) {
    var failureFacts: MigrationFailureFacts? = null
  }

  private data class MigrationFailureFacts(
    val sourceVersion: String,
    val targetVersion: String,
    val result: String,
  )

  private fun migrateGoalImport(
    states: WorkflowStateRepository,
    session: GoalRunnerPersistenceSession?,
    identity: FeatureTaskExecutionIdentity,
  ): RuntimeMigrationReceipt {
    val before = states.getFeatureTaskWorkflowAsMode(identity.workflowId, FeatureTaskWorkflowMode.RUNTIME)
    val imported =
      before?.toSnapshot()?.artifacts?.let {
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.value(it) as? Map<*, *>
      }
    return if (imported != null) {
      val parentId =
        imported[GoalPlanningPreparationPayloadKeys.PARENT_GOAL_WORKFLOW_ID] as? String
          ?: refuseImport(
            "Goal import has no parent ownership. Restore the original import before resuming.",
          )
      planningMigration.migrate(
        session ?: refuseImport(
          "Coupled planning migration requires the owning persistence session. Resume the parent goal.",
        ),
        parentId,
        identity.repositoryIdentity,
        identity.normalizedIssueKey,
        requirePreparation = true,
      )
    } else {
      RuntimeMigrationReceipt(
        "unknown",
        FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        RuntimeMigrationReceipt.Result.CURRENT,
      )
    }
  }

  private fun refuseImport(reason: String): Nothing =
    throw SkillBillRuntimeException(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT, reason)

  private fun requireMatchingIdentity(
    identity: FeatureTaskExecutionIdentity,
    row: WorkflowStateRecord,
    workflowId: String,
    expected: FeatureTaskExecutionIdentity?,
  ) {
    if (WorkflowStatus.fromWire(row.workflowStatus)?.let { it in WorkflowStatus.terminalStatuses } == true) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "terminal workflow cannot be admitted")
    }
    val matchingRow =
      identity.workflowId == workflowId && identity.mode == FeatureTaskWorkflowMode.RUNTIME &&
        identity.normalizedIssueKey == row.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey)
    val matchingExpected = expected == null || identity == expected
    if (!matchingRow || !matchingExpected) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "execution identity changed")
    }
  }

  private fun migratePhaseOutputs(
    request: AdmissionRequest,
    snapshot: WorkflowStateSnapshot,
    ownsGoalPlanningImport: Boolean,
  ): MigratedPhaseOutputs {
    val workflowId = request.workflowId
    val records = decodePhaseRecords(snapshot.artifacts)
    val migratedVersions = linkedSetOf<Pair<String, String>>()
    var hasCurrentOutput = false
    val rawRecords =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(snapshot.artifacts) as? Map<*, *>
        ?: if (!DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.contains(snapshot.artifacts)) {
          return MigratedPhaseOutputs(emptyMap(), emptySet(), null)
        } else {
          throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
            workflowId,
            "phase records do not have the persisted map shape",
          )
        }
    val migrated =
      rawRecords.entries.associate { (phase, record) -> phase.toString() to record }.toMutableMap()
    records.forEach { (phaseId, record) ->
      val output = record.outputArtifact ?: return@forEach
      if (!requiresOutputAdmission(output, record.phaseId, phaseId)) return@forEach
      when (val result = phaseOutputMigration.migrate(output)) {
        is FeatureTaskRuntimePhaseOutputMigrationResult.Current -> hasCurrentOutput = true
        is FeatureTaskRuntimePhaseOutputMigrationResult.Migrated -> {
          val hydrated = record.executionOrigin == FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED
          if (ownsGoalPlanningImport && hydrated &&
            phaseId in setOf(GoalPlanningSweepConstants.PHASE_PREPLAN, GoalPlanningSweepConstants.PHASE_PLAN)
          ) {
            throw SkillBillRuntimeException(
              FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT,
              "Imported planning output is not coherent with its parent checkpoint. Resume the parent goal.",
            )
          }
          migratedVersions += result.sourceVersion to result.targetVersion
          migrated[phaseId] = patchOutput(rawRecords[phaseId], result.payload, "$workflowId#$phaseId")
        }
        is FeatureTaskRuntimePhaseOutputMigrationResult.Refused ->
          refuseMigration(request, phaseId, result)
      }
    }
    return MigratedPhaseOutputs(migrated, migratedVersions, phaseOutputReceipt(migratedVersions, hasCurrentOutput))
  }

  private fun phaseOutputReceipt(
    migratedVersions: Set<Pair<String, String>>,
    hasCurrentOutput: Boolean,
  ): RuntimeMigrationReceipt? =
    migratedVersions.singleOrNull()?.let { (source, target) ->
      RuntimeMigrationReceipt(source, target, RuntimeMigrationReceipt.Result.CONVERTED)
    } ?: if (hasCurrentOutput) {
      RuntimeMigrationReceipt(
        FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        RuntimeMigrationReceipt.Result.CURRENT,
      )
    } else {
      null
    }

  private fun patchOutput(
    raw: Any?,
    payload: String,
    label: String,
  ): Map<String, Any?> {
    val record =
      raw as? Map<*, *> ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        label,
        "outer phase record cannot be patched without changing its stored fields",
      )
    return record.entries.associate { (key, value) -> key.toString() to value } +
      (SharedPayloadKeys.OUTPUT_ARTIFACT to payload)
  }

  private fun requiresOutputAdmission(
    output: String,
    storedPhase: String,
    phaseId: String,
  ): Boolean {
    if (storedPhase != phaseId) {
      invalidPhaseOutputIdentity()
    }
    val envelope = JsonCodec.parseObjectOrNull(output)
    val version = envelope?.get(SharedPayloadKeys.CONTRACT_VERSION)?.let(JsonCodec::jsonElementToValue)
    if (version == FEATURE_TASK_RUNTIME_CONTRACT_VERSION) return false
    if (version == null && !output.contains("\"${SharedPayloadKeys.CONTRACT_VERSION}\"")) return false
    if (envelope != null && envelope[SharedPayloadKeys.PHASE_ID]?.let(JsonCodec::jsonElementToValue) != phaseId) {
      invalidPhaseOutputIdentity()
    }
    return true
  }

  private fun invalidPhaseOutputIdentity(): Nothing =
    throw SkillBillRuntimeException(
      FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT,
      "Stored phase output identity does not match its owning phase record. Restore the original record and retry.",
    )

  private fun refuseMigration(
    request: AdmissionRequest,
    phaseId: String,
    refusal: FeatureTaskRuntimePhaseOutputMigrationResult.Refused,
  ): Nothing {
    val workflowId = request.workflowId
    val code =
      when (refusal.result) {
        FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.UNSUPPORTED ->
          FeatureTaskRuntimeMigrationFailureCode.SOURCE_UNSUPPORTED
        FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.CORRUPT ->
          FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT
        FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.NON_CONVERTIBLE ->
          FeatureTaskRuntimeMigrationFailureCode.TARGET_NON_CONVERTIBLE
      }
    val sourceVersion =
      refusal.sourceVersion
        ?.takeIf { it.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}")) } ?: "unknown"
    val guidance =
      when (refusal.result) {
        FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.UNSUPPORTED ->
          "Use a compatible runtime or an explicitly reviewed supported mapping."
        FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.CORRUPT ->
          "Restore or repair this phase record and its digest before resuming."
        FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.NON_CONVERTIBLE ->
          "Recover the original evidence or authorize recovery for unfinished work. Do not replay completed work."
      }
    request.failureFacts =
      MigrationFailureFacts(
        sourceVersion,
        refusal.targetVersion ?: "unknown",
        refusal.result.name.lowercase(),
      )
    throw SkillBillRuntimeException(
      code,
      "Feature-task phase output '$workflowId#$phaseId' could not migrate from " +
        "'$sourceVersion' to '${refusal.targetVersion ?: "unknown"}'. $guidance",
    )
  }

  private data class MigratedPhaseOutputs(
    val records: Map<String, Any?>,
    val migratedVersions: Set<Pair<String, String>>,
    val receipt: RuntimeMigrationReceipt?,
  )

  fun requireCompatibleDescriptor(
    states: WorkflowStateRepository,
    workflowId: String,
    expected: ValidatedFeatureTaskRuntimeExecutionPlan?,
  ): ResolvedPhaseExecutionPlan =
    try {
      val row = states.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
      val descriptor =
        row?.toSnapshot()?.artifacts?.let {
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(it)
        }
      compatibility.requireCompatibleExecution(
        descriptor?.let { JsonCodec.valueToJsonString(it).toByteArray(Charsets.UTF_8) },
        expected,
      )
    } catch (error: FeatureTaskRuntimeExecutionPlanAdmissionError) {
      warn(workflowId, error.reasonCode)
      throw error
    }

  fun recordTransactionOutcome(
    receipt: RuntimeMigrationReceipt,
    committed: Boolean,
  ) {
    val result =
      when {
        receipt.result == RuntimeMigrationReceipt.Result.CURRENT -> "current_or_absent"
        committed -> "committed"
        else -> "rolled_back"
      }
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=feature_task_phase_output_migration source_version=${receipt.sourceVersion} " +
        "target_version=${receipt.targetVersion} result=$result",
    )
  }

  private fun recordMapping(workflowId: String) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Execution plan checked semantic mapping workflow=${workflowId.take(ADMISSION_WORKFLOW_LABEL_LIMIT)}; " +
        "original descriptor and evidence retained",
    )
  }

  private fun warn(
    workflowId: String,
    reason: String,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Execution admission refused workflow=${workflowId.take(ADMISSION_WORKFLOW_LABEL_LIMIT)} reason=$reason",
    )
  }
}

internal fun requireCompletedGateOutputEvidence(
  artifacts: Map<String, Any?>,
  plan: ResolvedPhaseExecutionPlan,
) {
  val gateSteps =
    plan.selectedStrategies
      .filter { it.slot == PhaseSlot.QUALITY_GATE }
      .flatMapTo(mutableSetOf()) { it.steps }
  if (gateSteps.isEmpty()) return
  val records = decodePhaseRecords(artifacts).values
  val completedGateRecords =
    records.filter { record ->
      record.phaseId in gateSteps && record.status == WorkflowStepStatus.COMPLETED
    }
  val missingCompletedOutput =
    completedGateRecords.any { record ->
      record.outputArtifact == null
    }
  val inconsistentCompletedOutput =
    completedGateRecords.any { record ->
      val outputStatus =
        record.outputArtifact
          ?.let(JsonCodec::parseObjectOrNull)
          ?.get(SharedPayloadKeys.STATUS)
          ?.let(JsonCodec::jsonElementToValue) as? String
      outputStatus?.let { WorkflowStepStatus.fromWire(it) }
        ?.let { it != WorkflowStepStatus.COMPLETED } == true
    }
  if (missingCompletedOutput || inconsistentCompletedOutput) {
    throw UnsafeFeatureTaskRuntimeRegenerationError(
      if (missingCompletedOutput) {
        FeatureTaskRuntimeRegenerationRefusal.MISSING_PRODUCER_EVIDENCE
      } else {
        FeatureTaskRuntimeRegenerationRefusal.UNPROVEN_GATE_SEMANTICS
      },
    )
  }
}
