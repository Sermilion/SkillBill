package skillbill.workflow.engine

import skillbill.contracts.workflow.session.WorkflowContinueSessionSummary
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.model.WorkflowContinueStatus

internal data class ContinuationArtifactKeys(
  val currentStepArtifactKeys: List<String>,
  val omittedArtifactKeys: List<String>,
)

internal data class ContinuationBriefRequest(
  val definition: WorkflowDefinition,
  val workflowId: String,
  val resumeStepId: String,
  val continueStatus: WorkflowContinueStatus,
  val nextAction: String,
  val artifactKeys: ContinuationArtifactKeys,
)

internal fun continuationBrief(request: ContinuationBriefRequest): String {
  val stepLabel = request.definition.stepLabels[request.resumeStepId] ?: request.resumeStepId
  val currentArtifacts = request.artifactKeys.currentStepArtifactKeys.joinToString().ifBlank { "none" }
  val omittedArtifacts = request.artifactKeys.omittedArtifactKeys.joinToString().ifBlank { "none" }
  val skillName = request.definition.skillName
  val instructionPath =
    continuationEntryCommand(skillName, request.workflowId).takeIf { command -> command != skillName }
      ?.let { command -> "Follow the normal step instructions that `$command` supplies." }
      .orEmpty()
  return "Resume `${request.definition.skillName}` workflow `${request.workflowId}` from `$stepLabel` " +
    "(`${request.resumeStepId}`). " +
    instructionPath.takeIf(String::isNotEmpty)?.let { "$it " }.orEmpty() +
    "Use `current_step_artifacts` in this compact payload ($currentArtifacts) as authoritative " +
    "current-step context instead of reconstructing prior context from chat history. " +
    "Omitted artifact keys ($omittedArtifacts) remain private phase context. Explicit operator diagnostics " +
    "may inspect them with `workflow show`; phase agents must not. Workflow activation status: " +
    "`${request.continueStatus.wireValue}`. Next action: ${request.nextAction}"
}

internal data class ContinuationIdentity(
  val workflowId: String,
  val sessionId: String,
  val resumeStepId: String,
  val continueStatus: WorkflowContinueStatus,
  val nextAction: String,
  val nextAttemptCount: Int,
)

internal data class ContinuationEntryPromptRequest(
  val definition: WorkflowDefinition,
  val identity: ContinuationIdentity,
  val artifactKeys: ContinuationArtifactKeys,
  val sessionSummary: WorkflowContinueSessionSummary,
  val extraFields: Map<String, Any?>,
)

internal fun continuationEntryPrompt(request: ContinuationEntryPromptRequest): String {
  val identity = request.identity
  val references =
    request.definition.continuationReferenceSections[identity.resumeStepId].orEmpty()
      .joinToString("; ")
  val directive =
    request.definition.continuationDirectives[identity.resumeStepId]
      ?: (
        "Resume the workflow from the recovered current step using the persisted artifacts as " +
          "authoritative context."
      )
  val currentArtifacts = request.artifactKeys.currentStepArtifactKeys.joinToString().ifBlank { "none" }
  val omittedArtifacts = request.artifactKeys.omittedArtifactKeys.joinToString().ifBlank { "none" }
  val commonLines =
    mutableListOf(
      "Use `${continuationEntryCommand(request.definition.skillName, identity.workflowId)}` in continuation mode.",
      "Workflow id: ${identity.workflowId}",
      "Session id: ${identity.sessionId.ifBlank { "(none)" }}",
      "Continue status: ${identity.continueStatus.wireValue}",
      "Resume step: ${identity.resumeStepId} " +
        "(${request.definition.stepLabels[identity.resumeStepId] ?: identity.resumeStepId})",
    )
  if (request.definition.usesFeatureTaskRuntimeContinuation) {
    commonLines += "Feature: ${(request.extraFields["feature_name"] as String).ifBlank { "(unknown)" }}"
    commonLines += "Feature size: ${(request.extraFields["feature_size"] as String).ifBlank { "(unknown)" }}"
    commonLines += "Branch: ${(request.extraFields["branch_name"] as String).ifBlank { "(unknown)" }}"
  }
  commonLines += "Current-step artifacts: $currentArtifacts"
  commonLines += "Omitted artifact keys: $omittedArtifacts"
  if (request.definition.skillName == "bill-feature-verify") {
    commonLines += "Acceptance criteria count: ${request.sessionSummary.acceptanceCriteriaCount}"
    commonLines += "Rollout relevant: ${request.sessionSummary.rolloutRelevant}"
  }
  val specSummary = request.sessionSummary.specSummary.ifBlank { "(none saved)" }
  commonLines += "Spec summary: $specSummary"
  commonLines += "Reference sections: ${references.ifBlank { "normal step instructions only" }}"
  commonLines +=
    "Rules: do not rerun completed steps unless the workflow sends work backwards; treat " +
    "`current_step_artifacts` as the complete authoritative phase input. Omitted keys remain private; " +
    "`workflow show` is an explicit operator diagnostic and must not widen phase context."
  commonLines +=
    "Workflow update rule: every step_updates item must include step_id, status, and integer " +
    "attempt_count; use attempt_count ${identity.nextAttemptCount} for `${identity.resumeStepId}` " +
    "unless a later retry increments it."
  commonLines +=
    "Keep the same workflow_id and session_id, then continue " +
    "`${continuationEntryCommand(request.definition.skillName, identity.workflowId)}`."
  commonLines += "Step directive: $directive"
  commonLines += "Immediate next action: ${identity.nextAction}"
  return commonLines.joinToString("\n")
}

internal fun continuationEntryCommand(
  skillName: String,
  workflowId: String,
): String =
  when (skillName) {
    "bill-feature-verify" -> "skill-bill operation verify confirm:$workflowId"
    else -> skillName
  }
