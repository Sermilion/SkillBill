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
import skillbill.error.shellcontent.missingValidationGate
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.executionplan.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.boundedSafeModelIdentity
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

@Inject
public class FeatureTaskRuntimeExecutionPlanResolver(
  private val strategies: PhaseStrategyLookup,
  private val codec: FeatureTaskRuntimeExecutionPlanCodec,
  private val validator: FeatureTaskRuntimeExecutionPlanValidator,
  private val gateResolver: ValidationGateResolver,
  private val git: WorkflowGitOperations,
  private val config: RepoLocalConfigPort,
  private val database: DatabaseSessionFactory,
  private val compatibility: FeatureTaskRuntimeExecutionPlanCompatibility,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun resolveCreation(
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
      val recordedInputs = resolveRecordedInputs(repoRoot, plan)
      requireRequestedSettings(plan, qualityGate, validationDepth, timeout, recordedInputs)
      requireBuildGate(recordedInputs, recorded = true)
      return ValidatedFeatureTaskRuntimeExecutionPlan.read(requireNotNull(recorded), validator)
    }
    val plan =
      strategies.executionPlan(
        PhaseStrategySelectionFacts(
          definition,
          setOfNotNull(
            reviewMode,
            qualityGate,
            request.qaSelection ?: FeatureTaskRuntimeQaSelection.AUTO.takeIf { PhaseSlot.QA in definition.slots },
          ),
          request.stepLaunchAssignments,
        ),
      )
    val inputs = resolveInputs(repoRoot, plan.qualityGateSelection, validationDepth, timeout)
    recordSelectedStrategies(plan)
    requireBuildGate(inputs, recorded = false)
    return ValidatedFeatureTaskRuntimeExecutionPlan.read(codec.encodeExecution(plan, inputs), validator)
  }

  internal fun creationQualityGate(
    repoRoot: Path,
    qualityGate: FeatureTaskRuntimeQualityGateSelection?,
  ): FeatureTaskRuntimeQualityGateSelection? {
    if (qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD) {
      requireBuildGate(resolveInputs(repoRoot, qualityGate, ValidationDepth.DEFAULT, null), recorded = false)
    }
    return qualityGate
  }

  internal fun resolveInputs(
    repoRoot: Path,
    qualityGate: FeatureTaskRuntimeQualityGateSelection?,
    validationDepth: ValidationDepth,
    timeout: Duration?,
    workflowId: String? = null,
  ): EffectiveGatePolicyInputs {
    if (workflowId != null) {
      val plan = recordedPlan(workflowId)
      val recordedInputs = resolveRecordedInputs(repoRoot, plan)
      requireRequestedSettings(plan, qualityGate, validationDepth, timeout, recordedInputs)
      return recordedInputs
    }
    val resolution =
      gateResolver.resolve(listedPaths(git.trackedPaths(repoRoot)))
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

  internal fun resolveRecordedInputs(
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
    if (recorded) {
      throw missingValidationGate(
        "Recorded build gate pack '$pack' has no complete build command pair." +
          " The original execution plan is retained." +
          " Resume requires a reviewed semantic mapping to a declared build gate.",
      )
    }
    throw missingValidationGate(
      "Selected build gate pack '$pack' has no complete build command pair." +
        " Repair pack routing or its build commands before creating the workflow.",
    )
  }

  private fun recordSelectedStrategies(plan: ResolvedPhaseExecutionPlan) {
    plan.selectedStrategies.forEach { identity ->
      val participating = identity.steps.mapNotNull(plan.stepLaunchAssignments::get)
      val unknownReasons = participating.mapNotNull { it.launch.unknownReason }
      val profiles = participating.map { it.profile.wireValue }.distinct().joinToString(",")
      val requested =
        participating.mapNotNull { assignment ->
          assignment.launch.requestedModel?.let(::boundedSafeModelIdentity)
        }.distinct().joinToString(",")
      val effective =
        participating.mapNotNull { assignment ->
          assignment.launch.effectiveModel?.let(::boundedSafeModelIdentity)
        }.distinct().joinToString(",")
      val provenance = participating.map { it.launch.provenance.wireValue }.distinct().joinToString(",")
      val reason = unknownReasons.map { it.wireValue }.distinct().joinToString(",")
      val detail =
        "slot=${identity.slot.wireValue} strategyId=${identity.strategyId} " +
          "semanticRevision=${identity.semanticRevision} profiles=${profiles.ifBlank { "none" }} " +
          "requested=${requested.ifBlank { "none" }} effective=${effective.ifBlank { "none" }} " +
          "provenance=${provenance.ifBlank { "none" }} unknownReason=${reason.ifBlank { "none" }}"
      if (unknownReasons.isEmpty()) {
        diagnostics.info("launch strategy selected: $detail")
      } else {
        diagnostics.warning(
          "seam=launch_model_resolution value_expected=resolved value_used=${reason.ifBlank { "none" }} " +
            "model=${effective.ifBlank { requested }.ifBlank { "none" }} $detail",
        )
      }
    }
  }

  private fun recordedPlan(workflowId: String): ResolvedPhaseExecutionPlan =
    compatibility.requireSupportedComposition(recordedDescriptor(workflowId))

  internal fun planningResumeTimeout(
    workflowId: String,
    requestedTimeout: Duration?,
  ): Duration? {
    val plan = recordedPlan(workflowId)
    if (plan.definitionId != SkeletonDefinition.PLAN.id) incompatible("Planning resume requires a plan descriptor.")
    val settings = plan.effectivePolicySettings ?: incompatible("Recorded execution settings are missing.")
    val recordedTimeout = settings.phaseTimeoutMillis?.milliseconds
    if (recordedTimeout != requestedTimeout) {
      diagnostics.info(
        "seam=goal_planning_resume_timeout value_used=${settings.phaseTimeoutMillis} " +
          "value_expected=${requestedTimeout?.inWholeMilliseconds} reason=retain_recorded_policy",
      )
    }
    return recordedTimeout
  }

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
    recordedInputs: EffectiveGatePolicyInputs,
  ) {
    val settings = plan.effectivePolicySettings ?: incompatible()
    val compatibleQualityGate =
      plan.qualityGateSelection == qualityGate ||
        (
          qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD &&
            plan.qualityGateSelection == FeatureTaskRuntimeQualityGateSelection.VALIDATE &&
            recordedInputs.packSlug == null &&
            recordedInputs.declaration == null
        )
    val mismatches =
      buildList {
        if (!compatibleQualityGate) {
          add(
            "${Keys.QUALITY_GATE_SELECTION} recorded=${plan.qualityGateSelection?.wireValue} " +
              "requested=${qualityGate?.wireValue}",
          )
        }
        if (settings.validationDepth != validationDepth) {
          add(
            "${Keys.VALIDATION_DEPTH} recorded=${settings.validationDepth.wireValue} " +
              "requested=${validationDepth.wireValue}",
          )
        }
        if (settings.phaseTimeoutMillis != timeout?.inWholeMilliseconds) {
          add(
            "${Keys.PHASE_TIMEOUT_MILLIS} recorded=${settings.phaseTimeoutMillis} " +
              "requested=${timeout?.inWholeMilliseconds}",
          )
        }
      }
    if (mismatches.isNotEmpty()) incompatible("Execution settings differ: ${mismatches.joinToString("; ")}.")
  }

  private fun listedPaths(inventory: WorkflowGitNameListResult): List<String> =
    when (inventory) {
      is WorkflowGitNameListResult.Listed -> inventory.names
      is WorkflowGitNameListResult.Failed -> incompatible()
    }

  private fun incompatible(detail: String? = null): Nothing =
    throw IncompatibleFeatureTaskRuntimeExecutionPlanError(
      detail,
    )
}
