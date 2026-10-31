package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.install.model.SupportedAgent
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

internal object FeatureTaskRuntimeRunLoopLaunch {
  internal fun capturePhaseContentIdentities(
    request: FeatureTaskRuntimeRunFacts,
    coupledTransitions: FeatureTaskRuntimeRunTransitionOwner,
    gitOperations: WorkflowGitOperations,
    phaseId: String,
  ) {
    coupledTransitions.recordPhaseContentIdentities(request, gitOperations, phaseId)
  }

  internal fun launchedModelDirective(run: PhaseRun): LaunchedModelDirective {
    val assignment = run.launchAssignment?.launch
    val model = assignment?.effectiveModel ?: run.modelDirective?.model
    val effort = assignment?.requestedEffort ?: run.modelDirective?.effort
    if (run.resolvedAgent.resolvedAgentId == SupportedAgent.CURSOR.id && model != null && effort != null) {
      return LaunchedModelDirective("$model[effort=$effort]", effort, persistedEffort = null)
    }
    return LaunchedModelDirective(model, effort, effort)
  }
}

internal sealed interface AttemptResult {
  data class Settled(
    val outcome: PhaseOutcome,
  ) : AttemptResult

  data class SchemaInvalid(
    val operatorReason: String,
    val retryReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    override val rejectedOutput: String?,
  ) : AttemptResult

  data class IncompleteWork(
    val operatorReason: String,
    val continuationReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ) : AttemptResult

  data class RetryableTerminal(
    val operatorReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    val failureDisposition: FeatureTaskRuntimeFailureDisposition,
    val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    val continuationOutput: NormalizedFeatureTaskRuntimePhaseOutput?,
  ) : AttemptResult

  data class BoundaryBodyDelivery(
    val continuationReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ) : AttemptResult

  data class FindingsOwed(
    val retryReason: String,
    val refs: Set<String>,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ) : AttemptResult

  data class CriterionTurn(
    val continuationReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ) : AttemptResult

  val settledOutcome: PhaseOutcome? get() = (this as? Settled)?.outcome
  val schemaInvalidOperatorReason: String? get() = (this as? SchemaInvalid)?.operatorReason
  val schemaInvalidRetryReason: String? get() = (this as? SchemaInvalid)?.retryReason
  val fileManifest: FeatureTaskRuntimePhaseFileManifest?
    get() =
      when (this) {
        is Settled -> null
        is SchemaInvalid -> fileManifest
        is IncompleteWork -> fileManifest
        is RetryableTerminal -> fileManifest
        is BoundaryBodyDelivery -> fileManifest
        is FindingsOwed -> fileManifest
        is CriterionTurn -> fileManifest
      }
  val rejectedOutput: String? get() = (this as? SchemaInvalid)?.rejectedOutput

  val retryableOperatorReason: String?
    get() =
      when (this) {
        is Settled -> null
        is SchemaInvalid -> operatorReason
        is IncompleteWork -> operatorReason
        is RetryableTerminal -> operatorReason
        is BoundaryBodyDelivery -> null
        is FindingsOwed -> null
        is CriterionTurn -> null
      }

  val semanticRetryReason: String?
    get() =
      when (this) {
        is Settled -> null
        is SchemaInvalid -> retryReason
        is IncompleteWork -> null
        is RetryableTerminal -> null
        is BoundaryBodyDelivery -> null
        is FindingsOwed -> null
        is CriterionTurn -> null
      }

  val retryableTerminal: RetryableTerminal? get() = this as? RetryableTerminal

  val findingsOwed: FindingsOwed? get() = this as? FindingsOwed

  val incompleteWorkContinuationReason: String? get() = (this as? IncompleteWork)?.continuationReason
  val incompleteWorkOutput: NormalizedFeatureTaskRuntimePhaseOutput?
    get() = (this as? IncompleteWork)?.normalizedOutput
  val boundaryBodyDeliveryContinuationReason: String?
    get() = (this as? BoundaryBodyDelivery)?.continuationReason
  val criterionTurnContinuationReason: String? get() = (this as? CriterionTurn)?.continuationReason

  companion object {
    fun settled(outcome: PhaseOutcome): AttemptResult = Settled(outcome)

    fun criterionTurn(
      continuationReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ): AttemptResult = CriterionTurn(continuationReason, fileManifest)

    fun boundaryBodyDelivery(
      continuationReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ): AttemptResult = BoundaryBodyDelivery(continuationReason, fileManifest)

    fun incompleteWork(
      operatorReason: String,
      continuationReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
      normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    ): AttemptResult = IncompleteWork(operatorReason, continuationReason, fileManifest, normalizedOutput)

    fun schemaInvalid(
      operatorReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
      retryReason: String = operatorReason,
    ): AttemptResult =
      SchemaInvalid(
        operatorReason = operatorReason,
        retryReason = retryReason,
        fileManifest = fileManifest,
        rejectedOutput = null,
      )
  }
}

public sealed interface PhaseOutcome {
  public data class Completed(
    val output: FeatureTaskRuntimePhaseOutput,
  ) : PhaseOutcome

  public data class Blocked(
    val reason: String,
    override val failureCode: String? = null,
  ) : PhaseOutcome

  public data class Paused(
    val reason: String,
  ) : PhaseOutcome

  public data class RegenerateProducer(
    val producerPhaseId: String,
  ) : PhaseOutcome

  public val completedOutput: FeatureTaskRuntimePhaseOutput? get() = (this as? Completed)?.output

  public val blockedReason: String? get() = (this as? Blocked)?.reason

  public val failureCode: String? get() = (this as? Blocked)?.failureCode

  public val pausedReason: String? get() = (this as? Paused)?.reason

  public val regenerationTargetPhaseId: String? get() = (this as? RegenerateProducer)?.producerPhaseId

  public companion object {
    public fun completed(output: FeatureTaskRuntimePhaseOutput): PhaseOutcome = Completed(output)

    public fun blocked(
      reason: String,
      failureCode: String? = null,
    ): PhaseOutcome = Blocked(reason, failureCode)

    public fun paused(reason: String): PhaseOutcome = Paused(reason)

    internal fun regenerateProducer(producerPhaseId: String): PhaseOutcome = RegenerateProducer(producerPhaseId)
  }
}

internal const val LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION =
  "rejected an upstream bounded planning projection at the launch seam"
