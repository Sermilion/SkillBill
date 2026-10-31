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
public interface FeatureTaskRuntimeRunFacts {
  public val admittedExecution: AdmittedFeatureTaskRuntimeExecution?

  /** The caller's explicit in-memory QA selection; null when the caller supplies none. */
  public val qaSelection: FeatureTaskRuntimeQaSelection?

  /** The issue the run works on. */
  public val issueKey: String

  /** The identity the run's state is keyed by. */
  public val workflowId: String

  /** The invariants frozen when the run first opened. */
  public val runInvariants: FeatureTaskRuntimeRunInvariants

  /** The documented default agent. */
  public val invokedAgentId: String

  /** The per-step agent assignment. */
  public val agentAssignment: FeatureTaskRuntimeAgentAssignment

  /** The per-step model assignment. */
  public val modelAssignment: FeatureTaskRuntimeModelAssignment

  /** The compaction settings launched agents inherit. */
  public val compactionSettings: CompactionSettings

  /** The environment launched agents inherit. */
  public val environment: Map<String, String>

  /** The repository the run changes. */
  public val repoRoot: Path

  /** The per-launch timeout, when one was requested. */
  public val timeout: Duration?

  /** The code review mode the caller requested, when one was requested. */
  public val requestedCodeReviewMode: CodeReviewExecutionMode?

  /** The goal-continuation context of a goal child run, null for a standalone run. */
  public val goalContinuation: FeatureTaskRuntimeGoalContinuationContext?

  /** The parent goal receiving activity from a planning run, without goal-child execution policy. */
  public val activityParentWorkflowId: String?

  /** The agent add-ons selected for the run. */
  public val agentAddonSelection: HydratedAgentAddonSelection

  /** The sink run events are emitted to. */
  public val eventSink: FeatureTaskRuntimeRunEventSink

  /** The transitions that replace the definition's declaration, when a caller overrides them. */
  public val transitionsOverride: FeatureTaskRuntimeTransitionDeclaration?

  /** The skeleton a phase run drives; null lets the run derive the standalone or goal-child skeleton. */
  public val skeletonDefinition: SkeletonDefinition?

  /** The caller-supplied review target and review identity of a phase run; null for a full run. */
  public val reviewInvocation: ReviewInvocation?

  /** The operator instructions a phase run adds to its step prompts; null for a full run. */
  public val phaseInstructions: PhaseInstructions?

  /** The hash of an operator-authored `spec.md` the plan must leave untouched; null when the plan may rewrite it. */
  public val protectedSpecSha256: String?

  /** The operator's plan intake text the `intake` step resolves the parent spec from; null when none was supplied. */
  public val planIntake: String?

  /** Whether this pass ends once the `intake` step settles, before run invariants can be read from the spec. */
  public val stopAfterIntake: Boolean

  /** The pinned pull request target and owned worktree of a prepared standalone monitor; null for every other run. */
  public val monitorExecution: MonitorExecutionContext?

  /** The workspace, expected identity, and pack guidance of a standalone QA run; null for every other run. */
  public val qaExecution: QaExecutionContext?

  public val goalHistoryWriterInput: GoalHistoryWriterInput?

  public val goalQualityAutomaticRecovery: Boolean

  public val acceptedGoalQualityExecutionPlan: ResolvedPhaseExecutionPlan?

  public val qaOutcomeWorkflowId: String get() = workflowId
}

public data class PhaseInstructions(
  val text: String,
  val stepIds: Set<String> = emptySet(),
) {
  internal fun forStep(stepId: String): String? = text.takeIf { stepIds.isEmpty() || stepId in stepIds }
}
