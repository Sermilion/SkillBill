package skillbill.application.workflow.decomposition

import skillbill.application.workflow.decomposition.model.DecomposedParentForPurge
import skillbill.application.workflow.decomposition.model.DecomposedParentsForPurge
import skillbill.application.workflow.decomposition.model.UnclassifiedPurgeRow
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isInvalidWorkflowStateFailure
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.hasDecompositionPlan
import skillbill.workflow.decomposition.runtime.hasDecompositionRuntimeArtifact
import skillbill.workflow.decomposition.runtime.isActiveGoalRuntime
import skillbill.workflow.decomposition.runtime.isGoalContinuationChildWorkflow
import skillbill.workflow.engine.model.ExecutionPlanArtifactView
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

fun WorkflowStateRepository.findDecomposedParentOrCorruptFallback(
  issueKey: String,
  currentProjectedManifest: DecompositionManifest?,
  repositoryIdentity: String? = null,
): WorkflowStateRecord? {
  val normalizedIssueKey = normalizeRequiredIssueKey(issueKey)
  val candidates =
    listFeatureTaskWorkflowsForParentDiscovery(normalizedIssueKey, repositoryIdentity).mapNotNull { row ->
      parentDiscoveryCandidate(row, normalizedIssueKey)
    }
  val validCandidates =
    candidates.filterIsInstance<ParentDiscoveryCandidate.Valid>().map { candidate ->
      DecomposedParentCandidate(candidate.record, candidate.manifest)
    }
  val corruptCandidates = candidates.filterIsInstance<ParentDiscoveryCandidate.Corrupt>()
  val nonStale = validCandidates.filterNot { it.isStaleAbandonedLineage(currentProjectedManifest) }
  val active = nonStale.filter { it.manifest.isActiveGoalRuntime() }
  if (active.size > 1) {
    error(
      "Ambiguous decomposed parent workflows for '$normalizedIssueKey': " +
        active.joinToString { it.record.workflowId } +
        ". Pass an explicit workflow or manifest selector before continuing.",
    )
  }
  val validRecord = (active.firstOrNull() ?: nonStale.firstOrNull())?.record
  if (validRecord != null) return validRecord
  if (corruptCandidates.size > 1) {
    error(
      "Ambiguous corrupt-manifest parent rows for '$normalizedIssueKey': " +
        corruptCandidates.joinToString { it.record.workflowId } +
        ". Operator intervention is required to resolve the duplicate parent rows.",
    )
  }
  return corruptCandidates.firstOrNull()?.record
}

private fun parentDiscoveryCandidate(
  row: WorkflowStateRecord,
  issueKey: String,
): ParentDiscoveryCandidate? {
  val snapshot = row.toSnapshot()
  if (snapshot.isGoalContinuationChildWorkflow()) return null
  val relevant =
    row.issueKey?.trim() == issueKey ||
      snapshot.hasDecompositionPlan() ||
      snapshot.artifacts.hasDecompositionRuntimeArtifact()
  if (!relevant || snapshot.workflowStatus in WorkflowStatus.terminalStatuses) return null
  val manifest = row.decompositionRuntimeOrNull()
  return if (manifest == null) {
    ParentDiscoveryCandidate.Corrupt(row)
  } else if (manifest.issueKey == issueKey) {
    ParentDiscoveryCandidate.Valid(row, manifest)
  } else {
    null
  }
}

private fun WorkflowStateRecord.decompositionRuntimeOrNull(): DecompositionManifest? =
  try {
    toSnapshot().artifacts.decompositionRuntime()
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.isInvalidWorkflowStateFailure())
    null
  }

private fun WorkflowStateRepository.listFeatureTaskWorkflowsForParentDiscovery(
  normalizedIssueKey: String,
  repositoryIdentity: String?,
): List<WorkflowStateRecord> =
  listFeatureTaskWorkflowsForIssue(normalizedIssueKey, repositoryIdentity).filterNot {
    it.isPlanWorkflow()
  }

private fun WorkflowStateRepository.listFeatureTaskWorkflowsForIssue(
  normalizedIssueKey: String,
  repositoryIdentity: String?,
): List<WorkflowStateRecord> {
  val byId = LinkedHashMap<String, WorkflowStateRecord>()
  findFeatureTaskWorkflowsForIssue(FeatureTaskWorkflowMode.RUNTIME, normalizedIssueKey, repositoryIdentity).forEach {
      row ->
    byId[row.workflowId] = row
  }
  findFeatureTaskWorkflowsForIssue(FeatureTaskWorkflowMode.PROSE, normalizedIssueKey, repositoryIdentity).forEach {
      row ->
    byId.putIfAbsent(row.workflowId, row)
  }
  return byId.values.toList()
}

fun WorkflowStateRecord.isPlanWorkflow(): Boolean =
  try {
    ExecutionPlanArtifactView.definitionId(toSnapshot().artifacts) == SkeletonDefinition.PLAN.id
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.isInvalidWorkflowStateFailure())
    false
  }

fun WorkflowStateRepository.listPlanWorkflowsForPurge(
  issueKey: String,
  repositoryIdentity: String,
): List<WorkflowStateRecord> =
  listFeatureTaskWorkflowsForIssue(normalizeRequiredIssueKey(issueKey), repositoryIdentity).filter {
    it.isPlanWorkflow()
  }

fun WorkflowStateRepository.findCompletedPlanWorkflowId(
  issueKey: String,
  repositoryIdentity: String?,
): String? =
  listFeatureTaskWorkflowsForIssue(normalizeRequiredIssueKey(issueKey), repositoryIdentity)
    .filter { it.workflowStatus.workflowStatus() == WorkflowStatus.COMPLETED && it.isPlanWorkflow() }
    .maxByOrNull { it.updatedAt.orEmpty() }
    ?.workflowId

fun WorkflowStateRepository.listDecomposedParentsForPurge(
  issueKey: String,
  repositoryIdentity: String,
): DecomposedParentsForPurge {
  val normalizedIssueKey = normalizeRequiredIssueKey(issueKey)
  val unclassified = mutableListOf<UnclassifiedPurgeRow>()
  val parents =
    listFeatureTaskWorkflowsForParentDiscovery(normalizedIssueKey, repositoryIdentity).mapNotNull { row ->
      val snapshot =
        try {
          row.toSnapshot()
        } catch (error: SkillBillRuntimeException) {
          error.rethrowUnless(error.isInvalidWorkflowStateFailure())
          unclassified += UnclassifiedPurgeRow(row.workflowId, error.message.orEmpty())
          return@mapNotNull null
        }
      val carriesDecomposition =
        snapshot.hasDecompositionPlan() || snapshot.artifacts.hasDecompositionRuntimeArtifact()
      if (snapshot.isGoalContinuationChildWorkflow() || !carriesDecomposition) return@mapNotNull null
      val manifest = row.decompositionRuntimeOrNull()
      if (manifest != null && manifest.issueKey != normalizedIssueKey) null else DecomposedParentForPurge(row, manifest)
    }
  return DecomposedParentsForPurge(parents, unclassified)
}

fun WorkflowStateRepository.findDecomposedParentWorkflow(
  issueKey: String,
  currentProjectedManifest: DecompositionManifest? = null,
  repositoryIdentity: String? = null,
): WorkflowStateRecord? {
  val normalizedIssueKey = normalizeRequiredIssueKey(issueKey)
  val candidates =
    listFeatureTaskWorkflowsForParentDiscovery(normalizedIssueKey, repositoryIdentity).mapNotNull { row ->
      val snapshot = row.toSnapshot()
      if (snapshot.isGoalContinuationChildWorkflow()) return@mapNotNull null
      val manifest = snapshot.artifacts.decompositionRuntime() ?: return@mapNotNull null
      if (
        (snapshot.hasDecompositionPlan() || row.issueKey?.trim() == normalizedIssueKey) &&
        manifest.issueKey == normalizedIssueKey
      ) {
        DecomposedParentCandidate(row, manifest)
      } else {
        null
      }
    }.filterNot { candidate -> candidate.isStaleAbandonedLineage(currentProjectedManifest) }
  val activeCandidates = candidates.filter { candidate -> candidate.manifest.isActiveGoalRuntime() }
  if (activeCandidates.size > 1) {
    error(
      "Ambiguous decomposed parent workflows for '$normalizedIssueKey': " +
        activeCandidates.joinToString { candidate -> candidate.record.workflowId } +
        ". Pass an explicit workflow or manifest selector before continuing.",
    )
  }
  return activeCandidates.firstOrNull()?.record ?: candidates.firstOrNull()?.record
}

private sealed interface ParentDiscoveryCandidate {
  val record: WorkflowStateRecord

  data class Valid(
    override val record: WorkflowStateRecord,
    val manifest: DecompositionManifest,
  ) : ParentDiscoveryCandidate

  data class Corrupt(override val record: WorkflowStateRecord) : ParentDiscoveryCandidate
}

private data class DecomposedParentCandidate(
  val record: WorkflowStateRecord,
  val manifest: DecompositionManifest,
)

private fun DecomposedParentCandidate.isStaleAbandonedLineage(
  currentProjectedManifest: DecompositionManifest?,
): Boolean {
  if (currentProjectedManifest == null || record.workflowStatus.workflowStatus() != WorkflowStatus.ABANDONED) {
    return false
  }
  if (manifest.subtasks.any { subtask -> subtask.hasStarted() }) return false
  return manifest.subtasks.map { it.specPath } != currentProjectedManifest.subtasks.map { it.specPath }
}

private fun DecompositionManifest.sameRuntimeIdentity(other: DecompositionManifest): Boolean =
  issueKey == other.issueKey &&
    parentSpecPath == other.parentSpecPath &&
    subtasks.map { it.specPath } == other.subtasks.map { it.specPath }

fun WorkflowStateRepository.findDecomposedParentWorkflowForRuntime(
  manifest: DecompositionManifest,
): WorkflowStateRecord? =
  findFeatureTaskWorkflowsForIssue(
    FeatureTaskWorkflowMode.RUNTIME,
    normalizeRequiredIssueKey(manifest.issueKey),
  ).firstOrNull {
      row ->
    val snapshot = row.toSnapshot()
    !row.isPlanWorkflow() &&
      !snapshot.isGoalContinuationChildWorkflow() &&
      (snapshot.hasDecompositionPlan() || row.issueKey?.trim() == manifest.issueKey) &&
      snapshot.artifacts.decompositionRuntime()?.sameRuntimeIdentity(manifest) == true
  }
