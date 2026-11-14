package skillbill.engine.featuretask.lifecycle.execution

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.config.projectchecks.ProjectCheckCategory
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.engine.featuretask.validation.projectchecks.ProjectCheckCommandDiscoveryRequest
import skillbill.engine.featuretask.validation.projectchecks.ProjectCheckCommandResolver
import skillbill.engine.featuretask.validation.projectchecks.ProjectCheckCommandResolverRequest
import skillbill.engine.featuretask.validation.projectchecks.ProjectCheckCommandSnapshotAdmission
import skillbill.engine.featuretask.validation.projectchecks.ProjectCheckConfigAccessSnapshot
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
import skillbill.scaffold.model.ValidationGateDeclaration
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
  private val projectCheckCommands: ProjectCheckCommandResolver,
  private val diagnostics: RuntimeDiagnostics,
) {
  public fun resolveCreation(
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
    val admitted = admitProjectCheckCommands(request, plan, inputs)
    recordSelectedStrategies(admitted.plan)
    requireBuildGate(admitted.inputs, recorded = false)
    return ValidatedFeatureTaskRuntimeExecutionPlan.read(
      codec.encodeExecution(admitted.plan, admitted.inputs),
      validator,
    )
  }

  public fun creationQualityGate(
    qualityGate: FeatureTaskRuntimeQualityGateSelection?,
  ): FeatureTaskRuntimeQualityGateSelection? = qualityGate

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
      commandFamily = commandFamilyFor(qualityGate),
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
    val settings = plan.effectivePolicySettings ?: incompatible("Recorded execution settings are missing.")
    val current =
      resolveInputs(
        repoRoot,
        plan.qualityGateSelection,
        settings.validationDepth,
        settings.phaseTimeoutMillis?.milliseconds,
      ).frozen()
    val categories = ProjectCheckCommandSnapshotAdmission.categoriesForQualityGate(plan.qualityGateSelection)
    val committed = projectCheckCommands.loadCommitted(repoRoot)
    val inputs =
      if (categories.isNotEmpty() && categories.all { committed.config.category(it) != null }) {
        current.copy(
          projectCheckSnapshot =
            ProjectCheckCommandSnapshotAdmission.freeze(
              committed,
              categories,
              current.packSlug,
              current.declaration,
              current.gradleWrapper,
            ),
        ).frozen()
      } else {
        current
      }
    if (!FeatureTaskRuntimeEffectivePolicies.matchesRecorded(plan, inputs)) incompatible()
    diagnostics.info(
      "seam=execution_plan_gate_commands value_used=current " +
        "definition=${plan.definitionId} pack=${current.packSlug ?: "none"} reason=resume_current_validation",
    )
    return inputs
  }

  private fun admitProjectCheckCommands(
    request: FeatureTaskRuntimeExecutionPlanCreationRequest,
    plan: ResolvedPhaseExecutionPlan,
    inputs: EffectiveGatePolicyInputs,
  ): AdmittedProjectCheckPlan {
    if (!FeatureTaskRuntimeEffectivePolicies.runsGateCommands(plan)) {
      return AdmittedProjectCheckPlan(plan, inputs)
    }
    val categories = ProjectCheckCommandSnapshotAdmission.categoriesForQualityGate(plan.qualityGateSelection)
    if (categories.isEmpty()) {
      return AdmittedProjectCheckPlan(plan, inputs)
    }
    if (request.invokedAgentId == null) {
      incompatible("Fresh admission requires an invoked agent for project checks.")
    }
    if (request.issueKey == null) {
      incompatible("Fresh admission requires an issue key for project checks.")
    }
    val resolution = gateResolver.resolve(listedPaths(git.trackedPaths(request.repoRoot)))
    if (resolution is ValidationGateResolution.Incompatible) {
      incompatible(resolution.reason)
    }
    val (packSlug, declaration) = ProjectCheckCommandSnapshotAdmission.packContext(resolution)
    val loaded = loadProjectCheckConfig(request, categories, packSlug to declaration)
    val gradleWrapper =
      config.readRepoLocalConfig(ReadRepoLocalConfigRequest(request.repoRoot)).config.validationGate.gradleWrapper
    val snapshot =
      ProjectCheckCommandSnapshotAdmission.freeze(loaded, categories, packSlug, declaration, gradleWrapper)
    val admittedInputs =
      inputs
        .copy(
          projectCheckSnapshot = snapshot,
          gradleWrapper = gradleWrapper,
          packSlug = snapshot.parserMetadata?.packSlug,
        )
        .frozen()
    return AdmittedProjectCheckPlan(plan.withProjectCheckCommands(snapshot), admittedInputs)
  }

  private fun loadProjectCheckConfig(
    request: FeatureTaskRuntimeExecutionPlanCreationRequest,
    categories: Set<ProjectCheckCategory>,
    packContext: Pair<String?, ValidationGateDeclaration?>,
  ): ProjectCheckConfigAccessSnapshot {
    val committed = projectCheckCommands.loadCommitted(request.repoRoot)
    if (categories.all { committed.config.category(it) != null }) {
      return committed
    }
    val (packSlug, declaration) = packContext
    val discoveryDeclaration =
      declaration
        ?: gateResolver.declaredCandidates().firstNotNullOfOrNull { candidate ->
          (candidate as? ValidationGateResolution.Declared)?.declaration
        }
        ?: incompatible("Project check discovery requires a declared validation gate for unresolved categories.")
    return projectCheckCommands.resolve(
      ProjectCheckCommandResolverRequest(
        request.repoRoot,
        categories,
        ProjectCheckCommandDiscoveryRequest(
          packSlug = packSlug ?: "none",
          validationGate = discoveryDeclaration,
          issueKey = requireNotNull(request.issueKey),
          agentId = requireNotNull(request.invokedAgentId),
          modelOverride =
            request.stepLaunchAssignments.values.firstOrNull()?.launch?.effectiveModel
              ?: request.stepLaunchAssignments.values.firstOrNull()?.launch?.requestedModel,
          effortOverride = request.stepLaunchAssignments.values.firstOrNull()?.launch?.requestedEffort,
          timeout = request.timeout,
        ),
      ),
    )
  }

  private data class AdmittedProjectCheckPlan(
    val plan: ResolvedPhaseExecutionPlan,
    val inputs: EffectiveGatePolicyInputs,
  )

  private fun requireBuildGate(
    inputs: EffectiveGatePolicyInputs,
    recorded: Boolean,
  ) {
    if (inputs.commandFamily != ValidationGateCommandFamily.BUILD) return
    snapshotBuildGateFailure(inputs)?.let { throw it }
    if (inputs.projectCheckSnapshot != null) return
    if (ValidationGateCyclePhase.entries.all { !inputs.commandArgv(it).isNullOrEmpty() }) return
    throw missingValidationGate(incompleteBuildGateMessage(inputs, recorded))
  }

  private fun snapshotBuildGateFailure(inputs: EffectiveGatePolicyInputs): Throwable? {
    val snapshot = inputs.projectCheckSnapshot ?: return null
    val buildCommands = snapshot.buildCommands
    val pack = inputs.packSlug ?: "unrouted"
    return when {
      buildCommands == null -> {
        incompatible("Build gate admission requires a resolved build command category.")
      }
      buildCommands.commands.isEmpty() ->
        missingValidationGate(
          "Project build configuration for pack '$pack' is explicitly empty." +
            " Repair saved build commands before creating the workflow.",
        )
      else -> null
    }
  }

  private fun incompleteBuildGateMessage(
    inputs: EffectiveGatePolicyInputs,
    recorded: Boolean,
  ): String {
    val pack = inputs.packSlug ?: "unrouted"
    return if (recorded) {
      "Recorded build gate pack '$pack' has no complete build command pair." +
        " The original execution plan is retained." +
        " Resume requires a reviewed semantic mapping to a declared build gate."
    } else {
      "Selected build gate pack '$pack' has no complete build command pair." +
        " Repair pack routing or its build commands before creating the workflow."
    }
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

  private fun commandFamilyFor(qualityGate: FeatureTaskRuntimeQualityGateSelection?): ValidationGateCommandFamily =
    if (qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD) {
      ValidationGateCommandFamily.BUILD
    } else {
      ValidationGateCommandFamily.VALIDATION
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
