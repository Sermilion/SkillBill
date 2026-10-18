package dev.skillbill.tray.core.data.store

import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.model.HistoryFilter
import org.junit.jupiter.api.Test
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.sql.Connection
import java.sql.Types
import java.time.Instant
import kotlin.test.assertEquals

class WorkCandidateQueriesTest {
  private val now = Instant.now()

  @Test
  fun `goal phases stay out of live sections after cancellation while unrelated and later standalone phases remain selectable`() {
    establishTemporarySchemaReadiness("desktop-goal-phase-ownership").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      val repository = "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}"
      fixture.connection.prepareStatement(
        "INSERT INTO goal_issue_progress (parent_workflow_id, issue_key, first_started_at, status, state_entered_at) " +
          "VALUES ('goal', 'SKILL-428', ?, 'running', ?)",
      ).use { statement ->
        statement.setString(1, now.minusSeconds(60).toString())
        statement.setString(2, now.toString())
        statement.executeUpdate()
      }
      factory.transaction { unit ->
        unit.goalRunnerControls.persistControlState(
          "goal",
          GoalRunnerControlState(
            repositoryIdentity = repository,
            executionLease = GoalRunnerExecutionLease(
              1, "goal-owner", "host", "boot", 1, "birth", now, now.plusSeconds(300),
            ),
          ),
        )
        val related = StandalonePhaseStatusRegistration(
          repository, "main", " skill-428 ", null, "related-phase", FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, "related-execution",
          "paused", "runner_interrupted", now, "phase-owner", 1, now.plusSeconds(300),
        )
        unit.standalonePhaseStatuses.register(related)
        unit.standalonePhaseStatuses.register(
          related.copy(phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
            invocationId = "replan", executionId = "replan-execution"),
        )
        unit.standalonePhaseStatuses.register(
          related.copy(phaseId = "monitor", invocationId = "independent-monitor", executionId = "monitor-execution"),
        )
        unit.standalonePhaseStatuses.register(
          related.copy(issueKey = "SKILL-429", invocationId = "other-task", executionId = "other-task-execution"),
        )
        unit.standalonePhaseStatuses.register(
          related.copy(repositoryIdentity = "$repository/other", invocationId = "other-repository", executionId = "other-repo-execution"),
        )
        unit.standalonePhaseStatuses.register(
          related.copy(issueKey = null, invocationId = "keyless", executionId = "keyless-execution"),
        )
        val completed = unit.standalonePhaseStatuses.register(
          related.copy(phaseId = "validation", invocationId = "completed", executionId = "completed-execution", lifecycleState = "active"),
        )
        val update = unit.standalonePhaseStatuses.update(
          StandalonePhaseStatusUpdate(
            completed.executionId, completed.statusRevision, completed.leaseOwner, completed.leaseGeneration,
            "terminal", "validation", null, now, finishedAt = now, terminalResult = "completed",
          ),
        )
        assertEquals(StandalonePhaseStatusUpdateResult.ACCEPTED, update)
      }

      val unrelated = setOf("other-task", "other-repository", "keyless", "independent-monitor")
      assertEquals(unrelated, fixture.connection.candidates(WorkSection.PHASES, now))
      assertEquals(unrelated + "goal", fixture.connection.candidates(WorkSection.NEEDS_YOU, now))
      assertEquals(setOf("goal"), fixture.connection.candidates(WorkSection.GOALS, now))
      assertEquals(setOf("completed"), fixture.connection.candidates(WorkSection.HISTORY, now))
      assertEquals(unrelated, fixture.connection.candidates(WorkSection.PHASES, now.plusSeconds(301)))

      fixture.connection.createStatement().use { statement ->
        statement.executeUpdate("UPDATE goal_issue_progress SET status = 'abandoned', state_entered_at = '$now' WHERE parent_workflow_id = 'goal'")
      }
      assertEquals(unrelated, fixture.connection.candidates(WorkSection.PHASES, now))
      assertEquals(unrelated, fixture.connection.candidates(WorkSection.NEEDS_YOU, now))
      assertEquals(setOf("goal", "completed"), fixture.connection.candidates(WorkSection.HISTORY, now))

      factory.transaction { unit ->
        unit.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            repository, "main", "SKILL-428", null, "later-phase", FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, "later-execution",
            "active", "preplan", now.plusSeconds(1), "later-owner", 1, now.plusSeconds(300),
          ),
        )
      }
      assertEquals(unrelated + "later-phase", fixture.connection.candidates(WorkSection.PHASES, now.plusSeconds(2)))
      fixture.connection.createStatement().use { it.executeUpdate("DELETE FROM goal_issue_progress WHERE parent_workflow_id = 'goal'") }
      assertEquals(unrelated + setOf("related-phase", "replan", "later-phase"), fixture.connection.candidates(WorkSection.PHASES, now.plusSeconds(2)))
    }
  }

  @Test
  fun `completed goal planning stops appearing as a separate finished run once its goal exists`() {
    establishTemporarySchemaReadiness("desktop-completed-goal-plan").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      val repository = "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}"
      factory.transaction { unit ->
        val registration = StandalonePhaseStatusRegistration(
          repository, "main", " skill-419 ", null, "goal-plan", FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
          "goal-plan-execution", "active", "preplan", now.minusSeconds(60), "phase-owner", 1, now.plusSeconds(300),
          launchScope = IdeStatusLaunchScope.GOAL,
        )
        for (input in listOf(registration, registration.copy(
          repositoryIdentity = "$repository/other", invocationId = "other-repository", executionId = "other-execution",
        ))) {
          val record = unit.standalonePhaseStatuses.register(input)
          assertEquals(StandalonePhaseStatusUpdateResult.ACCEPTED, unit.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              record.executionId, record.statusRevision, record.leaseOwner, record.leaseGeneration,
              "terminal", "plan_subtasks", null, now.minusSeconds(1), finishedAt = now.minusSeconds(1), terminalResult = "completed",
            ),
          ))
        }
      }
      for (section in listOf(WorkSection.RECENT, WorkSection.HISTORY)) {
        assertEquals(setOf("goal-plan", "other-repository"), fixture.connection.candidates(section, now))
      }
      fixture.connection.prepareStatement(
        "INSERT INTO goal_issue_progress (parent_workflow_id, issue_key, first_started_at, status, state_entered_at) " +
          "VALUES ('goal', 'SKILL-419', ?, 'running', ?)",
      ).use { statement ->
        statement.setString(1, now.toString())
        statement.setString(2, now.toString())
        statement.executeUpdate()
      }
      factory.transaction { unit ->
        unit.goalRunnerControls.persistControlState("goal", GoalRunnerControlState(repositoryIdentity = repository))
      }
      for (section in listOf(WorkSection.RECENT, WorkSection.HISTORY)) {
        assertEquals(setOf("other-repository"), fixture.connection.candidates(section, now))
        assertEquals(setOf("other-repository"), fixture.connection.candidates(section, now, HistoryFilter.GOALS))
      }
      fixture.connection.createStatement().use { statement ->
        statement.executeUpdate("UPDATE goal_issue_progress SET status = 'completed', state_entered_at = '$now' WHERE parent_workflow_id = 'goal'")
      }
      for (section in listOf(WorkSection.RECENT, WorkSection.HISTORY)) {
        assertEquals(setOf("goal", "other-repository"), fixture.connection.candidates(section, now))
      }
    }
  }

  private fun Connection.candidates(section: WorkSection, observedAt: Instant, historyFilter: HistoryFilter = HistoryFilter.ALL): Set<String> {
    val query = candidateQuery(section, observedAt.minusSeconds(3600).toString(), observedAt.toString(), null, null, "", 100, historyFilter)
    return prepareStatement(query.sql).use { statement ->
      var index = 1
      statement.setString(index++, observedAt.toString())
      if (section in setOf(WorkSection.HISTORY, WorkSection.RECENT)) {
        statement.setString(index++, observedAt.minusSeconds(3600).toString())
        statement.setString(index++, observedAt.toString())
      }
      statement.setString(index++, observedAt.toString())
      statement.setString(index++, "")
      statement.setString(index++, "")
      repeat(4) { statement.setNull(index++, Types.VARCHAR) }
      statement.setInt(index, 100)
      statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString("id")) } }
    }
  }
}
