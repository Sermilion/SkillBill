package skillbill.engine.work

import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.model.StandalonePhaseOutputLine
import skillbill.ports.idestatus.model.StandalonePhaseOutputSnapshot
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkDetailList
import skillbill.ports.idestatus.model.WorkDetailRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class IdeStatusServicePlanDetailTest {
  @Test
  fun `goal planning exposes the same live stages and output as standalone planning`() {
    val fixture = gitRepoFixture("ide-status-plan-detail")
    val identity = testGoalRepositoryIdentity(fixture)
    val output = StandalonePhaseOutputSnapshot(listOf("Reading the intake"), ideStatusObservedAt)
    for (scope in listOf(IdeStatusLaunchScope.GOAL, IdeStatusLaunchScope.STANDALONE)) {
      val record =
        StandalonePhaseStatusRecord(
          repositoryIdentity = identity,
          branchCorrelation = "main",
          issueKey = "SKILL-148",
          workflowId = "plan-workflow",
          invocationId = "plan-invocation",
          phaseId = "plan",
          executionId = "plan-execution",
          statusStoreId = "store",
          runSequence = "1",
          statusRevision = "1",
          lifecycleState = "active",
          currentStep = "preplan",
          currentActivity = null,
          startedAt = ideStatusObservedAt,
          updatedAt = ideStatusObservedAt,
          finishedAt = null,
          activeDurationMs = null,
          activeDurationAsOf = null,
          leaseOwner = "owner",
          leaseGeneration = 1,
          leaseExpiresAt = ideStatusObservedAt.plusSeconds(60),
          terminalResult = null,
          launchScope = scope,
        )
      val base = TrackingDatabase(emptyList(), IdeStatusWorkflowStates())
      val statuses =
        object : StandalonePhaseStatusRepository by base.unitOfWork().standalonePhaseStatuses {
          override fun readByInvocation(invocationId: String): StandalonePhaseStatusRecord? =
            record.takeIf { invocationId == it.invocationId }

          override fun readOutput(executionId: String): StandalonePhaseOutputSnapshot? =
            output.takeIf { executionId == record.executionId }

          override fun readOutputLines(
            executionId: String,
            beforeSequence: Long?,
            limit: Int,
          ): List<StandalonePhaseOutputLine> =
            (7L downTo 1L).filter { it < (beforeSequence ?: Long.MAX_VALUE) }.take(limit).map {
              StandalonePhaseOutputLine("line-$it", "[subtask 1] output $it", ideStatusObservedAt, 1, it)
            }
        }
      val service = ideStatusService(TrackingDatabase(emptyList(), base.workflows, statusRepository = statuses))

      val detail =
        assertNotNull(service.detail(WorkDetailRequest(record.invocationId, observedAt = ideStatusObservedAt)).item)

      assertEquals(scope, detail.snapshot.execution?.launchScope)
      assertEquals(
        listOf("preplan", "plan", "plan_subtasks"),
        assertNotNull(detail.standalonePlanProgress).stages.map { it.phaseId },
      )
      assertEquals(output.tail, detail.planOutputTail)
      assertEquals(output.latestLineAt, detail.planOutputUpdatedAt)
      assertOutputPages(service, record.invocationId)
    }
  }

  private fun assertOutputPages(
    service: IdeStatusService,
    id: String,
  ) {
    val first = service.detail(WorkDetailRequest(id, observedAt = ideStatusObservedAt, pageSize = 2))
    assertEquals(listOf(7L, 6L), assertNotNull(first.item).planOutputLines.map { it.sequence })
    val cursor = assertNotNull(first.listCursors[WorkDetailList.PLAN_OUTPUT])
    val next = service.detail(WorkDetailRequest(id, pageSize = 2, list = WorkDetailList.PLAN_OUTPUT, cursor = cursor))
    assertEquals(listOf(5L, 4L), assertNotNull(next.item).planOutputLines.map { it.sequence })
  }
}
