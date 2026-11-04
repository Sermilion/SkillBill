package skillbill.engine.work.timeline

import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.work.changes.scope.attributeTimeline
import skillbill.ports.workchanges.model.WorkBranchCommit
import skillbill.ports.workchanges.model.WorkCommitTimelineResult
import skillbill.ports.workchanges.model.WorkScopeSubtask
import skillbill.workflow.model.DecompositionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkTimelineAttributionTest {
  @Test
  fun `two trailers own a squash commit and mark both completed tasks changed`() {
    val commit = commit("new", "Skill-Bill-Subtask: SKILL-1/1\nSkill-Bill-Subtask: SKILL-1/2")
    val result =
      attribute(
        "SKILL-1",
        listOf(task(1, "old-1"), task(2, "old-2")),
        listed(commit, setOf("old-1", "old-2")),
      )

    assertEquals(listOf(1, 2), result.commits.single().ownerSubtaskIds)
    assertEquals(setOf(1, 2), result.changedAfterFinishIds)
  }

  @Test
  fun `foreign issue trailer does not suppress recorded sha ownership`() {
    val commit = commit("recorded", "Skill-Bill-Subtask: OTHER-1/1")
    val result = attribute("SKILL-1", listOf(task(1, "recorded")), listed(commit, setOf("recorded")))

    assertEquals(listOf(1), result.commits.single().ownerSubtaskIds)
    assertEquals(emptySet(), result.changedAfterFinishIds)
  }

  @Test
  fun `unknown trailer id does not suppress recorded sha ownership`() {
    val commit = commit("recorded", "Skill-Bill-Subtask: SKILL-1/99")
    val result = attribute("SKILL-1", listOf(task(1, "recorded")), listed(commit, setOf("recorded")))

    assertEquals(listOf(1), result.commits.single().ownerSubtaskIds)
    assertEquals(emptySet(), result.changedAfterFinishIds)
  }

  @Test
  fun `recorded sha ownership applies when no trailer exists`() {
    val result =
      attribute(
        "SKILL-1",
        listOf(task(1, "recorded")),
        listed(commit("recorded", "ordinary commit"), setOf("recorded")),
      )

    assertEquals(listOf(1), result.commits.single().ownerSubtaskIds)
  }

  @Test
  fun `dropped trailer leaves the commit operator-owned and flags a pruned recorded sha`() {
    val commit = commit("amended", "rewritten message")
    val result = attribute("SKILL-1", listOf(task(1, "pruned")), listed(commit, emptySet()))

    assertEquals(emptyList(), result.commits.single().ownerSubtaskIds)
    assertEquals(setOf(1), result.changedAfterFinishIds)
  }

  @Test
  fun `no-change subtask never owns a matching sha or gets a badge`() {
    val commit = commit("recorded", "ordinary commit")
    val noChange =
      WorkScopeSubtask(
        1,
        "no change",
        DecompositionStatus.COMPLETED_NO_CHANGE,
        false,
        "recorded",
        emptyList(),
      )
    val result = attribute("SKILL-1", listOf(noChange), listed(commit, emptySet()))

    assertEquals(emptyList(), result.commits.single().ownerSubtaskIds)
    assertEquals(emptySet(), result.changedAfterFinishIds)
  }

  @Test
  fun `non integer trailer id does not suppress recorded sha ownership`() {
    val commit = commit("recorded", "Skill-Bill-Subtask: SKILL-1/abc")
    val result = attribute("SKILL-1", listOf(task(1, "recorded")), listed(commit, setOf("recorded")))

    assertEquals(listOf(1), result.commits.single().ownerSubtaskIds)
    assertEquals(emptySet(), result.changedAfterFinishIds)
  }

  @Test
  fun `irrelevant trailer mixture does not suppress recorded sha ownership`() {
    val commit =
      commit(
        "recorded",
        "Skill-Bill-Subtask: OTHER-1/1\nSkill-Bill-Subtask: SKILL-1/99\nSkill-Bill-Subtask: SKILL-1/abc",
      )
    val result = attribute("SKILL-1", listOf(task(1, "recorded")), listed(commit, setOf("recorded")))

    assertEquals(listOf(1), result.commits.single().ownerSubtaskIds)
    assertEquals(emptySet(), result.changedAfterFinishIds)
  }

  @Test
  fun `known current issue owner remains authoritative with irrelevant trailers`() {
    val commit =
      commit(
        "recorded",
        "Skill-Bill-Subtask: SKILL-1/2\nSkill-Bill-Subtask: OTHER-1/1\nSkill-Bill-Subtask: SKILL-1/99",
      )
    val result =
      attribute(
        "SKILL-1",
        listOf(task(1, "recorded"), task(2, "older")),
        listed(commit, setOf("recorded", "older")),
      )

    assertEquals(listOf(2), result.commits.single().ownerSubtaskIds)
    assertEquals(setOf(2), result.changedAfterFinishIds)
  }

  @Test
  fun `unmatched recorded sha leaves commit operator-owned`() {
    val commit = commit("operator-commit", "Skill-Bill-Subtask: OTHER-1/1")
    val result = attribute("SKILL-1", listOf(task(1, "recorded")), listed(commit, setOf("recorded")))

    assertEquals(emptyList(), result.commits.single().ownerSubtaskIds)
    assertEquals(emptySet(), result.changedAfterFinishIds)
  }

  @Test
  fun `recorded sha reachable behind the merge base does not mark an unchanged task`() {
    val later = commit("later", "ordinary commit")
    val task = task(1, "recorded-before-base")
    val result = attribute("SKILL-1", listOf(task), listed(later, setOf("recorded-before-base")))

    assertEquals(emptySet(), result.changedAfterFinishIds)
  }

  @Test
  fun `identity writer trailer parses through the timeline reader`() {
    val identity = FeatureTaskRuntimeSubtaskCommitIdentity("SKILL-1", "2")
    val parsed = FeatureTaskRuntimeSubtaskCommitIdentity.parseAll("subject\n\n${identity.trailer}")

    assertEquals(listOf(identity), parsed)
  }

  private fun attribute(
    issue: String,
    tasks: List<WorkScopeSubtask>,
    timeline: WorkCommitTimelineResult.Listed,
  ) = attributeTimeline(issue, tasks, timeline)

  private fun task(
    id: Int,
    recordedSha: String,
  ) = WorkScopeSubtask(
    id,
    "task $id",
    DecompositionStatus.COMPLETE,
    false,
    recordedSha,
    emptyList(),
  )

  private fun commit(
    sha: String,
    message: String,
  ) = WorkBranchCommit(sha, "parent", "subject", message)

  private fun listed(
    commit: WorkBranchCommit,
    reachable: Set<String>,
  ) = WorkCommitTimelineResult.Listed(
    "base",
    commit.sha,
    "feat/SKILL-1",
    listOf(commit),
    reachable,
  )
}
