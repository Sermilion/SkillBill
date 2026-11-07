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
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFailure
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentIdentityAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReasonRetention
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentScope
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseExecutionOrigin
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkActivityServiceTest {
  @Test
  fun `goal finish uses detailed block recorded after stop completion`() {
    establishTemporarySchemaReadiness("activity-finish-late-block").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      database.transaction { unit ->
        val reason = "The current acceptance criterion exhausted its repair attempts."
        unit.blockIncidents.createOrLink(
          BlockIncidentOccurrence(
            occurrenceToken = "bi_${"a".repeat(64)}",
            scope = BlockIncidentScope(workflowId = "goal", phaseId = "plan_subtasks", attempt = 1, generation = 1),
            failure =
              BlockIncidentFailure(
                origin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
                reason = reason,
                reasonRetention = BlockIncidentReasonRetention.COMPLETE,
                failureDisposition = "blocked",
                exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
              ),
            observedAt = at(11),
            evidenceAvailability = BlockIncidentEvidenceAvailability.DATABASE,
            identityAvailability = BlockIncidentIdentityAvailability.PARTIAL,
            fieldAvailability =
              BlockIncidentCorrelatedField.entries.associateWith { field ->
                if (
                  field in
                  setOf(
                    BlockIncidentCorrelatedField.WORKFLOW_ID,
                    BlockIncidentCorrelatedField.PHASE_ID,
                    BlockIncidentCorrelatedField.ATTEMPT,
                    BlockIncidentCorrelatedField.GENERATION,
                    BlockIncidentCorrelatedField.REASON,
                  )
                ) {
                  BlockIncidentFieldAvailability.AVAILABLE
                } else {
                  BlockIncidentFieldAvailability.NOT_CAPTURED
                }
              },
          ),
          BlockIncidentAlias(BlockIncidentAliasKind.SETTLEMENT, "goal:blocked"),
          at(100),
        )

        val goal = WorkItem("SKILL-455", WorkItemKind.FEATURE_GOAL, "goal", at(0), "running", at(0), false)
        val stops =
          listOf(
            WorkActivityStop("goal", at(0), at(10), "BLOCKED", "The goal is blocked."),
            WorkActivityStop("goal:resumed", at(20), null, null),
          )
        val group = ActivityGoalFinishReads().groups(goal, emptyList(), stops, unit).single()

        assertEquals(1, group.blocks.size)
        assertEquals("plan_subtasks", group.blocks.single().phaseId)
        assertEquals(reason, group.blocks.single().reason)
      }
    }
  }

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
            WorkActivityStop("decision", at(21), at(30), "AWAITING_OPERATOR_DECISION", "The goal needs your decision."),
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
        assertEquals(listOf("pr", "decision"), interruptions.blocks.map { it.id })
        assertEquals(WorkActivityGroupKind.GENERAL_BLOCKS, interruptions.kind)
        assertTrue(interruptions.blocks.single { it.id == "decision" }.needsDecision)
        assertEquals(WorkActivityStatus.INTERRUPTED, interruptions.status)
        assertEquals(1, groups.size)
        assertEquals(emptyList(), interruptions.phases)
        assertEquals(null, interruptions.startedAt)
        assertEquals(null, interruptions.finishedAt)
      }
    }
  }

  @Test
  fun `running subtask keeps goal finish pending and owns stops recorded during its run`() {
    establishTemporarySchemaReadiness("activity-finish-pending").use { fixture ->
      val delegate = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      val goal = WorkItem("SKILL-458", WorkItemKind.FEATURE_GOAL, "goal", at(0), "running", at(0), false)
      val work =
        object : WorkListRepository by EmptyWorkListRepository {
          override fun findByWorkflowId(workflowId: String) = listOf(goal)

          override fun goalActivityAttempts(
            parentWorkflowId: String,
            offset: Int,
            limit: Int,
          ) = listOf(WorkActivityAttempt("child", 1, "Child", "running", "blocked", at(0), null, null))

          override fun goalActivityStops(
            parentWorkflowId: String,
            offset: Int,
            limit: Int,
          ) = listOf(WorkActivityStop("paused", at(5), at(10), "AWAITING_OPERATOR_DECISION", "Usage limit reached."))
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
                  ) = FeatureTaskRuntimePhaseLedgerPage(emptyList(), null)

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
        WorkActivityService(database, ActivityPhaseReads(), ActivityGoalFinishReads(), NoopRuntimeDiagnostics)

      val groups = assertIs<WorkActivityResult.Listed>(service.timeline("goal")).groups
      val group = groups.single { it.id == "goal-interruptions" }

      assertEquals(WorkActivityStatus.INTERRUPTED, group.status)
      assertEquals("Usage limit reached.", group.blocks.single().reason)
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

  @Test
  fun `a cursor attempt keeps its grok model and drops a borrowed codex model`() {
    establishTemporarySchemaReadiness("activity-cursor-model").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      database.read { unit ->
        val entries =
          listOf(
            FeatureTaskRuntimePhaseLedgerEntry(
              FeatureTaskRuntimePhaseLedgerAction.START,
              0,
              at(0),
              "audit",
              1,
              "cursor",
            ),
            FeatureTaskRuntimePhaseLedgerEntry(
              FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
              1,
              at(1),
              "audit",
              1,
              "cursor",
            ),
          )
        val record =
          FeatureTaskRuntimePhaseRecord(
            "audit",
            WorkflowStepStatus.COMPLETED,
            1,
            at(0),
            finishedAt = at(1),
            resolvedAgentId = "cursor",
            launchedModel = "grok-4.7-high-fast",
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
        assertEquals(listOf("grok-4.7-high-fast"), phases.map { it.model })
        assertEquals(listOf("cursor"), phases.map { it.agent })
        assertTrue(modelMatchesAgent("cursor", "cursor-grok-4.6-high"))
        assertFalse(modelMatchesAgent("cursor", "gpt-6.1-sol"))
        assertFalse(modelMatchesAgent("cursor", "claude-opus"))
      }
    }
  }

  @Test
  fun `resume opening a new attempt is live and the attempt it replaces adds no phase time`() {
    establishTemporarySchemaReadiness("activity-resumed-attempt").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      database.read { unit ->
        val entries =
          listOf(
            entry(0, 1, FeatureTaskRuntimePhaseLedgerAction.START),
            entry(1, 1, FeatureTaskRuntimePhaseLedgerAction.PAUSED),
            entry(2, 2, FeatureTaskRuntimePhaseLedgerAction.RESUME),
            entry(3, 3, FeatureTaskRuntimePhaseLedgerAction.RESUME),
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
            ) = DurableWorkflowArtifacts.EMPTY
          }
        val reads =
          object : UnitOfWork by unit {
            override val workflowStates = workflows
          }

        val phases = ActivityPhaseReads().phases("child", reads)

        assertEquals(listOf(1, 2, 3), phases.map { it.attempt })
        assertEquals(listOf(at(1), at(2), null), phases.map { it.finishedAt })
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
