package skillbill.engine.work.changes.scope

import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.ports.workchanges.model.WorkCommitTimelineResult
import skillbill.ports.workchanges.model.WorkGoalTimelineResult
import skillbill.ports.workchanges.model.WorkScopeSubtask
import skillbill.ports.workchanges.model.WorkTimelineCommit
import skillbill.workflow.model.DecompositionStatus

internal fun attributeTimeline(
  issueKey: String,
  subtasks: List<WorkScopeSubtask>,
  timeline: WorkCommitTimelineResult.Listed,
): WorkGoalTimelineResult.Attributed {
  val subtasksById = subtasks.associateBy(WorkScopeSubtask::id)
  val ownedCommits = mutableMapOf<Int, MutableList<String>>()
  val commits =
    timeline.commits.map { commit ->
      val trailers = FeatureTaskRuntimeSubtaskCommitIdentity.parseAll(commit.message)
      val trailerOwners =
        trailers.asSequence()
          .filter { it.issueKey == issueKey }
          .mapNotNull { it.subtaskId.toIntOrNull() }
          .filter(subtasksById::containsKey)
          .distinct()
          .sorted()
          .toList()
      val owners =
        if (trailerOwners.isNotEmpty()) {
          trailerOwners
        } else {
          subtasks
            .asSequence()
            .filter { it.status != DecompositionStatus.COMPLETED_NO_CHANGE && it.recordedSha == commit.sha }
            .map(WorkScopeSubtask::id)
            .sorted()
            .toList()
        }
      owners.forEach { id -> ownedCommits.getOrPut(id) { mutableListOf() }.add(commit.sha) }
      WorkTimelineCommit(commit.sha, commit.parentSha, commit.subject, owners)
    }
  val changedAfterFinishIds =
    subtasks.asSequence()
      .filter { it.status == DecompositionStatus.COMPLETE }
      .filter { subtask ->
        val recordedSha = subtask.recordedSha ?: return@filter false
        val owned = ownedCommits[subtask.id].orEmpty()
        (owned.isNotEmpty() && owned.last() != recordedSha) || recordedSha !in timeline.reachableRecordedShas
      }
      .map(WorkScopeSubtask::id)
      .toSet()
  return WorkGoalTimelineResult.Attributed(timeline.mergeBaseSha, timeline.headSha, commits, changedAfterFinishIds)
}
