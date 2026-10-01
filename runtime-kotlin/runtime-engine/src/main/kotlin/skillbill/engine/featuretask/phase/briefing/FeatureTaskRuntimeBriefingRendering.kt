package skillbill.engine.featuretask.phase.briefing

import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.workflow.taskruntime.model.audit.acceptanceCriterionIdentity
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries

fun StringBuilder.appendRepositoryCheckpoint(
  handoff: FeatureTaskRuntimePhaseHandoff,
  envelope: FeatureTaskRuntimeHandoffEnvelope,
) {
  val requiresCheckpoint =
    handoff.projectionDeclarations.any { declaration ->
      declaration.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED
    }
  val checkpoint = envelope.repositoryCheckpoint?.takeIf { requiresCheckpoint } ?: return
  appendLine("## Repository checkpoint (layer 2, resolved)")
  appendLine("fingerprint: ${escapeBriefingLineBreaks(checkpoint.fingerprint)}")
  checkpoint.baseRef?.let { appendLine("base_ref: ${escapeBriefingLineBreaks(it)}") }
  checkpoint.headRef?.let { appendLine("head_ref: ${escapeBriefingLineBreaks(it)}") }
  appendLine("scoped_owned_paths:")
  if (checkpoint.workingTreeOwnedPaths.isEmpty()) {
    appendLine("  (none)")
  } else {
    checkpoint.workingTreeOwnedPaths.forEach { path -> appendLine("  - ${escapeBriefingLineBreaks(path)}") }
  }
  appendLine()
}

fun StringBuilder.appendProjections(envelope: FeatureTaskRuntimeHandoffEnvelope) {
  val visible = envelope.promptVisibleProjections
  if (visible.isEmpty()) {
    appendLine("(none)")
    return
  }
  visible.forEach { projection ->
    append(projection.canonicalDeliveredRendering)
  }
}

fun escapeBriefingLineBreaks(value: String): String =
  value.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

fun StringBuilder.appendAllowlistedRunInvariants(
  handoff: FeatureTaskRuntimePhaseHandoff,
  allowlist: Set<FeatureTaskRuntimeRunInvariantPromptField>,
) {
  val invariants = handoff.runInvariants
  appendLine("## Run invariants (layer 1, unconditional)")
  if (FeatureTaskRuntimeRunInvariantPromptField.SPEC_REFERENCE in allowlist) {
    appendLine("spec_reference: ${invariants.specReference}")
  }
  if (FeatureTaskRuntimeRunInvariantPromptField.FEATURE_SIZE in allowlist) {
    appendLine("feature_size: ${invariants.featureSize.name}")
  }
  if (FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING in allowlist) {
    appendLine("ceremony_scaling:")
    FeatureTaskRuntimePhaseWorkflowQueries.ceremonyScaling(invariants.featureSize)
      .toBriefingLines()
      .forEach { line -> appendLine("  $line") }
  }
  if (FeatureTaskRuntimeRunInvariantPromptField.ACCEPTANCE_CRITERIA in allowlist) {
    appendAcceptanceCriteria(handoff)
  }
  if (FeatureTaskRuntimeRunInvariantPromptField.MANDATES_AND_OVERRIDES in allowlist) {
    appendLine("mandates_and_overrides:")
    if (invariants.mandatesAndOverrides.isEmpty()) {
      appendLine("  (none)")
    } else {
      invariants.mandatesAndOverrides.forEach { mandate -> appendLine("  - $mandate") }
    }
  }
}

fun StringBuilder.appendAcceptanceCriteria(handoff: FeatureTaskRuntimePhaseHandoff) {
  appendLine("acceptance_criteria:")
  handoff.runInvariants.acceptanceCriteria.forEachIndexed { index, criterion ->
    val identified =
      acceptanceCriterionIdentity(criterion, index + 1)?.identifiedText(criterion)
        ?: throw InvalidFeatureTaskRuntimePhaseHandoffSchemaError(
          "acceptance_criteria",
          "Invalid criterion identity at ordinal ${index + 1}.",
        )
    appendLine("  $identified")
  }
}

private const val SHARED_EVIDENCE_PROJECTION: String =
  FeatureTaskRuntimePhaseWorkflowDefinition.SHARED_REVIEW_EVIDENCE_PROJECTION_NAME

private const val SELF_READ_DIFF_INSTRUCTION: String =
  "read the branch diff yourself; it is not delivered in this briefing"

private const val SHARED_EVIDENCE_DIFF_INSTRUCTION: String =
  "the branch diff is already derived for you: the '$SHARED_EVIDENCE_PROJECTION' projection above " +
    "carries its store_path, checkpoint_fingerprint, base_ref/head_ref, and per-file hunk index; " +
    "work from that reference, and dereference store_path when you need the diff bytes themselves"

private const val SHARED_EVIDENCE_UNIT_INSTRUCTION: String =
  "the current unit of work is already derived for you: the '$SHARED_EVIDENCE_PROJECTION' projection " +
    "above carries its store_path, checkpoint_fingerprint, base_ref/head_ref, and per-file hunk index; " +
    "work from that reference, and dereference store_path when you need the diff bytes themselves"

private const val SELF_READ_UNIT_INSTRUCTION: String =
  "read the current unit of work yourself; the shared evidence projection is not delivered in this briefing"

private fun derivedContextInstruction(
  key: String,
  sharedEvidenceDelivered: Boolean,
): String? =
  when (key) {
    FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_DIFF ->
      if (sharedEvidenceDelivered) SHARED_EVIDENCE_DIFF_INSTRUCTION else SELF_READ_DIFF_INSTRUCTION
    "current_unit_of_work" ->
      if (sharedEvidenceDelivered) SHARED_EVIDENCE_UNIT_INSTRUCTION else SELF_READ_UNIT_INSTRUCTION
    FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_SCOPED_REPOSITORY_STATE ->
      "read the repository at the resolved checkpoint above — the diff over base_ref/head_ref plus " +
        "the listed scoped_owned_paths — and treat that actual state, not any upstream receipt claim, " +
        "as the evidence for every criterion. scoped_owned_paths is checkpoint evidence, not a write " +
        "allowlist; remaining-criteria repair may edit any files those criteria require"
    FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_PR_BRANCH_DIFF ->
      SELF_READ_DIFF_INSTRUCTION
    else -> null
  }

fun renderFeatureTaskRuntimePhaseBriefing(
  handoff: FeatureTaskRuntimePhaseHandoff,
  envelope: FeatureTaskRuntimeHandoffEnvelope,
  invariantFields: Set<FeatureTaskRuntimeRunInvariantPromptField>,
): String =
  buildString {
    appendLine("# Feature-task-runtime phase briefing")
    appendLine("phase: ${handoff.phaseId}")
    handoff.drivingVerdict?.let { verdict -> appendLine("driving_verdict: ${verdict.wireValue}") }
    appendLine()
    appendAllowlistedRunInvariants(handoff, invariantFields)
    appendLine()
    appendLine("## Upstream projections (layer 2, declared and validated)")
    appendProjections(envelope)
    appendLine()
    appendRepositoryCheckpoint(handoff, envelope)
    appendLine("## Derived context (layer 3, declared)")
    if (handoff.derivedContextKeys.isEmpty()) {
      append("(none)")
    } else {
      val sharedEvidenceDelivered = envelope.projections.any { it.projectionName == SHARED_EVIDENCE_PROJECTION }
      append(
        handoff.derivedContextKeys.joinToString(separator = "\n") { key ->
          derivedContextInstruction(key, sharedEvidenceDelivered)
            ?.let { instruction -> "- $key: $instruction" }
            ?: "- $key"
        },
      )
    }
  }
