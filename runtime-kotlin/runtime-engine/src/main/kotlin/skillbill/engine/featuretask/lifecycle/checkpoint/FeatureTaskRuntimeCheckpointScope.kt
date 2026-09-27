package skillbill.engine.featuretask.lifecycle.checkpoint

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointDecision
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.featuretask.runloop.core.isFeatureSpecPathForIssue
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunEvidenceOwnership
import java.util.Locale

private const val RUNTIME_PRIVATE_ROOT = ".skill-bill/"
private const val RUNTIME_TRACKABLE_CONFIG = ".skill-bill/config.yaml"
private const val MAX_REPORTED_PATHS = 10

object FeatureTaskRuntimeCheckpointScope {
  fun decide(input: FeatureTaskRuntimeCheckpointScopeInput): FeatureTaskRuntimeCheckpointDecision {
    val runtimeOwned: (String) -> Boolean = { path ->
      isRuntimePrivatePath(path) ||
        FeatureTaskRuntimeRunEvidenceOwnership.isOwnedByRun(path, input.workflowId)
    }
    val deleted = sanitized(input.deletedPaths, runtimeOwned)
    val implementationPaths =
      sanitized(
        input.ownedPaths +
          input.phaseIntroducedPaths +
          input.concurrentlyModifiedOwnedPaths +
          deleted,
        runtimeOwned,
      ).filterNot { isFeatureSpecPathForIssue(it, input.issueKey) }
    val implementationAliases =
      implementationPaths
        .groupBy(::normalizeForAliasComparison)
        .mapValues { (_, paths) -> paths.first() }
    val stageable =
      sanitized(
        input.worktreeDeltaPaths +
          input.phaseIntroducedPaths +
          input.foreignStagedPaths +
          input.concurrentlyModifiedOwnedPaths +
          deleted,
        runtimeOwned,
      ).filterNot { isFeatureSpecPathForIssue(it, input.issueKey) }
        .mapNotNull { path ->
          implementationAliases[normalizeForAliasComparison(path)]
        }.distinct().sorted()
    val adopted =
      sanitized(
        input.foreignStagedPaths +
          input.concurrentlyModifiedOwnedPaths +
          deleted,
        runtimeOwned,
      ).filterNot { isFeatureSpecPathForIssue(it, input.issueKey) }
        .mapNotNull { path ->
          implementationAliases[normalizeForAliasComparison(path)]
        }.distinct().sorted()
    return if (stageable.isEmpty()) {
      FeatureTaskRuntimeCheckpointDecision.Skip
    } else {
      FeatureTaskRuntimeCheckpointDecision.Stage(stageable, adopted)
    }
  }
}

internal fun sanitized(
  paths: Collection<String>,
  runtimeOwned: (String) -> Boolean,
): List<String> = paths.filter(String::isNotBlank).filterNot(runtimeOwned)

fun isRuntimePrivatePath(path: String): Boolean {
  val normalized = normalizeForAliasComparison(path)
  if (normalized == RUNTIME_TRACKABLE_CONFIG) return false
  if (FeatureTaskRuntimeRunEvidenceOwnership.isRunEvidencePath(normalized)) return false
  return normalized == RUNTIME_PRIVATE_ROOT.trimEnd('/') ||
    normalized.startsWith(RUNTIME_PRIVATE_ROOT)
}

fun goalScopedBaselinePaths(
  ownBaseline: List<String>,
  goalStartBaseline: List<String>?,
): List<String> {
  val goalStart = goalStartBaseline?.toSet() ?: return ownBaseline
  return ownBaseline.filter { it in goalStart }
}

fun phaseWrittenPaths(
  worktreeDeltaPaths: List<String>,
  phaseManifestPaths: List<String>,
): List<String> {
  val manifest = phaseManifestPaths.filter(String::isNotBlank).map(::normalizeForAliasComparison)
  if (manifest.isEmpty()) return emptyList()
  return worktreeDeltaPaths.filter(String::isNotBlank)
    .filterNot(::isRuntimePrivatePath)
    .filter { path ->
      val normalized = normalizeForAliasComparison(path)
      manifest.any { entry -> normalized == entry || normalized.startsWith("$entry/") }
    }.distinct().sorted()
}

fun reviewUntrackedExclusions(
  baselineUntrackedPaths: List<String>,
  currentUntrackedPaths: List<String>,
  ownedPaths: List<String>,
): List<String> {
  val ownedAliases = ownedPaths.map(::normalizeForAliasComparison).toSet()
  val foreign =
    currentUntrackedPaths.filter(String::isNotBlank)
      .filterNot { normalizeForAliasComparison(it) in ownedAliases }
  return (baselineUntrackedPaths + foreign).filter(String::isNotBlank).distinct().sorted()
}

fun adoptionWarning(
  branch: String,
  paths: List<String>,
): String =
  "Feature-task-runtime checkpoint adopted owned path(s) ${formatCheckpointPaths(paths)} whose index or " +
    "working-tree content diverged from what this run wrote. The working-tree content is committed " +
    "to '$branch' as this workflow's work rather than blocking the run."

fun normalizeForAliasComparison(path: String): String = path.trim().trimEnd('/').lowercase(Locale.ROOT)

private fun formatCheckpointPaths(paths: List<String>): String {
  val reported = paths.take(MAX_REPORTED_PATHS).joinToString(", ") { "'$it'" }
  val overflow = paths.size - MAX_REPORTED_PATHS
  return if (overflow > 0) "$reported (+$overflow more)" else reported
}

class FeatureTaskRuntimeCheckpointMetadata(
  val phaseId: String,
  val loopId: String?,
  val generation: Int,
  val branch: String,
  val intent: String,
) {
  override fun toString(): String =
    buildList {
      add("phase=$phaseId")
      loopId?.takeIf(String::isNotBlank)?.let { add("loop=$it") }
      add("generation=$generation")
    }.joinToString(" ")
}

object FeatureTaskRuntimeCheckpointMessage {
  fun build(
    issueKey: String,
    subtaskName: String?,
    metadata: FeatureTaskRuntimeCheckpointMetadata,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): String {
    return compose(subject(issueKey, subtaskName, identity.subtaskId), metadata, identity)
  }

  fun subject(
    issueKey: String,
    subtaskName: String?,
    subtaskId: String,
  ): String =
    subtaskName?.trim()?.takeIf(String::isNotBlank)?.let { "$issueKey: $it" }
      ?: fallbackSubject(issueKey, subtaskId)

  fun finalise(
    subject: String,
    metadata: FeatureTaskRuntimeCheckpointMetadata,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): String = compose(subject.trim(), metadata, identity)

  private fun compose(
    subject: String,
    metadata: FeatureTaskRuntimeCheckpointMetadata,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): String {
    val body = "${metadata.intent} checkpoint on '${metadata.branch}'"
    return "$subject\n\n$body\n$metadata\n\n${identity.trailer}\n"
  }

  fun fallbackSubject(
    issueKey: String,
    subtaskId: String,
  ): String = "$issueKey: subtask $subtaskId"

  fun missingSubtaskNameRecord(
    issueKey: String,
    subtaskId: String,
  ): String =
    "seam=FeatureTaskRuntimeCheckpointMessage.build value_used='${fallbackSubject(issueKey, subtaskId)}' " +
      "value_expected=manifest subtask name for '$issueKey' subtask '$subtaskId' " +
      "cause=the durable goal-continuation row carried no subtask name; the commit subject " +
      "degrades to the issue key and subtask id"

  const val INTENT_AUDITED_IMPLEMENTATION: String = "audited implementation"
  const val INTENT_REMEDIATION: String = "remediation"
  const val INTENT_FINALISED_SUBTASK: String = "finalised subtask"
}
