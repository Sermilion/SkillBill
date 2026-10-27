package skillbill.engine.featuretask.model.core

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.config.model.CompactionSettings
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.execution.MonitorExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.review.ReviewInvocation
import skillbill.goalrunner.model.history.GoalHistoryWriterInput
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The per-call facts the run loop and its strategies read: the run identity, the frozen run invariants, the agent
 * and model assignment, the repository, and the goal-continuation context. The durable entry's
 * [FeatureTaskRuntimeRunRequest] supplies them; the loop never sees the session or operator-decision fields that
 * only the durable entry consumes.
 */
interface FeatureTaskRuntimeRunFacts {
  val admittedExecution: AdmittedFeatureTaskRuntimeExecution? get() = null

  /** The caller's explicit in-memory QA selection; null when the caller supplies none. */
  val qaSelection: FeatureTaskRuntimeQaSelection? get() = null

  /** The issue the run works on. */
  val issueKey: String

  /** The identity the run's state is keyed by. */
  val workflowId: String

  /** The invariants frozen when the run first opened. */
  val runInvariants: FeatureTaskRuntimeRunInvariants

  /** The documented default agent. */
  val invokedAgentId: String

  /** The per-step agent assignment. */
  val agentAssignment: FeatureTaskRuntimeAgentAssignment

  /** The per-step model assignment. */
  val modelAssignment: FeatureTaskRuntimeModelAssignment

  /** The compaction settings launched agents inherit. */
  val compactionSettings: CompactionSettings

  /** The environment launched agents inherit. */
  val environment: Map<String, String>

  /** The repository the run changes. */
  val repoRoot: Path

  /** The per-launch timeout, when one was requested. */
  val timeout: Duration?

  /** The code review mode the caller requested, when one was requested. */
  val requestedCodeReviewMode: CodeReviewExecutionMode?

  /** The goal-continuation context of a goal child run, null for a standalone run. */
  val goalContinuation: FeatureTaskRuntimeGoalContinuationContext?

  /** The agent add-ons selected for the run. */
  val agentAddonSelection: HydratedAgentAddonSelection

  /** The sink run events are emitted to. */
  val eventSink: FeatureTaskRuntimeRunEventSink

  /** The transitions that replace the definition's declaration, when a caller overrides them. */
  val transitionsOverride: FeatureTaskRuntimeTransitionDeclaration?

  /** The skeleton a phase run drives; null lets the run derive the standalone or goal-child skeleton. */
  val skeletonDefinition: SkeletonDefinition? get() = null

  /** The caller-supplied review target and review identity of a phase run; null for a full run. */
  val reviewInvocation: ReviewInvocation? get() = null

  /** The operator instructions a phase run adds to its step prompts; null for a full run. */
  val phaseInstructions: PhaseInstructions? get() = null

  /** The hash of an operator-authored `spec.md` the plan must leave untouched; null when the plan may rewrite it. */
  val protectedSpecSha256: String? get() = null

  /** The operator's plan intake text the `intake` step resolves the parent spec from; null when none was supplied. */
  val planIntake: String? get() = null

  /** Whether this pass ends once the `intake` step settles, before run invariants can be read from the spec. */
  val stopAfterIntake: Boolean get() = false

  /** The pinned pull request target and owned worktree of a prepared standalone monitor; null for every other run. */
  val monitorExecution: MonitorExecutionContext? get() = null

  /** The workspace, expected identity, and pack guidance of a standalone QA run; null for every other run. */
  val qaExecution: QaExecutionContext? get() = null

  val goalHistoryWriterInput: GoalHistoryWriterInput? get() = null

  val goalQualityAutomaticRecovery: Boolean get() = false

  val acceptedGoalQualityExecutionPlan: ResolvedPhaseExecutionPlan? get() = null

  val qaOutcomeWorkflowId: String get() = workflowId
}

data class PhaseInstructions(
  val text: String,
  val stepIds: Set<String> = emptySet(),
) {
  fun forStep(stepId: String): String? = text.takeIf { stepIds.isEmpty() || stepId in stepIds }
}
