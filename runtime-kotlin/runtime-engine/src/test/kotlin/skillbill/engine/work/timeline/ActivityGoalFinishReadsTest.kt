package skillbill.engine.work.timeline

import skillbill.engine.work.timeline.model.WorkActivityGroup
import skillbill.engine.work.timeline.model.WorkActivityStatus
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.UnitOfWork
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFailure
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentIdentityAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReasonRetention
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReport
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentScope
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityGoalFinishReadsTest {
  @Test
  fun `parent incidents before and during the second subtask never imply goal finish`() {
    establishTemporarySchemaReadiness("activity-parent-interruptions").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      database.read { unit ->
        val incidents = listOf(incident("old", 5), incident("current", 35))
        val repository =
          object : BlockIncidentRepository by unit.blockIncidents {
            override fun listForWorkPage(
              workflowId: String,
              offset: Int,
              limit: Int,
            ) = incidents
          }
        val reads =
          object : UnitOfWork by unit {
            override val blockIncidents = repository
          }
        listOf(WorkActivityStatus.RUNNING, WorkActivityStatus.BLOCKED, WorkActivityStatus.INTERRUPTED).forEach {
            status ->
          val state = if (status == WorkActivityStatus.BLOCKED) "blocked" else "running"
          val goal = WorkItem("SKILL-455", WorkItemKind.FEATURE_GOAL, "goal", at(0), state, at(0), false)
          val children =
            listOf(
              WorkActivityGroup(
                "first",
                1,
                "First",
                WorkActivityStatus.COMPLETE,
                at(0),
                at(20),
                emptyList(),
                emptyList(),
              ),
              WorkActivityGroup(
                "second",
                2,
                "Second",
                status,
                at(30),
                null,
                emptyList(),
                emptyList(),
              ),
              WorkActivityGroup("third", 3, "Third", WorkActivityStatus.PENDING, null, null, emptyList(), emptyList()),
            )
          val group = ActivityGoalFinishReads().groups(goal, children, emptyList(), reads).single()
          assertEquals("goal-interruptions", group.id)
          assertEquals(listOf("old", "current"), group.blocks.map { it.id })
          assertEquals(listOf("plan_subtasks", "plan_subtasks"), group.blocks.map { it.phaseId })
          assertEquals(WorkActivityStatus.INTERRUPTED, group.status)
          assertEquals(emptyList(), group.after)
        }
      }
    }
  }

  @Test
  fun `interrupted latest child does not place live progress under goal finish`() {
    establishTemporarySchemaReadiness("activity-finish-progress").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      database.read { unit ->
        val progress =
          GoalProgressEvent(
            GoalProgressEventKind.OPERATION_HEARTBEAT, "goal", "implement", true, 1, at(40),
            stepId = "implement", operationName = "working", agentId = "codex",
          )
        val artifacts =
          DurableWorkflowArtifacts.fromMap(
            mapOf(DurableWorkflowArtifactFamily.GOAL_PROGRESS_LATEST_EVENT.label() to progress.toPersistenceWire()),
          )
        val workflows =
          object : WorkflowStateRepository by unit.workflowStates {
            override fun readFeatureTaskArtifacts(
              workflowId: String,
              families: Set<DurableWorkflowArtifactFamily>,
            ) = artifacts
          }
        val reads =
          object : UnitOfWork by unit {
            override val workflowStates = workflows
          }
        val goal = WorkItem("SKILL-455", WorkItemKind.FEATURE_GOAL, "goal", at(0), "running", at(0), false)
        val child =
          WorkActivityGroup(
            "child",
            1,
            "Child",
            WorkActivityStatus.INTERRUPTED,
            at(20),
            at(30),
            emptyList(),
            emptyList(),
          )
        val previous = child.copy(id = "previous", status = WorkActivityStatus.COMPLETE, startedAt = at(0))
        assertEquals(emptyList(), ActivityGoalFinishReads().groups(goal, listOf(previous, child), emptyList(), reads))
        val finished = child.copy(status = WorkActivityStatus.COMPLETE)
        val group = ActivityGoalFinishReads().groups(goal, listOf(previous, finished), emptyList(), reads).single()
        assertEquals("goal-finish", group.id)
        assertEquals("implement", group.after.single().name)
      }
    }
  }

  private fun incident(
    token: String,
    seconds: Long,
  ) = BlockIncident(
    BlockIncidentOccurrence(
      occurrenceToken = token,
      scope = BlockIncidentScope(workflowId = "goal", phaseId = "plan_subtasks"),
      failure =
        BlockIncidentFailure(
          origin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
          reason = "Provider usage limit during child audit",
          reasonRetention = BlockIncidentReasonRetention.COMPLETE,
          exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
        ),
      observedAt = at(seconds),
      evidenceAvailability = BlockIncidentEvidenceAvailability.DATABASE,
      identityAvailability = BlockIncidentIdentityAvailability.PARTIAL,
      fieldAvailability = emptyMap(),
    ),
    emptyList(),
    BlockIncidentState(
      BlockIncidentDiagnosis.INVESTIGATING,
      BlockIncidentRecovery.UNAVAILABLE,
      BlockIncidentReport.UNAVAILABLE,
      1,
      at(100),
      0,
      null,
      null,
    ),
  )

  private fun at(seconds: Long) = Instant.EPOCH.plusSeconds(seconds)
}
