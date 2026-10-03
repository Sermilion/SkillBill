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
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
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
      if (requestedReviewSelection != null && plan.reviewSelection != requestedReviewSelection) {
        throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
      }
      if (plan.definitionId != SkeletonDefinition.forRun(identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD).id) {
        throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
      }
      requireCompletedGateOutputEvidence(artifacts, plan)
      val phaseOutputs = migratePhaseOutputs(workflowId, initialSnapshot, ownsGoalPlanningImport)
      if (phaseOutputs.migratedVersions.isNotEmpty()) {
        val migratedArtifacts =
          initialSnapshot.artifacts +
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(phaseOutputs.records)
        states.migrateFeatureTaskArtifacts(row, JsonCodec.valueToJsonString(migratedArtifacts))
      }
      AdmittedFeatureTaskRuntimeExecution(
        identity,
        plan,
        checkedInputs,
        requireNotNull(descriptor),
        phaseOutputs.migratedVersions + if (goalMigration) setOf("0.6" to "0.7") else emptySet(),
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
    } catch (error: SkillBillRuntimeException) {
      recordMigrationFailure(error)
      throw error
    }

  private fun recordMigrationFailure(error: SkillBillRuntimeException) {
    val migrationCode = error.code as? FeatureTaskRuntimeMigrationFailureCode
    if (migrationCode != null) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=feature_task_phase_output_migration source_version=unknown target_version=0.7 " +
          "result=${migrationCode.name.lowercase()}",
      )
    }
  }

  private data class AdmissionRequest(
    val workflowId: String,
    val inputs: EffectiveGatePolicyInputs,
    val expectedIdentity: FeatureTaskExecutionIdentity?,
    val requestedReviewSelection: RuntimeReviewSelection?,
  )

  private fun migrateGoalImport(
    states: WorkflowStateRepository,
    session: GoalRunnerPersistenceSession?,
    identity: FeatureTaskExecutionIdentity,
  ): Boolean {
    val before = states.getFeatureTaskWorkflowAsMode(identity.workflowId, FeatureTaskWorkflowMode.RUNTIME)
    val imported =
      before?.toSnapshot()?.artifacts?.let {
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.value(it) as? Map<*, *>
      }
    val importedVersion = imported?.get(GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_VERSION)
    return if (imported != null && importedVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION
    ) {
      val parentId =
        imported[GoalPlanningPreparationPayloadKeys.PARENT_GOAL_WORKFLOW_ID] as? String
          ?: throw SkillBillRuntimeException(
            FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT,
            "Goal import has no parent ownership. Restore the original import before resuming.",
          )
      planningMigration.migrate(
        session ?: throw SkillBillRuntimeException(
          FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT,
          "Coupled planning migration requires the owning persistence session. Resume the parent goal.",
        ),
        parentId,
        identity.repositoryIdentity,
        identity.normalizedIssueKey,
      )
    } else {
      false
    }
  }

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
    workflowId: String,
    snapshot: WorkflowStateSnapshot,
    ownsGoalPlanningImport: Boolean,
  ): MigratedPhaseOutputs {
    val records = decodePhaseRecords(snapshot.artifacts)
    val migratedVersions = linkedSetOf<Pair<String, String>>()
    val rawRecords =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(snapshot.artifacts) as? Map<*, *>
        ?: if (!DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.contains(snapshot.artifacts)) {
          return MigratedPhaseOutputs(emptyMap(), emptySet())
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
      if (!requiresOutputMigration(output, record.phaseId, phaseId, workflowId)) return@forEach
      when (val result = phaseOutputMigration.migrate(output)) {
        is FeatureTaskRuntimePhaseOutputMigrationResult.Current -> Unit
        is FeatureTaskRuntimePhaseOutputMigrationResult.Migrated -> {
          val hydrated = record.executionOrigin == FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED
          if (ownsGoalPlanningImport && hydrated) {
            throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
              "$workflowId#$phaseId",
              "phase output is coupled to a goal-planning import; preserve this workflow until the owning " +
                "goal-planning migration can update both records together",
            )
          }
          migratedVersions += result.sourceVersion to result.targetVersion
          migrated[phaseId] = patchOutput(rawRecords[phaseId], result.payload, "$workflowId#$phaseId")
        }
        is FeatureTaskRuntimePhaseOutputMigrationResult.Refused ->
          refuseMigration(workflowId, phaseId, result)
      }
    }
    return MigratedPhaseOutputs(migrated, migratedVersions)
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

  private fun requiresOutputMigration(
    output: String,
    storedPhase: String,
    phaseId: String,
    workflowId: String,
  ): Boolean {
    if (storedPhase != phaseId) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        "$workflowId#$phaseId",
        "outer phase record identity does not match its owning phase key",
      )
    }
    if (!output.trimStart().startsWith("{")) return false
    val envelope = JsonCodec.parseObjectOrNull(output)
    if (envelope?.get(SharedPayloadKeys.CONTRACT_VERSION)?.let(JsonCodec::jsonElementToValue) ==
      FEATURE_TASK_RUNTIME_CONTRACT_VERSION
    ) {
      return false
    }
    if (envelope == null && !output.contains("\"${SharedPayloadKeys.CONTRACT_VERSION}\"")) return false
    if (envelope != null && envelope[SharedPayloadKeys.PHASE_ID]?.let(JsonCodec::jsonElementToValue) != phaseId) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        "$workflowId#$phaseId",
        "phase output identity does not match its owning phase record",
      )
    }
    return true
  }

  private fun refuseMigration(
    workflowId: String,
    phaseId: String,
    refusal: FeatureTaskRuntimePhaseOutputMigrationResult.Refused,
  ): Nothing {
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
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=feature_task_phase_output_migration workflow_id=${workflowId.take(ADMISSION_WORKFLOW_LABEL_LIMIT)} " +
        "phase_id=$phaseId source_version=$sourceVersion " +
        "target_version=${refusal.targetVersion ?: "unknown"} result=${refusal.result.name.lowercase()}",
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

  fun recordCommittedMigrations(execution: AdmittedFeatureTaskRuntimeExecution) {
    execution.migrationVersions.forEach { (source, target) ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=feature_task_phase_output_migration source_version=$source target_version=$target result=committed",
      )
    }
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
