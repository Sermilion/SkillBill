package skillbill.engine.featuretask.runloop.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunEvidenceOwnership
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.recovery.recommendedDurableChildRecoveryCommand
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import java.nio.file.Path

internal data class FeatureTaskRuntimeRunLoopContext(
  override val request: FeatureTaskRuntimeRunFacts,
  override val runState: PhaseRunState,
  val strategies: PhaseStrategyLookup,
) : PhaseAttemptEnvironment

internal data class LaunchRejectionAttribution(
  val projectionContractId: String,
  val producerIteration: FeatureTaskRuntimeProducerIteration,
)

internal fun resolveLaunchRejectionAttribution(
  declarations: List<PhaseHandoffProjectionDeclaration>,
  projectionName: String,
  currentProducerIteration: (String) -> Int?,
  fallbackProducerIteration: FeatureTaskRuntimeProducerIteration,
): LaunchRejectionAttribution {
  val declaration =
    declarations.singleOrNull { it.projectionName == projectionName }
      ?: return LaunchRejectionAttribution(
        projectionContractId = "feature_task_runtime.$projectionName",
        producerIteration = fallbackProducerIteration,
      )
  val declaredProducer = declaration.producerIteration
  return LaunchRejectionAttribution(
    projectionContractId = declaration.projectionContractId,
    producerIteration =
      FeatureTaskRuntimeProducerIteration(
        phaseId = declaredProducer.phaseId,
        iteration = currentProducerIteration(declaredProducer.phaseId) ?: declaredProducer.iteration,
      ),
  )
}

private const val FEATURE_SPEC_ROOT = ".feature-specs"

fun isFeatureSpecPathForIssue(
  path: String,
  issueKey: String,
): Boolean {
  val normalized = path.trim().trimEnd('/')
  if (normalized == FEATURE_SPEC_ROOT) return true
  if (!normalized.startsWith("$FEATURE_SPEC_ROOT/")) return false
  val issueDirectory = normalized.removePrefix("$FEATURE_SPEC_ROOT/").substringBefore('/')
  val key = issueKey.trim()
  return issueDirectory == key || issueDirectory.startsWith("$key-")
}

fun reconcileCheckpointPathInventory(
  repoRoot: Path,
  issueKey: String,
  specReference: String,
  workflowId: String,
  paths: List<String>,
): List<String> {
  val specPath =
    Path.of(specReference)
      .let { path -> if (path.isAbsolute) repoRoot.relativize(path) else path }
      .normalize()
      .toString()
  return paths.filterNot { path ->
    path == specPath ||
      isFeatureSpecPathForIssue(path, issueKey) ||
      FeatureTaskRuntimeRunEvidenceOwnership.isOwnedByRun(path, workflowId)
  }.distinct()
}

fun resolveReviewPassNumber(
  reservedPassNumber: Int?,
  completedReviewPassCount: Int,
): Int {
  reservedPassNumber?.let { pass ->
    require(pass == 1) { "Review reservation allows only pass 1, was $pass." }
  }
  require(completedReviewPassCount <= 1) {
    "Review completed-pass count cannot exceed one, was $completedReviewPassCount."
  }
  return 1
}

@Inject
open class FeatureTaskRuntimeRunLoopEntry {
  internal open fun run(
    context: FeatureTaskRuntimeRunLoopContext,
    beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit = {},
  ): FeatureTaskRuntimeRunReport {
    val loop = FeatureTaskRuntimeRunLoop(context = context)
    beforeDrive(loop)
    loop.drive()
    return loop.report()
  }
}

class FeatureTaskRuntimeRunLoop internal constructor(
  internal val context: FeatureTaskRuntimeRunLoopContext,
) {
  internal val session = context.session

  init {
    val resumed = FeatureTaskRuntimeRunLoopDrive.resumedReentry(context)
    session.transitionReentryPair(resumed, resumed)
  }

  fun drive() {
    with(FeatureTaskRuntimeRunLoopDrive) {
      invalidateStaleEvidence(context)
      context.runPhaseDriveLoop(::advance)
    }
  }

  internal fun advance(phaseId: String): PhaseSettlement {
    FeatureTaskRuntimeRunLoopDrive.phaseEntryBlockReason(context, phaseId)?.let { reason ->
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(
        context.request,
        context.state,
        session,
        phaseId,
        reason,
      )
      return PhaseSettlement.stop()
    }
    FeatureTaskRuntimeRunLoopDrive.settleWithoutLaunch(context, phaseId)?.let { settled -> return settled }
    val reason = FeatureTaskRuntimeRunLoopDrive.advancePhaseReason(context, phaseId)
    return FeatureTaskRuntimeRunLoopDrive.settleAdvanceOutcome(
      context.request,
      context.state,
      session,
      phaseId,
      reason,
    )
  }

  fun report(): FeatureTaskRuntimeRunReport {
    val branch =
      session.resolvedBranch
        ?: context.recorder.loadResolvedBranch(context.request.workflowId)?.branch
    return session.decomposed ?: session.paused?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: session.blocked?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: FeatureTaskRuntimeRunReport.Completed(
      issueKey = context.request.issueKey,
      workflowId = context.request.workflowId,
      featureSize = context.request.runInvariants.featureSize.name,
      completedPhaseIds = context.state.completedPhaseIds(),
      resolvedBranch = branch,
    )
  }

  fun applyOperatorDecision(): String? =
    buildString {
      append("Operator decisions over review remediation are removed; ")
      append("the run advances to validate after one implement_fix round.")
      context.request.goalContinuation?.let {
        append(
          " Recover with: '${recommendedDurableChildRecoveryCommand(
            it.parentIssueKey,
            it.subtaskId,
            null,
            null,
          )}'.",
        )
      }
    }
}
