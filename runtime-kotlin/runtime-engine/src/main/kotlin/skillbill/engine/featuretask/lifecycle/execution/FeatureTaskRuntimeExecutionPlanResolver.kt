package skillbill.engine.featuretask.lifecycle.execution

import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.contracts.JsonCodec
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.time.Duration

@Inject
class FeatureTaskRuntimeExecutionPlanResolver(
  private val strategies: PhaseStrategyLookup,
  private val codec: FeatureTaskRuntimeExecutionPlanCodec,
  private val validator: FeatureTaskRuntimeExecutionPlanValidator,
  private val gateResolver: ValidationGateResolver,
  private val git: WorkflowGitOperations,
  private val config: RepoLocalConfigPort,
  private val database: DatabaseSessionFactory,
  private val compatibility: FeatureTaskRuntimeExecutionPlanCompatibility,
) {
  fun resolveCreation(
    repoRoot: Path,
    definition: SkeletonDefinition,
    reviewMode: CodeReviewExecutionMode,
    qualityGate: FeatureTaskRuntimeQualityGateSelection?,
    validationDepth: ValidationDepth,
    timeout: Duration?,
    workflowId: String? = null,
  ): ValidatedFeatureTaskRuntimeExecutionPlan {
    if (workflowId != null) {
      val plan = recordedPlan(workflowId)
      val expectedReview = RuntimeReviewSelection.valueOf(reviewMode.name)
      if (plan.definitionId != definition.id || plan.reviewSelection != expectedReview) {
        throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
      }
      requireRequestedSettings(plan, qualityGate, validationDepth, timeout)
      resolveRecordedInputs(repoRoot, plan)
      return ValidatedFeatureTaskRuntimeExecutionPlan.read(codec.encode(plan), validator)
    }
    val plan = strategies.executionPlan(PhaseStrategySelectionFacts(definition, setOfNotNull(reviewMode, qualityGate)))
    val inputs = resolveInputs(repoRoot, qualityGate, validationDepth, timeout)
    return ValidatedFeatureTaskRuntimeExecutionPlan.read(codec.encodeExecution(plan, inputs), validator)
  }

  fun resolveInputs(
    repoRoot: Path,
    qualityGate: FeatureTaskRuntimeQualityGateSelection?,
    validationDepth: ValidationDepth,
    timeout: Duration?,
    workflowId: String? = null,
  ): EffectiveGatePolicyInputs {
    if (workflowId != null) {
      val plan = recordedPlan(workflowId)
      requireRequestedSettings(plan, qualityGate, validationDepth, timeout)
      return resolveRecordedInputs(repoRoot, plan)
    }
    val paths = when (val inventory = git.repositoryOwnedPaths(repoRoot)) {
      is WorkflowGitNameListResult.Listed -> inventory.names
      is WorkflowGitNameListResult.Failed -> throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
    val resolution = gateResolver.resolve(paths)
    val pack = when (resolution) {
      is ValidationGateResolution.Declared -> resolution.packSlug
      is ValidationGateResolution.Absent -> resolution.routedPackSlug
      is ValidationGateResolution.Incompatible -> throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
    val declaration = (resolution as? ValidationGateResolution.Declared)?.declaration
    return EffectiveGatePolicyInputs(
      commandFamily = if (qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD) {
        ValidationGateCommandFamily.BUILD
      } else {
        ValidationGateCommandFamily.VALIDATION
      },
      packSlug = pack,
      declaration = declaration,
      gradleWrapper = config.readRepoLocalConfig(
        ReadRepoLocalConfigRequest(repoRoot),
      ).config.validationGate.gradleWrapper,
      validationDepth = validationDepth,
      phaseTimeoutMillis = timeout?.inWholeMilliseconds,
    ).also { it.canonicalInputs() }
  }

  fun resolveRecordedInputs(repoRoot: Path, plan: ResolvedPhaseExecutionPlan): EffectiveGatePolicyInputs {
    val settings = plan.effectivePolicySettings ?: throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    val wrapper = config.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config.validationGate.gradleWrapper
    val family = if (plan.qualityGateSelection == FeatureTaskRuntimeQualityGateSelection.BUILD) {
      ValidationGateCommandFamily.BUILD
    } else {
      ValidationGateCommandFamily.VALIDATION
    }
    return gateResolver.declaredCandidates().map { candidate ->
      val slug = when (candidate) {
        is ValidationGateResolution.Declared -> candidate.packSlug
        is ValidationGateResolution.Absent -> candidate.routedPackSlug
        is ValidationGateResolution.Incompatible -> throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
      }
      EffectiveGatePolicyInputs(
        family, slug, (candidate as? ValidationGateResolution.Declared)?.declaration,
        wrapper, settings.validationDepth, settings.phaseTimeoutMillis,
      ).frozen()
    }.singleOrNull { inputs ->
      FeatureTaskRuntimeEffectivePolicies.resolve(plan, inputs).sortedBy { it.id } == plan.effectivePolicies
    } ?: throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
  }

  private fun recordedPlan(workflowId: String): ResolvedPhaseExecutionPlan = database.read { unit ->
    val descriptor = unit.workflowStates.getFeatureTaskWorkflow(workflowId)?.toSnapshot()?.artifacts?.let {
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(it)
    }
    compatibility.requireSupportedComposition(descriptor?.let { JsonCodec.valueToJsonString(it).toByteArray(Charsets.UTF_8) })
  }

  private fun requireRequestedSettings(
    plan: ResolvedPhaseExecutionPlan,
    qualityGate: FeatureTaskRuntimeQualityGateSelection?,
    validationDepth: ValidationDepth,
    timeout: Duration?,
  ) {
    val settings = plan.effectivePolicySettings ?: throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    if (plan.qualityGateSelection != qualityGate || settings.validationDepth != validationDepth ||
      settings.phaseTimeoutMillis != timeout?.inWholeMilliseconds
    ) throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
  }

}
