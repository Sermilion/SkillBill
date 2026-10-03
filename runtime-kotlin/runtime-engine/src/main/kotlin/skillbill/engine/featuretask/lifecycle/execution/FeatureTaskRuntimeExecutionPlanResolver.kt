package skillbill.engine.featuretask.lifecycle.execution

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.MissingValidationGateError
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
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
    request: FeatureTaskRuntimeExecutionPlanCreationRequest,
  ): ValidatedFeatureTaskRuntimeExecutionPlan {
    val repoRoot = request.repoRoot
    val definition = request.definition
    val reviewMode = request.reviewMode
    val qualityGate = request.qualityGate
    val validationDepth = request.validationDepth
    val timeout = request.timeout
    val workflowId = request.workflowId

    if (workflowId != null) {
      val recorded = recordedDescriptor(workflowId)
      val plan = compatibility.requireSupportedComposition(recorded)
      val expectedReview = RuntimeReviewSelection.valueOf(reviewMode.name)
      if (plan.definitionId != definition.id || plan.reviewSelection != expectedReview) {
        incompatible()
      }
      requireRequestedSettings(plan, qualityGate, validationDepth, timeout)
      requireBuildGate(resolveRecordedInputs(repoRoot, plan), recorded = true)
      return ValidatedFeatureTaskRuntimeExecutionPlan.read(requireNotNull(recorded), validator)
    }
    val plan = strategies.executionPlan(PhaseStrategySelectionFacts(definition, setOfNotNull(reviewMode, qualityGate)))
    val inputs = resolveInputs(repoRoot, qualityGate, validationDepth, timeout)
    requireBuildGate(inputs, recorded = false)
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
    val resolution =
      gateResolver.resolveWithRepositoryFallback(listedPaths(git.repositoryOwnedPaths(repoRoot))) {
        listedPaths(git.trackedPaths(repoRoot))
      }
    val pack =
      when (resolution) {
        is ValidationGateResolution.Declared -> resolution.packSlug
        is ValidationGateResolution.Absent -> resolution.routedPackSlug
        is ValidationGateResolution.Incompatible -> incompatible()
      }
    val declaration = (resolution as? ValidationGateResolution.Declared)?.declaration
    return EffectiveGatePolicyInputs(
      commandFamily =
        if (qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD) {
          ValidationGateCommandFamily.BUILD
        } else {
          ValidationGateCommandFamily.VALIDATION
        },
      packSlug = pack,
      declaration = declaration,
      gradleWrapper =
        config.readRepoLocalConfig(
          ReadRepoLocalConfigRequest(repoRoot),
        ).config.validationGate.gradleWrapper,
      validationDepth = validationDepth,
      phaseTimeoutMillis = timeout?.inWholeMilliseconds,
    ).also { it.canonicalInputs() }
  }

  fun resolveRecordedInputs(
    repoRoot: Path,
    plan: ResolvedPhaseExecutionPlan,
  ): EffectiveGatePolicyInputs {
    val settings = plan.effectivePolicySettings ?: incompatible()
    val wrapper = config.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config.validationGate.gradleWrapper
    val family =
      if (plan.qualityGateSelection == FeatureTaskRuntimeQualityGateSelection.BUILD) {
        ValidationGateCommandFamily.BUILD
      } else {
        ValidationGateCommandFamily.VALIDATION
      }
    return gateResolver.declaredCandidates().map { candidate ->
      val slug =
        when (candidate) {
          is ValidationGateResolution.Declared -> candidate.packSlug
          is ValidationGateResolution.Absent -> candidate.routedPackSlug
          is ValidationGateResolution.Incompatible -> incompatible()
        }
      EffectiveGatePolicyInputs(
        family,
        slug,
        (candidate as? ValidationGateResolution.Declared)?.declaration,
        wrapper,
        settings.validationDepth,
        settings.phaseTimeoutMillis,
      ).frozen()
    }.singleOrNull { inputs ->
      FeatureTaskRuntimeEffectivePolicies.resolve(plan, inputs).sortedBy { it.id } == plan.effectivePolicies
    } ?: incompatible()
  }

  private fun requireBuildGate(
    inputs: EffectiveGatePolicyInputs,
    recorded: Boolean,
  ) {
    if (inputs.commandFamily != ValidationGateCommandFamily.BUILD) return
    if (ValidationGateCyclePhase.entries.all { !inputs.commandArgv(it).isNullOrEmpty() }) return
    val pack = inputs.packSlug ?: "unrouted"
    val source = if (recorded) "Recorded" else "Selected"
    val recovery =
      if (recorded) {
        " The original execution plan is retained." +
          " Resume requires a reviewed semantic mapping to a declared build gate."
      } else {
        " Repair pack routing or its build commands before creating the workflow."
      }
    throw MissingValidationGateError("$source build gate pack '$pack' has no complete build command pair.$recovery")
  }

  private fun recordedPlan(workflowId: String): ResolvedPhaseExecutionPlan =
    compatibility.requireSupportedComposition(recordedDescriptor(workflowId))

  private fun recordedDescriptor(workflowId: String): ByteArray? =
    database.read { unit ->
      val descriptor =
        unit.workflowStates.getFeatureTaskWorkflow(workflowId)?.toSnapshot()?.artifacts?.let {
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(it)
        }
      descriptor?.let { JsonCodec.valueToJsonString(it).toByteArray(Charsets.UTF_8) }
    }

  private fun requireRequestedSettings(
    plan: ResolvedPhaseExecutionPlan,
    qualityGate: FeatureTaskRuntimeQualityGateSelection?,
    validationDepth: ValidationDepth,
    timeout: Duration?,
  ) {
    val settings = plan.effectivePolicySettings ?: incompatible()
    if (plan.qualityGateSelection != qualityGate || settings.validationDepth != validationDepth ||
      settings.phaseTimeoutMillis != timeout?.inWholeMilliseconds
    ) {
      incompatible()
    }
  }

  private fun listedPaths(inventory: WorkflowGitNameListResult): List<String> =
    when (inventory) {
      is WorkflowGitNameListResult.Listed -> inventory.names
      is WorkflowGitNameListResult.Failed -> incompatible()
    }

  private fun incompatible(): Nothing = throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
}
