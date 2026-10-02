package skillbill.application.reviewevidence

import skillbill.application.reviewevidence.model.DiffResolution
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDerivation
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.text.RECORD_FIELD_SEPARATOR
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceFileEntry
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceHunkEntry
import java.nio.file.Path

internal data class SharedReviewEvidenceQuery(
  val repoRoot: Path,
  val workflowId: String,
  val scope: ParallelReviewScope,
  val range: ReviewCommitRange,
  val suppliedDiff: Boolean,
)

internal class SharedReviewEvidenceResolution(
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  private val diffResolver: DiffResolverPort,
) {
  internal fun resolve(
    query: SharedReviewEvidenceQuery,
    resolveAggregateDiff: () -> DiffResolution<String>,
  ): DiffResolution<SharedReviewEvidenceRecord> {
    val deriveRecord = { derive(query, resolveAggregateDiff) }
    val checkpoint = checkpoint(query)
    if (checkpoint == null) {
      return when (val record = deriveRecord()) {
        is DiffResolution.Unresolved -> record
        is DiffResolution.Resolved -> DiffResolution.Resolved(persistAlreadyDerived(query, record.value))
      }
    }
    var derived: SharedReviewEvidenceRecord? = null
    var unresolved: DiffResolution.Unresolved? = null
    val resolution =
      sharedEvidenceResolver.resolve(
        FeatureTaskRuntimeSharedEvidenceRequest(query.repoRoot, query.workflowId, checkpoint),
      ) {
        when (val record = deriveRecord()) {
          is DiffResolution.Unresolved -> {
            unresolved = record
            null
          }
          is DiffResolution.Resolved -> {
            derived = record.value
            derivationOf(record.value)
          }
        }
      }
    if (resolution == null) {
      return unresolved ?: DiffResolution.Unresolved("Shared review evidence could not be derived.")
    }
    val record = derived ?: SharedReviewEvidenceCodec.decode(resolution.diffPayload)
    if (record != null) {
      return DiffResolution.Resolved(record.copy(storePath = resolution.storePath))
    }
    return when (val rederived = deriveRecord()) {
      is DiffResolution.Unresolved -> rederived
      is DiffResolution.Resolved -> DiffResolution.Resolved(rederived.value.copy(storePath = resolution.storePath))
    }
  }

  private fun derive(
    query: SharedReviewEvidenceQuery,
    resolveAggregateDiff: () -> DiffResolution<String>,
  ): DiffResolution<SharedReviewEvidenceRecord> {
    val aggregateDiff =
      when (val resolved = resolveAggregateDiff()) {
        is DiffResolution.Unresolved -> return resolved
        is DiffResolution.Resolved -> resolved.value
      }
    return when (
      val sequence =
        SharedReviewEvidenceAssembler(diffResolver)
          .assemble(query.scope, query.repoRoot, query.range, query.suppliedDiff)
    ) {
      is DiffResolution.Unresolved -> sequence
      is DiffResolution.Resolved ->
        DiffResolution.Resolved(SharedReviewEvidenceRecord(aggregateDiff = aggregateDiff, sequence = sequence.value))
    }
  }

  private fun persistAlreadyDerived(
    query: SharedReviewEvidenceQuery,
    record: SharedReviewEvidenceRecord,
  ): SharedReviewEvidenceRecord {
    val checkpoint =
      FeatureTaskRuntimeRepositoryCheckpoint(
        fingerprint = sha256HexUtf8(record.aggregateDiff),
        baseRef = query.range.baseRevision,
        headRef = query.range.headRevision,
      )
    val resolution =
      sharedEvidenceResolver.resolve(
        FeatureTaskRuntimeSharedEvidenceRequest(query.repoRoot, query.workflowId, checkpoint),
      ) {
        derivationOf(record)
      }
    return record.copy(storePath = resolution?.storePath)
  }

  private fun checkpoint(query: SharedReviewEvidenceQuery): FeatureTaskRuntimeRepositoryCheckpoint? {
    val scope = query.scope
    val range = query.range
    if (query.suppliedDiff) return null
    if (scope != ParallelReviewScope.BRANCH && scope != ParallelReviewScope.PR) return null
    val key = listOf(scope.name, range.baseRevision, range.headRevision)
    return FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = sha256HexUtf8(key.joinToString(RECORD_FIELD_SEPARATOR)),
      baseRef = range.baseRevision,
      headRef = range.headRevision,
    )
  }

  private fun derivationOf(record: SharedReviewEvidenceRecord): FeatureTaskRuntimeSharedEvidenceDerivation {
    val evidence = runCatching { ReviewDiffEvidence.parse(record.aggregateDiff) }.getOrNull()
    return FeatureTaskRuntimeSharedEvidenceDerivation(
      baseRef = record.sequence.baseRevision,
      headRef = record.sequence.headRevision,
      files =
        evidence?.files.orEmpty().map {
          FeatureTaskRuntimeSharedEvidenceFileEntry(it.path, changeKind(it.oldPath, it.newPath))
        },
      hunks =
        evidence?.hunks.orEmpty().map {
          FeatureTaskRuntimeSharedEvidenceHunkEntry(it.path, it.content.lineSequence().first().ifBlank { "@@" })
        },
      diffPayload = SharedReviewEvidenceCodec.encode(record),
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
