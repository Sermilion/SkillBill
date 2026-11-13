package skillbill.engine.featuretask.lifecycle.assignment

import me.tatarka.inject.annotations.Inject
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.ResolvedWorkflowAgentAssignments
import skillbill.config.model.WorkflowAgentAssignmentPolicyMode
import skillbill.config.model.WorkflowAgentAssignmentSource
import skillbill.config.model.WorkflowAgentPhaseAssignment
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.WORKFLOW_AGENT_ASSIGNMENTS_CONTRACT_VERSION
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentInspection.Legacy
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentInspection.Present
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentInspection.Problem
import skillbill.engine.featuretask.lifecycle.execution.decoding.decodeExecutionPlan
import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.workflow.WorkflowAgentAssignmentsFailureCode
import skillbill.install.model.SupportedAgent
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.assignment.WorkflowAgentAssignmentsValidator
import skillbill.ports.taskruntime.executionplan.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import skillbill.contracts.workflow.identity.task.WorkflowAgentAssignmentKeys as Keys

@Inject
public class WorkflowAgentAssignmentStore(
  private val database: DatabaseSessionFactory,
  private val validator: WorkflowAgentAssignmentsValidator,
  private val executionPlanValidator: FeatureTaskRuntimeExecutionPlanValidator,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun prepare(assignments: ResolvedWorkflowAgentAssignments): Present {
    val canonical = validator.canonicalize(encode(assignments), sourceLabel(assignments.admissionWorkflowId))
    return Present(assignments, canonical, fingerprint(canonical))
  }

  public fun inspect(workflowId: String): WorkflowAgentAssignmentInspection =
    database.readIfPresent { unit -> inspect(unit.workflowStates, workflowId) }
      ?: Legacy.also { recordInspection(workflowId, it) }

  public fun inspect(
    unit: UnitOfWork,
    workflowId: String,
  ): WorkflowAgentAssignmentInspection = inspect(unit.workflowStates, workflowId)

  public fun inspect(
    states: WorkflowStateRepository,
    workflowId: String,
  ): WorkflowAgentAssignmentInspection =
    inspectStored(states, workflowId).also {
      recordInspection(workflowId, it)
    }

  private fun inspectStored(
    states: WorkflowStateRepository,
    workflowId: String,
  ): WorkflowAgentAssignmentInspection {
    val row = states.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME) ?: return Legacy
    val artifacts = row.toSnapshot().artifacts
    if (!DurableWorkflowArtifactFamily.WORKFLOW_AGENT_ASSIGNMENTS.contains(artifacts)) return Legacy
    val raw = DurableWorkflowArtifactFamily.WORKFLOW_AGENT_ASSIGNMENTS.value(artifacts)
    return try {
      val encoded =
        (raw as? String)?.toByteArray(Charsets.UTF_8)
          ?: invalid(workflowId, "routing artifact must be canonical JSON text")
      val canonical = validator.canonicalize(encoded, sourceLabel(workflowId))
      val value = decode(canonical, workflowId)
      validateRetainedEvidence(workflowId, artifacts, value)
      Present(value, canonical, fingerprint(canonical))
    } catch (failure: SkillBillRuntimeException) {
      Problem(failure.code, sourceLabel(workflowId), failure.message.orEmpty())
    }
  }

  public fun require(workflowId: String): Present =
    when (val result = inspect(workflowId)) {
      is Present -> result
      Legacy -> invalid(workflowId, "workflow has no admitted routing record")
      is Problem -> throw SkillBillRuntimeException(result.failureCode, result.recoveryMessage)
    }

  public fun adopt(
    unit: UnitOfWork,
    sourceWorkflowId: String,
    targetWorkflowId: String,
  ): WorkflowAgentAssignmentAdoption {
    val source =
      when (val inspection = inspect(unit, sourceWorkflowId)) {
        is Present -> inspection
        Legacy -> unsafeMigration(sourceWorkflowId, "source routing record is absent")
        is Problem -> throw SkillBillRuntimeException(inspection.failureCode, inspection.recoveryMessage)
      }
    val targetRow =
      unit.workflowStates.getFeatureTaskWorkflowAsMode(targetWorkflowId, FeatureTaskWorkflowMode.RUNTIME)
        ?: unsafeMigration(targetWorkflowId, "target workflow is missing")
    val targetSnapshot = targetRow.toSnapshot()
    val hasTargetRouting = DurableWorkflowArtifactFamily.WORKFLOW_AGENT_ASSIGNMENTS.contains(targetSnapshot.artifacts)
    if (hasTargetRouting) {
      val existing =
        when (val inspection = inspect(unit, targetWorkflowId)) {
          is Present -> inspection
          Legacy -> unsafeMigration(targetWorkflowId, "target routing artifact disappeared during inspection")
          is Problem -> throw SkillBillRuntimeException(inspection.failureCode, inspection.recoveryMessage)
        }
      if (!existing.canonicalBytes.contentEquals(source.canonicalBytes)) {
        unsafeMigration(targetWorkflowId, "target already has different admitted routing evidence")
      }
      diagnostic(targetWorkflowId, "outcome=no_op reason=identical_current")
      return WorkflowAgentAssignmentAdoption(
        existing,
        changed = false,
        sourceLabel = sourceLabel(sourceWorkflowId),
        targetLabel = sourceLabel(targetWorkflowId),
      )
    }
    validateRetainedEvidence(targetWorkflowId, targetSnapshot.artifacts, source.assignments)
    val updated =
      targetSnapshot.artifacts +
        DurableWorkflowArtifactFamily.WORKFLOW_AGENT_ASSIGNMENTS.entry(source.canonicalBytes.toString(Charsets.UTF_8))
    unit.workflowStates.migrateFeatureTaskArtifacts(targetRow, DurableWorkflowArtifacts.fromMap(updated))
    return WorkflowAgentAssignmentAdoption(
      source,
      changed = true,
      sourceLabel = sourceLabel(sourceWorkflowId),
      targetLabel = sourceLabel(targetWorkflowId),
    )
  }

  public fun recordAdoptionOutcome(
    adoption: WorkflowAgentAssignmentAdoption,
    committed: Boolean,
  ) {
    if (adoption.changed) {
      diagnostic(
        adoption.targetLabel,
        "outcome=${if (committed) "committed" else "rolled_back"} " +
          "reason=legacy_adoption source=${adoption.sourceLabel}",
      )
    }
  }

  public fun prepareLegacyRecord(
    workflowId: String,
    artifacts: Map<String, Any?>,
    invokedAgentId: String?,
  ): String? {
    if (invokedAgentId == null) return null
    val invoked =
      SupportedAgent.entries.firstOrNull { it.wireValue == invokedAgentId }
        ?: unsafeMigration(workflowId, "the current invocation is not a supported provider")
    val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(artifacts) ?: return null
    val plan =
      try {
        readPlan(descriptor)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: RuntimeException) {
        unsafeMigration(workflowId, "retained execution-plan evidence cannot be validated")
      }
    val partial =
      ResolvedWorkflowAgentAssignments(
        admissionWorkflowId = workflowId,
        policyMode = WorkflowAgentAssignmentPolicyMode.LEGACY,
        invokedAgentId = invoked,
        configuredAgentOverrideId = null,
        phaseAssignments =
          plan.stepLaunchAssignments.map { (phase, launch) ->
            val agent =
              SupportedAgent.entries.firstOrNull { it.wireValue == launch.agentId }
                ?: unsafeMigration(workflowId, "retained phase '$phase' has an unsupported provider")
            val tier = ExecutionMatrix().tierOf(phase)
            phase to WorkflowAgentPhaseAssignment(agent, tier, WorkflowAgentAssignmentSource.LEGACY_RECORDED)
          }.toMap(),
      )
    val encoded = validator.canonicalize(encode(partial), sourceLabel(workflowId))
    validateRetainedEvidence(workflowId, artifacts, partial)
    diagnostic(workflowId, "outcome=prepared reason=missing_artifact")
    return encoded.toString(Charsets.UTF_8)
  }

  private fun encode(assignments: ResolvedWorkflowAgentAssignments): ByteArray =
    JsonCodec.mapToJsonString(
      linkedMapOf(
        Keys.CONTRACT_VERSION to WORKFLOW_AGENT_ASSIGNMENTS_CONTRACT_VERSION,
        Keys.ADMISSION_WORKFLOW_ID to assignments.admissionWorkflowId,
        Keys.POLICY_MODE to assignments.policyMode.id,
        Keys.INVOKED_AGENT_ID to assignments.invokedAgentId.wireValue,
        Keys.CONFIGURED_AGENT_OVERRIDE_ID to assignments.configuredAgentOverrideId?.wireValue,
        Keys.PHASE_ASSIGNMENTS to
          assignments.phaseAssignments.mapValues { (_, phase) ->
            mapOf(Keys.AGENT_ID to phase.agentId.wireValue, Keys.TIER to phase.tier.id, Keys.SOURCE to phase.source.id)
          },
      ),
    ).toByteArray(Charsets.UTF_8)

  private fun recordInspection(
    workflowId: String,
    result: WorkflowAgentAssignmentInspection,
  ) {
    when (result) {
      Legacy -> diagnostic(workflowId, "outcome=classified reason=missing_artifact")
      is Present ->
        if (result.assignments.policyMode == WorkflowAgentAssignmentPolicyMode.LEGACY) {
          diagnostic(workflowId, "outcome=classified reason=legacy_record")
        }
      is Problem -> diagnostic(workflowId, "outcome=refused reason=${result.failureCode}")
    }
  }

  private fun diagnostic(
    workflowId: String,
    facts: String,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=workflow_agent_assignment workflow=${sourceLabel(workflowId)} $facts",
    )
  }

  private fun validateRetainedEvidence(
    workflowId: String,
    artifacts: Map<String, Any?>,
    assignments: ResolvedWorkflowAgentAssignments,
  ) {
    val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(artifacts) ?: return
    val plan =
      try {
        readPlan(descriptor)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: RuntimeException) {
        unsafeMigration(workflowId, "retained execution-plan evidence cannot be validated")
      }
    plan.stepLaunchAssignments.forEach { (phase, launch) ->
      val selected = assignments.phaseAssignments[phase]?.agentId?.wireValue
      if (selected != null && selected != launch.agentId) {
        unsafeMigration(workflowId, "routing and retained provider evidence disagree for phase '$phase'")
      }
    }
  }

  private fun readPlan(descriptor: Any): ResolvedPhaseExecutionPlan {
    val canonical =
      executionPlanValidator.canonicalize(
        JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8),
        "routing retained execution plan",
      )
    return decodeExecutionPlan(
      requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(canonical.toString(Charsets.UTF_8)))),
    )
  }

  private fun decode(
    encoded: ByteArray,
    workflowId: String,
  ): ResolvedWorkflowAgentAssignments {
    val root =
      JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(encoded.toString(Charsets.UTF_8)))
        ?: invalid(workflowId, "routing record is not an object")
    val phaseValues =
      JsonCodec.anyToStringAnyMap(root[Keys.PHASE_ASSIGNMENTS])
        ?: invalid(workflowId, "routing phase assignments are missing")
    val phases =
      phaseValues.mapValues { (phase, raw) ->
        val fields = JsonCodec.anyToStringAnyMap(raw) ?: invalid(workflowId, "phase '$phase' is malformed")
        WorkflowAgentPhaseAssignment(
          agentId = supportedAgent(workflowId, fields[Keys.AGENT_ID]),
          tier =
            ExecutionTier.fromId(fields[Keys.TIER] as? String ?: "")
              ?: invalid(workflowId, "phase '$phase' has an unknown tier"),
          source =
            WorkflowAgentAssignmentSource.entries.firstOrNull { it.id == fields[Keys.SOURCE] }
              ?: invalid(workflowId, "phase '$phase' has an unknown source"),
        )
      }
    return ResolvedWorkflowAgentAssignments(
      admissionWorkflowId =
        root[Keys.ADMISSION_WORKFLOW_ID] as? String ?: invalid(workflowId, "admission identity is missing"),
      policyMode =
        WorkflowAgentAssignmentPolicyMode.entries.firstOrNull { it.id == root[Keys.POLICY_MODE] }
          ?: invalid(workflowId, "policy mode is unknown"),
      invokedAgentId = supportedAgent(workflowId, root[Keys.INVOKED_AGENT_ID]),
      configuredAgentOverrideId =
        (root[Keys.CONFIGURED_AGENT_OVERRIDE_ID] as? String)?.let { supportedAgent(workflowId, it) },
      phaseAssignments = phases,
    )
  }

  private fun supportedAgent(
    workflowId: String,
    value: Any?,
  ): SupportedAgent =
    SupportedAgent.entries.firstOrNull { it.wireValue == value }
      ?: invalid(workflowId, "routing record contains an unknown provider")

  private fun fingerprint(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun sourceLabel(workflowId: String): String = workflowId.take(MAX_SOURCE_LABEL_LENGTH)

  private fun invalid(
    workflowId: String,
    reason: String,
  ): Nothing {
    diagnostic(workflowId, "outcome=refused reason=invalid_record")
    throw SkillBillRuntimeException(
      WorkflowAgentAssignmentsFailureCode.INVALID_RECORD,
      "Workflow '${sourceLabel(workflowId)}' has an invalid routing record: $reason. " +
        "Keep the source record and restore or repair it before resuming.",
    )
  }

  private fun unsafeMigration(
    workflowId: String,
    reason: String,
  ): Nothing {
    diagnostic(workflowId, "outcome=refused reason=unsafe_migration")
    throw SkillBillRuntimeException(
      WorkflowAgentAssignmentsFailureCode.UNSAFE_MIGRATION,
      "Workflow '${sourceLabel(workflowId)}' routing evidence cannot be adopted: $reason. " +
        "Preserve both workflow records and repair the evidence before retrying.",
    )
  }

  private companion object {
    const val MAX_SOURCE_LABEL_LENGTH: Int = 128
  }
}

public sealed interface WorkflowAgentAssignmentInspection {
  public data object Legacy : WorkflowAgentAssignmentInspection

  public class Present(
    public val assignments: ResolvedWorkflowAgentAssignments,
    canonicalBytes: ByteArray,
    public val fingerprint: String,
  ) : WorkflowAgentAssignmentInspection {
    private val encoded: ByteArray = canonicalBytes.copyOf()
    public val canonicalBytes: ByteArray get() = encoded.copyOf()
  }

  public data class Problem(
    public val failureCode: RuntimeFailureCode,
    public val sourceLabel: String,
    public val recoveryMessage: String,
  ) : WorkflowAgentAssignmentInspection
}

public data class WorkflowAgentAssignmentAdoption(
  public val inspection: WorkflowAgentAssignmentInspection.Present,
  public val changed: Boolean,
  internal val sourceLabel: String,
  internal val targetLabel: String,
)
