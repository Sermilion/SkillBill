package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.model.GoalSubtaskUnknownScenarios
import skillbill.engine.goalrunner.model.GoalUnknownScenario
import kotlin.test.Test
import kotlin.test.assertEquals

class AcceptanceCriterionRepairPhaseRecorderTest {
  private val scenarios =
    AcceptanceCriterionRepairPhaseRecorderScenarios { database, workflowId ->
      val store = testWorkflowGoalRunnerOutcomeStore(database)
      assertEquals(
        GoalSubtaskUnknownScenarios.Recorded(listOf(GoalUnknownScenario("IS-001", "malformed audit output"))),
        store.recordedUnknownScenarios(workflowId),
      )
      assertEquals(GoalSubtaskUnknownScenarios.Absent, store.recordedUnknownScenarios("wftr-never-recorded"))
    }

  @Test
  fun `spec drift before repair start retains the refusal without reserving or consuming an attempt`() {
    scenarios.`spec drift before repair start retains the refusal without reserving or consuming an attempt`()
  }

  @Test
  fun `spec drift after a satisfied audit reports the bound source and preserves the running record`() {
    scenarios.`spec drift after a satisfied audit reports the bound source and preserves the running record`()
  }

  @Test
  fun `an accepted catalog re-audits only the criteria that open QA findings name`() {
    scenarios.`an accepted catalog re-audits only the criteria that open QA findings name`()
  }

  @Test
  fun `a broken trace reopens the owner and routes it to audit planning`() {
    scenarios.`a broken trace reopens the owner and routes it to audit planning`()
  }

  @Test
  fun `a trace that stays malformed after one re-ask degrades to unknown with a warning`() {
    scenarios.`a trace that stays malformed after one re-ask degrades to unknown with a warning`()
  }

  @Test
  fun `a repair that edits a cited file schedules a recheck that survives continuation reload and replay`() {
    scenarios.`a repair that edits a cited file schedules a recheck that survives continuation reload and replay`()
  }

  @Test
  fun `a recheck only trace settles holds with fresh evidence and a regressed line reopens the criterion`() {
    scenarios.`a recheck only trace settles holds with fresh evidence and a regressed line reopens the criterion`()
  }

  @Test
  fun `a malformed Recheck without evidence is a relaunchable re-ask rather than a hard schema block`() {
    scenarios.`a malformed Recheck without evidence is a relaunchable re-ask rather than a hard schema block`()
  }

  @Test
  fun `progress repair settlement reaches targeted verification with changed paths`() {
    scenarios.`progress repair settlement reaches targeted verification with changed paths`()
  }

  @Test
  fun `a retained repair clarification resumes through verification without losing its evidence`() {
    scenarios.`a retained repair clarification resumes through verification without losing its evidence`()
  }

  @Test
  fun `each criterion keeps its own failure count and its third unsuccessful cycle blocks`() {
    scenarios.`each criterion keeps its own failure count and its third unsuccessful cycle blocks`()
  }

  @Test
  fun `duplicate starts preserve the live ledger and completed attempts cannot reopen`() {
    scenarios.`duplicate starts preserve the live ledger and completed attempts cannot reopen`()
  }

  @Test
  fun `recovered cycles survive projection recovery and stale workers cannot acknowledge new outcomes`() {
    scenarios.`recovered cycles survive projection recovery and stale workers cannot acknowledge new outcomes`()
  }
}
