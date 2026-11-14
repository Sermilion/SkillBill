package skillbill.engine.goalrunner.intake

import me.tatarka.inject.annotations.Inject
import skillbill.config.model.FrozenWorkflowAgentOptionCheck
import skillbill.config.model.ResolvedWorkflowAgentAssignments
import skillbill.config.model.WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS
import skillbill.config.model.WorkflowAgentAssignmentPolicyMode
import skillbill.config.model.checkFrozenWorkflowAgentOptions
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentInspection
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentStore
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goalrunner.execution.core.StoppedReportArgs
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalIntakeAdmission
import skillbill.engine.goalrunner.model.GoalIntakeMissingInput
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.status.stopped
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.nio.file.Path

private const val MAX_DIAGNOSTIC_ID = 128

@Inject
public class GoalIntakeAdmissionService(
  private val manifestStore: GoalRunnerManifestStore,
  internal val intakePreparation: GoalIntakePreparation,
  private val workflowAgentAssignmentStore: WorkflowAgentAssignmentStore,
  private val executionPlans: FeatureTaskRuntimeExecutionPlanResolver,
  private val diagnostics: RuntimeDiagnostics,
) : GoalNewWorkAdmissionPort {
  override fun admit(
    intake: String,
    repoRoot: Path,
  ): GoalNewWorkAdmission =
    try {
      when (val result = admission(intake, repoRoot)) {
        is GoalIntakeAdmission.Admitted -> {
          GoalNewWorkAdmission.Admitted(result.issueKey)
        }

        is GoalIntakeAdmission.NeedsInput -> {
          when (result.missing) {
            GoalIntakeMissingInput.ISSUE_KEY -> {
              GoalNewWorkAdmission.NoIssueKey
            }

            GoalIntakeMissingInput.REQUIREMENTS -> {
              GoalNewWorkAdmission.Refused(result.issueKey, "Add what ${result.issueKey} should do, then start again.")
            }

            GoalIntakeMissingInput.DESCRIPTION -> {
              GoalNewWorkAdmission.Refused(
                result.issueKey,
                "Add a short description with English letters or digits after ${result.issueKey}, then start again.",
              )
            }
          }
        }
      }
    } catch (failure: InvalidFeatureSpecPreparationRequestError) {
      GoalNewWorkAdmission.Refused(null, failure.reason)
    }

  public fun admission(
    intake: String,
    repoRoot: Path,
  ): GoalIntakeAdmission {
    val trimmed = intake.trim()
    existingSpecAdmission(trimmed, repoRoot)?.let { return it }
    if (trimmed.isNotBlank() && trimmed.none(Char::isWhitespace) && !trimmed.contains('/')) {
      manifestStore.readByIssueKeyIfPresent(trimmed, repoRoot)?.let {
        return GoalIntakeAdmission.Admitted(it.manifest.issueKey)
      }
    }
    val parsed =
      GoalIntake.parseOrNull(trimmed)
        ?: return GoalIntakeAdmission.NeedsInput(GoalIntakeMissingInput.ISSUE_KEY, issueKey = null)
    val missing =
      if (manifestStore.readByIssueKeyIfPresent(parsed.issueKey, repoRoot) == null) {
        intakePreparation.missingNewWorkInput(parsed, repoRoot)
      } else {
        null
      }
    return missing?.let { GoalIntakeAdmission.NeedsInput(it, parsed.issueKey) }
      ?: GoalIntakeAdmission.Admitted(parsed.issueKey)
  }

  private fun existingSpecAdmission(
    intake: String,
    repoRoot: Path,
  ): GoalIntakeAdmission? =
    intakePreparation.issueKeyForExistingSpec(intake, repoRoot)?.let(GoalIntakeAdmission::Admitted)

  public fun retainedRoutingProviders(request: GoalRunnerRunRequest): Map<String, Set<String>>? {
    val state =
      manifestStore.loadOccupyingByIssueKey(request.issueKey, request.repoRoot)?.state
        ?: manifestStore.loadDurableByIssueKey(request.issueKey, request.repoRoot)
        ?: return null
    val providers = linkedMapOf<String, MutableSet<String>>()
    val parentRouting = workflowAgentAssignmentStore.inspect(state.parentWorkflowId)

    val parentLegacy = appendRoutingProviders(state.parentWorkflowId, providers, parentRouting)
    val childLegacy =
      state.manifest.subtasks
        .mapNotNull { it.workflowId?.takeIf(String::isNotBlank) }
        .map { appendRoutingProviders(it, providers) }
        .any { it }
    if (parentLegacy || childLegacy) {
      val compatibilityProvider = request.configuredAgentOverrideId ?: request.invokedAgentId
      WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS.forEach { phase ->
        providers.getOrPut(phase) { linkedSetOf() }.add(compatibilityProvider)
      }
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=workflow_agent_assignment phase=preplan provider=${compatibilityProvider.take(MAX_DIAGNOSTIC_ID)} " +
          "reason=legacy_invocation_compatibility outcome=used",
      )
    }
    val recipients = providers.mapValues { it.value.toSet() }
    recordIgnoredInvocationDrift(request, recipients)
    return recipients.takeIf { it.isNotEmpty() }
  }

  public fun retainedLegacyLaunchPhases(request: GoalRunnerRunRequest): Set<String> {
    val state =
      manifestStore.loadOccupyingByIssueKey(request.issueKey, request.repoRoot)?.state
        ?: manifestStore.loadDurableByIssueKey(request.issueKey, request.repoRoot)
        ?: return emptySet()
    return state.manifest.subtasks
      .asSequence()
      .mapNotNull { it.workflowId?.takeIf(String::isNotBlank) }
      .filter { workflowId ->
        when (val routing = workflowAgentAssignmentStore.inspect(workflowId)) {
          WorkflowAgentAssignmentInspection.Legacy -> {
            true
          }

          is WorkflowAgentAssignmentInspection.Present -> {
            routing.assignments.policyMode == WorkflowAgentAssignmentPolicyMode.LEGACY
          }

          is WorkflowAgentAssignmentInspection.Problem -> {
            throw SkillBillRuntimeException(routing.failureCode, routing.recoveryMessage)
          }
        }
      }.flatMap { workflowId -> executionPlans.recordedStepLaunchProviders(workflowId).orEmpty().keys }
      .toSet()
  }

  internal fun frozenOptionRefusal(request: GoalRunnerRunRequest): GoalRunnerRunReport.Stopped? {
    val state =
      manifestStore.loadOccupyingByIssueKey(request.issueKey, request.repoRoot)?.state
        ?: manifestStore.loadDurableByIssueKey(request.issueKey, request.repoRoot)
        ?: return null
    val retained = retainedAssignments(state.parentWorkflowId, request.invokedAgentId) ?: return null
    val refusal =
      listOfNotNull(
        request.configuredAgentOverrideId,
        request.agentAssignment.override,
      ).firstNotNullOfOrNull { explicit ->
        (checkFrozenWorkflowAgentOptions(retained, explicit, emptyMap()) as? FrozenWorkflowAgentOptionCheck.Refused)
      } ?: (
        checkFrozenWorkflowAgentOptions(retained, null, request.agentAssignment.perPhaseAgentIds)
          as? FrozenWorkflowAgentOptionCheck.Refused
      ) ?: return null
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=workflow_agent_assignment workflow=${state.parentWorkflowId.take(MAX_DIAGNOSTIC_ID)} " +
        "phase=${refusal.phaseId.take(
          MAX_DIAGNOSTIC_ID,
        )} provider=${refusal.requestedAgentId.take(MAX_DIAGNOSTIC_ID)} " +
        "retained=${refusal.retainedAgentId.take(MAX_DIAGNOSTIC_ID)} reason=explicit_provider_refused " +
        "classification=${refusal.reason.name.lowercase()}",
    )
    return stopped(
      StoppedReportArgs(
        issueKey = request.issueKey,
        attempted = emptyList(),
        subtaskId =
          state.manifest.subtasks
            .firstOrNull()
            ?.id ?: 0,
        reason = GoalRunnerStopReason.BLOCKED,
        blockedReason =
          if (refusal.reason == FrozenWorkflowAgentOptionCheck.Reason.UNKNOWN_PROVIDER) {
            "Provider '${refusal.requestedAgentId}' is unsupported for phase '${refusal.phaseId}'. " +
              "Choose a supported provider id and retry."
          } else {
            "Phase '${refusal.phaseId}' is frozen to provider '${refusal.retainedAgentId}', but the explicit option " +
              "requested '${refusal.requestedAgentId}'. Remove the conflicting option or use the recorded provider."
          },
        workflowId = state.parentWorkflowId,
        lastResumableStep = refusal.phaseId,
        failurePhaseId = refusal.phaseId,
      ),
    )
  }

  private fun recordIgnoredInvocationDrift(
    request: GoalRunnerRunRequest,
    assignments: Map<String, Set<String>>,
  ) {
    if (assignments.isEmpty() || assignments.values.all { request.invokedAgentId in it }) return
    val retained = assignments.toSortedMap().entries.first { request.invokedAgentId !in it.value }
    val retainedProvider = retained.value.sorted().first()
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=workflow_agent_assignment phase=${retained.key.take(MAX_DIAGNOSTIC_ID)} " +
        "provider=${retainedProvider.take(
          MAX_DIAGNOSTIC_ID,
        )} invocation=${request.invokedAgentId.take(MAX_DIAGNOSTIC_ID)} " +
        "reason=retained_assignment_authority outcome=invocation_ignored",
    )
  }

  internal fun prepareRoutingContext(request: GoalRunnerRunRequest): GoalRunnerRunRequest {
    val state =
      manifestStore.loadOccupyingByIssueKey(request.issueKey, request.repoRoot)?.state
        ?: manifestStore.loadDurableByIssueKey(request.issueKey, request.repoRoot)
    if (state == null) return request
    return when (val routing = workflowAgentAssignmentStore.inspect(state.parentWorkflowId)) {
      is WorkflowAgentAssignmentInspection.Present -> {
        request.copy(
          workflowAgentAssignments = routing.assignments,
          workflowAgentAssignmentsFingerprint = routing.fingerprint,
          routingContextPrepared = true,
        )
      }

      WorkflowAgentAssignmentInspection.Legacy -> {
        request.copy(routingContextPrepared = true)
      }

      is WorkflowAgentAssignmentInspection.Problem -> {
        throw SkillBillRuntimeException(routing.failureCode, routing.recoveryMessage)
      }
    }
  }

  private fun appendRoutingProviders(
    workflowId: String,
    providers: MutableMap<String, MutableSet<String>>,
    inspection: WorkflowAgentAssignmentInspection? = null,
  ): Boolean {
    var legacyRoutingSeen = false
    when (val routing = inspection ?: workflowAgentAssignmentStore.inspect(workflowId)) {
      is WorkflowAgentAssignmentInspection.Present -> {
        routing.assignments.phaseAssignments.forEach { (phase, assignment) ->
          if (routing.assignments.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN) {
            providers.getOrPut(phase) { linkedSetOf() }.add(assignment.agentId.wireValue)
          }
        }
        if (routing.assignments.policyMode == WorkflowAgentAssignmentPolicyMode.LEGACY) {
          legacyRoutingSeen = true
          executionPlans
            .recordedProviderLimitStepLaunchProviders(workflowId)
            .toSortedMap()
            .forEach { (phase, provider) -> providers.getOrPut(phase) { linkedSetOf() }.add(provider) }
        }
      }

      WorkflowAgentAssignmentInspection.Legacy -> {
        legacyRoutingSeen = true
        executionPlans
          .recordedProviderLimitStepLaunchProviders(workflowId)
          .toSortedMap()
          .forEach { (phase, provider) -> providers.getOrPut(phase) { linkedSetOf() }.add(provider) }
      }

      is WorkflowAgentAssignmentInspection.Problem -> {
        throw SkillBillRuntimeException(routing.failureCode, routing.recoveryMessage)
      }
    }
    return legacyRoutingSeen
  }

  private fun retainedAssignments(
    workflowId: String,
    invokedAgentId: String,
  ): ResolvedWorkflowAgentAssignments? {
    return when (val routing = workflowAgentAssignmentStore.inspect(workflowId)) {
      is WorkflowAgentAssignmentInspection.Present -> {
        if (routing.assignments.policyMode == WorkflowAgentAssignmentPolicyMode.LEGACY) {
          val selected =
            routing.assignments.phaseAssignments
              .toSortedMap()
              .entries
              .firstOrNull()
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "seam=workflow_agent_assignment phase=${selected?.key ?: "preplan"} " +
              "provider=${selected?.value?.agentId?.wireValue ?: invokedAgentId.take(MAX_DIAGNOSTIC_ID)} " +
              "reason=legacy_record_use outcome=compatible",
          )
        }
        routing.assignments
      }

      WorkflowAgentAssignmentInspection.Legacy -> {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=workflow_agent_assignment phase=preplan provider=${invokedAgentId.take(MAX_DIAGNOSTIC_ID)} " +
            "reason=legacy_invocation_compatibility outcome=compatible",
        )
        return null
      }

      is WorkflowAgentAssignmentInspection.Problem -> {
        throw SkillBillRuntimeException(routing.failureCode, routing.recoveryMessage)
      }
    }
  }
}
