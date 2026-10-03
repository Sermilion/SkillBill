package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.runner.PLAN_OUTPUT
import skillbill.engine.featuretask.runner.PREPLAN_OUTPUT
import skillbill.engine.featuretask.runner.VALID_REVIEW_OUTPUT
import skillbill.engine.featuretask.runner.auditSatisfiedOutput
import skillbill.engine.featuretask.runner.settledAuditSatisfiedRecord
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditVerdictRule
import skillbill.engine.featuretask.slot.state.PhaseHistoricalInterpreter
import skillbill.engine.featuretask.slot.state.PhaseHistoricalPolicy
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerProgressReader
import skillbill.engine.goalrunner.execution.support.GoalRunnerChildProgressRead
import skillbill.engine.goalrunner.status.completed
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val RESUME_RULES = PhaseHistoricalInterpreter(PhaseHistoricalPolicy.REVISION_1)::resumeRules

class AmbientInputsAndLoudFailSeamsTest {
  @Test
  fun `explicit resume of a finished audit continues from the furthest later phase`() {
    val completed =
      mapOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to PREPLAN_OUTPUT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to PLAN_OUTPUT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to settledAuditSatisfiedRecord(),
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to VALID_REVIEW_OUTPUT,
      ).mapValues { (phaseId, output) ->
        completedRecord(phaseId, output)
      }
    val state =
      FeatureTaskRuntimeRunState(
        initialRecords =
          completed + (
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to
              FeatureTaskRuntimePhaseRecord(
                phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
                status = WorkflowStepStatus.RUNNING,
                attemptCount = 1,
                startedAt = "2026-09-08T00:00:00Z",
                resolvedAgentId = "codex",
              )
          ),
        transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
        stepVerdictRule = { stepId ->
          AcceptanceAuditVerdictRule(SilentDiagnostics)
            .takeIf { stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT }
        },
        resumeRulesFn = RESUME_RULES,
      )

    val start = state.explicitResumeStart(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY, start.phaseId)
    assertTrue(start.reopen)
    state.reopenFromExplicitResume(start.phaseId)

    assertTrue(state.phase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT).completed)
    assertTrue(state.phase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW).completed)
    assertFalse(state.phase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY).completed)
  }

  @Test
  fun `explicit resume of a finished audit with no later phase starts the next phase`() {
    val state =
      FeatureTaskRuntimeRunState(
        initialRecords =
          mapOf(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to
              completedRecord(
                FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
                PREPLAN_OUTPUT,
              ),
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
              completedRecord(
                FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
                PLAN_OUTPUT,
              ),
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to
              completedRecord(
                FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
                auditSatisfiedOutput(),
              ),
          ),
        transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
        resumeRulesFn = RESUME_RULES,
      )

    val start = state.explicitResumeStart(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, start.phaseId)
    assertTrue(start.reopen)
    state.reopenFromExplicitResume(start.phaseId)

    assertTrue(state.phase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT).completed)
    assertFalse(state.phase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW).completed)
  }

  @Test
  fun `explicit resume of an unfinished audit still reopens audit`() {
    val state =
      FeatureTaskRuntimeRunState(
        initialRecords =
          mapOf(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to
              completedRecord(
                FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
                PREPLAN_OUTPUT,
              ),
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
              completedRecord(
                FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
                PLAN_OUTPUT,
              ),
          ),
        transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
        resumeRulesFn = RESUME_RULES,
      )

    val start = state.explicitResumeStart(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT, start.phaseId)
    assertTrue(start.reopen)
  }

  @Test
  fun `corrupt durable phase payload does not collapse to emptyMap`() {
    val state =
      FeatureTaskRuntimeRunState(
        initialRecords = emptyMap(),
        transitions =
          FeatureTaskRuntimeTransitionDeclaration(
            listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
          ),
        resumeRulesFn = RESUME_RULES,
      )
    val output =
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        iteration = 1,
        payload = "not a json object",
      )

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      state.parsedOutput(output)
    }
  }

  @Test
  fun `throwing progress store is distinguishable from absent progress`() {
    val outcomes = RecordingOutcomeStore().apply { throwOnProgress = true }
    val reader = GoalRunnerProgressReader(outcomes)

    assertIs<GoalRunnerChildProgressRead.Failed>(reader.read("wfl-child"))
  }
}

private fun completedRecord(
  phaseId: String,
  output: String,
): FeatureTaskRuntimePhaseRecord =
  FeatureTaskRuntimePhaseRecord(
    phaseId = phaseId,
    status = WorkflowStepStatus.COMPLETED,
    attemptCount = 1,
    startedAt = "2026-09-08T00:00:00Z",
    resolvedAgentId = "codex",
    outputArtifact = output,
  )

private object SilentDiagnostics : RuntimeDiagnostics {
  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
