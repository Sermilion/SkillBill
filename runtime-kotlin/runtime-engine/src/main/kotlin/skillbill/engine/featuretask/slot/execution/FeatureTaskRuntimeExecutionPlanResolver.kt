package skillbill.engine.featuretask.slot.execution

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.featuretask.validation.model.ValidationGateCommandFamily
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
) {
  fun resolveCreation(
    repoRoot: Path,
    definition: SkeletonDefinition,
    reviewMode: CodeReviewExecutionMode,
    qualityGate: FeatureTaskRuntimeQualityGateSelection?,
    validationDepth: ValidationDepth,
    timeout: Duration?,
  ): ValidatedFeatureTaskRuntimeExecutionPlan {
    val plan = strategies.executionPlan(PhaseStrategySelectionFacts(definition, setOfNotNull(reviewMode, qualityGate)))
    val inputs = resolveInputs(repoRoot, qualityGate, validationDepth, timeout)
    return ValidatedFeatureTaskRuntimeExecutionPlan.read(codec.encodeExecution(plan, inputs), validator)
  }

  fun resolveInputs(
    repoRoot: Path,
    qualityGate: FeatureTaskRuntimeQualityGateSelection?,
    validationDepth: ValidationDepth,
    timeout: Duration?,
  ): EffectiveGatePolicyInputs {
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
    if (qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD &&
      (declaration?.buildCommand.isNullOrEmpty() || declaration.cacheBypassingBuildCommand.isNullOrEmpty())
    ) throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    return EffectiveGatePolicyInputs(
      commandFamily = if (qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD) {
        ValidationGateCommandFamily.BUILD
      } else {
        ValidationGateCommandFamily.VALIDATION
      },
      packSlug = pack,
      declaration = declaration,
      gradleWrapper = config.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config.validationGate.gradleWrapper,
      validationDepth = validationDepth,
      phaseTimeoutMillis = timeout?.inWholeMilliseconds,
    ).also { it.canonicalInputs() }
  }
}
