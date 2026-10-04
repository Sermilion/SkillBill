package skillbill.engine.featuretask.review.core

import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDerivation
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolveOutcome
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceOutcome
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceFileEntry
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceHunkEntry
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val log: Logger = Logger.getLogger(FeatureTaskRuntimeSharedReviewEvidenceResolver::class.java.name)

class FeatureTaskRuntimeSharedReviewEvidenceResolver(
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  private val diffResolver: DiffResolverPort,
) {
  fun resolve(
    repoRoot: Path,
    workflowId: String?,
    checkpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
    consumerPhaseId: String,
  ): FeatureTaskRuntimeSharedReviewEvidenceResolved? {
    if (workflowId.isNullOrBlank() || checkpoint == null) return null
    val resolution =
      sharedEvidenceResolver.resolve(
        FeatureTaskRuntimeSharedEvidenceRequest(repoRoot, workflowId, checkpoint),
      ) { requested -> derive(repoRoot, requested, workflowId, consumerPhaseId) }
        ?: return null
    val storePath = resolution.storePath?.takeIf(String::isNotBlank) ?: return null
    val reference = FeatureTaskRuntimeSharedReviewEvidenceReference.of(storePath, resolution.artifact)
    return FeatureTaskRuntimeSharedReviewEvidenceResolved(
      reference = reference,
      measurement =
        FeatureTaskRuntimeSharedEvidenceMeasurement(
          workflowId = workflowId,
          checkpointFingerprint = resolution.artifact.fingerprint,
          consumerPhaseId = consumerPhaseId,
          outcome = resolution.outcome.toMeasurementOutcome(),
          fileIndexCount = resolution.artifact.files.size,
          hunkIndexCount = resolution.artifact.hunks.size,
        ),
    )
  }

  private fun derive(
    repoRoot: Path,
    checkpoint: FeatureTaskRuntimeRepositoryCheckpoint,
    workflowId: String,
    consumerPhaseId: String,
  ): FeatureTaskRuntimeSharedEvidenceDerivation? {
    val base = checkpoint.baseRef?.takeIf(String::isNotBlank)
    val head = checkpoint.headRef?.takeIf(String::isNotBlank) ?: "HEAD"
    val ownedPaths = checkpoint.workingTreeOwnedPaths.filter(String::isNotBlank)
    val query =
      when {
        base == null -> ReviewDiffQuery.WorkingTree(head, ownedPaths, includeBinary = false)
        ownedPaths.isEmpty() -> ReviewDiffQuery.CommitRange(base, head)
        else -> ReviewDiffQuery.WorkingTree(base, ownedPaths, includeBinary = false)
      }
    val diff =
      diffResolver.diff(repoRoot, query)
        ?: return recordUnreadableDiff(workflowId, consumerPhaseId, query)
    val evidence =
      try {
        ReviewDiffEvidence.parse(diff)
      } catch (error: IllegalArgumentException) {
        recordParseDegradation(error)
        null
      }
    return FeatureTaskRuntimeSharedEvidenceDerivation(
      baseRef = base,
      headRef = head,
      files =
        evidence?.files.orEmpty().map {
          FeatureTaskRuntimeSharedEvidenceFileEntry(it.path, changeKind(it.oldPath, it.newPath))
        },
      hunks =
        evidence?.hunks.orEmpty().map {
          FeatureTaskRuntimeSharedEvidenceHunkEntry(it.path, it.content.lineSequence().first().ifBlank { "@@" })
        },
      diffPayload = diff,
    )
  }

  private fun recordUnreadableDiff(
    workflowId: String,
    consumerPhaseId: String,
    query: ReviewDiffQuery,
  ): Nothing? {
    log.log(
      Level.WARNING,
      "seam=shared_review_evidence_derive value_used=no_evidence value_expected=derived_evidence " +
        "workflow_id=$workflowId consumer_phase_id=$consumerPhaseId " +
        "cause=Could not read the shared review evidence diff for $query.",
    )
    return null
  }

  private fun recordParseDegradation(error: IllegalArgumentException) {
    log.log(
      Level.WARNING,
      "seam=shared_review_evidence_parse value_used=empty_file_and_hunk_index " +
        "value_expected=parsed_diff_evidence cause=${error.message ?: error.javaClass.simpleName}",
      error,
    )
  }

  private fun changeKind(
    oldPath: String?,
    newPath: String?,
  ): String =
    when {
      oldPath == null -> "added"
      newPath == null -> "deleted"
      oldPath != newPath -> "renamed"
      else -> "modified"
    }
}

private fun FeatureTaskRuntimeSharedEvidenceResolveOutcome.toMeasurementOutcome():
  FeatureTaskRuntimeSharedEvidenceOutcome =
  when (this) {
    FeatureTaskRuntimeSharedEvidenceResolveOutcome.DERIVATION ->
      FeatureTaskRuntimeSharedEvidenceOutcome.DERIVATION
    FeatureTaskRuntimeSharedEvidenceResolveOutcome.REUSE ->
      FeatureTaskRuntimeSharedEvidenceOutcome.REUSE
    FeatureTaskRuntimeSharedEvidenceResolveOutcome.CHECKPOINT_CHANGE_REDERIVATION ->
      FeatureTaskRuntimeSharedEvidenceOutcome.CHECKPOINT_CHANGE_REDERIVATION
  }
