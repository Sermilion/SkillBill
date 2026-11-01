package skillbill.engine.work.timeline

import skillbill.engine.work.timeline.model.WorkActivityGroup
import skillbill.engine.work.timeline.model.WorkActivityGroupKind
import skillbill.engine.work.timeline.model.WorkActivityResult
import skillbill.engine.work.timeline.model.WorkActivityStatus
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.UnitOfWork
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.work.model.WorkActivityAttempt
import skillbill.ports.work.model.WorkActivityStop
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureTaskRuntimePhaseLedgerPage
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseExecutionOrigin
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class WorkActivityServiceTest {
  @Test
  fun `unmatched goal stops stay in general blocks including later PR failures`() {
    establishTemporarySchemaReadiness("activity-finish").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      database.read { unit ->
        val goal = WorkItem("SKILL-455", WorkItemKind.FEATURE_GOAL, "goal", at(0), "running", at(0), false)
        val child =
          WorkActivityGroup(
            "child",
            1,
            "Child",
            WorkActivityStatus.COMPLETE,
            at(0),
            at(10),
            emptyList(),
            emptyList(),
          )
        val stops =
          listOf(
            WorkActivityStop("early", at(0), at(5), "BLOCKED"),
            WorkActivityStop("decision", at(6), at(7), "AWAITING_OPERATOR_DECISION"),
            WorkActivityStop("pr", at(10), at(20), "PULL_REQUEST_FAILED"),
          )
        val priorAttempt = child.copy(id = "prior", status = WorkActivityStatus.BLOCKED, finishedAt = at(3))
        val groups =
          ActivityGoalFinishReads().groups(
            goal,
            listOf(priorAttempt, child.copy(startedAt = at(4))),
            stops,
            unit,
          )
        val interruptions = groups.single { it.id == "goal-interruptions" }
        assertEquals(listOf("early", "decision", "pr"), interruptions.blocks.map { it.id })
        assertEquals(WorkActivityGroupKind.GENERAL_BLOCKS, interruptions.kind)
        assertFalse(interruptions.blocks.single { it.id == "decision" }.needsDecision)
        assertEquals(WorkActivityStatus.INTERRUPTED, interruptions.status)
        assertEquals(1, groups.size)
        assertEquals(emptyList(), interruptions.phases)
        assertEquals(null, interruptions.startedAt)
        assertEquals(null, interruptions.finishedAt)
      }
    }
  }

  @Test
  fun `completed workflow overrides stale blocked event and completed phase artifacts are reused`() {
    establishTemporarySchemaReadiness("activity-service").use { fixture ->
      val delegate = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      var phaseReads = 0
      val goal = WorkItem("SKILL-455", WorkItemKind.FEATURE_GOAL, "goal", at(0), "running", at(0), false)
      val work =
        object : WorkListRepository by EmptyWorkListRepository {
          override fun findByWorkflowId(workflowId: String) = listOf(goal)

          override fun goalActivityAttempts(
            parentWorkflowId: String,
            offset: Int,
            limit: Int,
          ) = listOf(WorkActivityAttempt("child", 3, "Child", "completed", "blocked", at(0), at(10), "Old block"))
        }
      val database =
        object : DatabaseSessionFactory by delegate {
          override fun <T> read(block: (UnitOfWork) -> T): T =
            delegate.read { unit ->
              val workflows =
                object : WorkflowStateRepository by unit.workflowStates {
                  override fun readFeatureTaskPhaseLedgerPage(
                    workflowId: String,
                    offset: Int,
                    limit: Int,
                  ): FeatureTaskRuntimePhaseLedgerPage? {
                    phaseReads++
                    return FeatureTaskRuntimePhaseLedgerPage(emptyList(), null)
                  }

                  override fun readFeatureTaskArtifacts(
                    workflowId: String,
                    families: Set<DurableWorkflowArtifactFamily>,
                  ) = DurableWorkflowArtifacts.EMPTY
                }
              block(
                object : UnitOfWork by unit {
                  override val workList = work
                  override val workflowStates = workflows
                },
              )
            }
        }
      val service =
        WorkActivityService(
          database,
          ActivityPhaseReads(),
          ActivityGoalFinishReads(),
          NoopRuntimeDiagnostics,
        )
      val first = assertIs<WorkActivityResult.Listed>(service.timeline("goal"))
      assertEquals(WorkActivityStatus.COMPLETE, first.groups.first().status)
      assertEquals("Old block", first.groups.first().blocks.single().reason)
      assertEquals(first, service.timeline("goal"))
      assertEquals(1, phaseReads)
    }
  }

  @Test
  fun `phase attempts ignore resume and hydrated starts and never borrow the latest model`() {
    establishTemporarySchemaReadiness("activity-attempts").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      database.read { unit ->
        val entries =
          listOf(
            entry(0, 1, FeatureTaskRuntimePhaseLedgerAction.START),
            entry(1, 1, FeatureTaskRuntimePhaseLedgerAction.RESUME),
            entry(2, 1, FeatureTaskRuntimePhaseLedgerAction.COMPLETE),
            entry(3, 2, FeatureTaskRuntimePhaseLedgerAction.START),
            entry(4, 2, FeatureTaskRuntimePhaseLedgerAction.COMPLETE),
            entry(5, 3, FeatureTaskRuntimePhaseLedgerAction.START).copy(
              executionOrigin = FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED,
            ),
          )
        val record =
          FeatureTaskRuntimePhaseRecord(
            "audit",
            WorkflowStepStatus.COMPLETED,
            2,
            at(3),
            finishedAt = at(4),
            resolvedAgentId = "codex",
            launchedModel = "claude-opus",
            launchedEffort = "high",
          )
        val artifacts =
          DurableWorkflowArtifacts.fromMap(
            mapOf(
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label() to
                mapOf(record.phaseId to record.asWorkflowArtifactEntry()),
            ),
          )
        val workflows =
          object : WorkflowStateRepository by unit.workflowStates {
            override fun readFeatureTaskPhaseLedgerPage(
              workflowId: String,
              offset: Int,
              limit: Int,
            ) = FeatureTaskRuntimePhaseLedgerPage(entries, null)

            override fun readFeatureTaskArtifacts(
              workflowId: String,
              families: Set<DurableWorkflowArtifactFamily>,
            ) = artifacts
          }
        val reads =
          object : UnitOfWork by unit {
            override val workflowStates = workflows
          }
        val phases = ActivityPhaseReads().phases("child", reads)
        assertEquals(listOf(1, 2), phases.map { it.attempt })
        assertEquals(listOf(at(0), at(3)), phases.map { it.startedAt })
        assertEquals(listOf(at(2), at(4)), phases.map { it.finishedAt })
        assertEquals(listOf(null, null), phases.map { it.model })
        assertEquals(listOf("codex", "codex"), phases.map { it.agent })
      }
    }
  }

  private fun at(seconds: Long) = Instant.EPOCH.plusSeconds(seconds)

  private fun entry(
    sequence: Int,
    attempt: Int,
    action: FeatureTaskRuntimePhaseLedgerAction,
  ) = FeatureTaskRuntimePhaseLedgerEntry(action, sequence, at(sequence.toLong()), "audit", attempt, "codex")
}
