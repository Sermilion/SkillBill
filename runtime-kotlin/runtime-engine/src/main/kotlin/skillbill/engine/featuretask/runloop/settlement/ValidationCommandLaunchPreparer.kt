package skillbill.engine.featuretask.runloop.settlement

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.config.projectchecks.ProjectCheckCategory
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.contracts.config.projectchecks.wireValue
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.engine.featuretask.model.phase.ValidationCommandLaunchTransport
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.engine.featuretask.runloop.qualitygate.RuntimeQualityGateCycles
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.engine.featuretask.validation.projectchecks.ProjectCheckCommandDiscoveryRequest
import skillbill.engine.featuretask.validation.projectchecks.ProjectCheckCommandResolver
import skillbill.engine.featuretask.validation.projectchecks.ProjectCheckCommandResolverRequest
import skillbill.engine.featuretask.validation.projectchecks.ProjectCheckCommandSnapshotAdmission
import skillbill.engine.featuretask.validation.projectchecks.ProjectCheckConfigAccessSnapshot
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.MonitorRepairValidationSnapshotKey
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.scaffold.model.ValidationGateDeclaration
import java.nio.file.Path

@Inject
public class ValidationCommandLaunchPreparer(
  private val database: DatabaseSessionFactory,
  private val resolver: ProjectCheckCommandResolver,
  private val qualityGateCycles: RuntimeQualityGateCycles,
  private val gitOperations: WorkflowGitOperations,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val diagnostics: RuntimeDiagnostics,
  private val executionPlans: FeatureTaskRuntimeExecutionPlanResolver? = null,
) {
  internal fun prepareValidation(
    run: PhaseRun,
    attempt: Int,
  ): Pair<RequiredPhaseWrite, ValidationCommandLaunchTransport?> {
    acceptedValidationTransport(run)?.let { return RequiredPhaseWrite.Acknowledged to it }
    admittedLegacyTransport(run)?.let { return RequiredPhaseWrite.Acknowledged to it }
    return freezeOrReject(
      run,
      attempt,
      "Validation could not resolve validation command configuration.",
      "Validation requires a committed validation command configuration.",
      VALIDATION_LAUNCH_SEAM,
    ).let { (write, frozen) ->
      if (write is RequiredPhaseWrite.Acknowledged && frozen != null && frozen.validationCommandList.isEmpty()) {
        diagnostics.info(
          "seam=project_check_resolver outcome=known_empty project=${frozen.projectId} " +
            "category=${ProjectCheckCategory.VALIDATION.wireValue}",
        )
      }
      write to frozen?.let(::transport)
    }
  }

  internal fun prepareMonitorRepair(
    run: PhaseRun,
    identity: MonitorRepairValidationSnapshotKey,
    attempt: Int,
  ): Pair<RequiredPhaseWrite, ValidationCommandLaunchTransport?> {
    val legacy = admittedLegacyTransport(run)
    load(identity)?.let { saved ->
      if (legacy != null && (saved.validationCommands?.commands != legacy.initialCommands)) {
        return rejection(
          run,
          attempt,
          "Saved monitor validation commands conflict with the accepted legacy gate. Restore the recorded snapshot " +
            "or recover the execution plan before retrying this repair.",
        )
      }
      if (legacy != null) return RequiredPhaseWrite.Acknowledged to legacy
      return RequiredPhaseWrite.Acknowledged to transport(saved)
    }
    acceptedValidationTransport(run)?.let { saved ->
      return persist(identity, requireNotNull(saved.snapshotOrNull()), run, attempt)
        .let { (write, snapshot) -> write to snapshot?.let(::transport) }
    }
    if (legacy != null) return RequiredPhaseWrite.Acknowledged to legacy
    val (write, frozen) =
      freezeOrReject(
        run,
        attempt,
        "Monitor repair could not resolve validation command configuration.",
        "Monitor repair requires a validation command configuration.",
        MONITOR_LAUNCH_SEAM,
      )
    if (write is RequiredPhaseWrite.Rejected || frozen == null) {
      return write to frozen?.let(::transport)
    }
    return persist(identity, frozen, run, attempt).let { (write, snapshot) -> write to snapshot?.let(::transport) }
  }

  private fun acceptedValidationTransport(run: PhaseRun): ValidationCommandLaunchTransport? =
    admittedValidationSnapshot(run)?.let(::transport)

  private fun admittedLegacyTransport(run: PhaseRun): ValidationCommandLaunchTransport.LegacyCommands? {
    val acceptedPlan = run.request.acceptedGoalQualityExecutionPlan
    val admitted = run.request.admittedExecution
    val inputs = when {
      acceptedPlan != null ->
        requireNotNull(executionPlans) { "accepted goal-quality legacy commands require recorded plan resolution" }
          .resolveRecordedInputs(run.request.repoRoot, acceptedPlan)
      admitted != null -> admitted.effectiveInputs
      else -> return null
    }
    val initial = inputs.commandArgv(ValidationGateCyclePhase.INITIAL_DISCOVERY) ?: return null
    val postRepair = inputs.commandArgv(ValidationGateCyclePhase.POST_REPAIR_VERIFY) ?: initial
    val initialCommands = listOf(ProjectCheckCommand(initial, "."))
    return FeatureTaskRuntimeRunLoopValidationScope.legacyValidationLaunchTransport(
      initialCommands,
      listOf(ProjectCheckCommand(postRepair, ".")),
    )
  }

  private fun transport(snapshot: ProjectCheckCommandSnapshot): ValidationCommandLaunchTransport =
    FeatureTaskRuntimeRunLoopValidationScope.validationLaunchTransport(snapshot)

  private fun ValidationCommandLaunchTransport.snapshotOrNull(): ProjectCheckCommandSnapshot? =
    when (this) {
      is ValidationCommandLaunchTransport.Commands -> snapshot
      is ValidationCommandLaunchTransport.KnownEmpty -> snapshot
      is ValidationCommandLaunchTransport.LegacyCommands -> null
    }

  internal fun load(key: MonitorRepairValidationSnapshotKey): ProjectCheckCommandSnapshot? =
    database.read { unitOfWork -> unitOfWork.monitorRepairValidationSnapshots.load(key) }

  internal fun accept(
    key: MonitorRepairValidationSnapshotKey,
    snapshot: ProjectCheckCommandSnapshot,
  ): Boolean = database.transaction { unitOfWork -> unitOfWork.monitorRepairValidationSnapshots.accept(key, snapshot) }

  internal fun goalRepairCycle(run: PhaseRun): Int =
    database.read { unitOfWork -> unitOfWork.monitorCiCycles.latest(run.request.workflowId) }?.cycle
      ?: run.request.goalQualityValidationOccurrence.takeIf { it > 0 }
      ?: 1

  internal fun standaloneRepairCycle(
    run: PhaseRun,
    publishedRepairCount: Int?,
  ): Int {
    val ledgerCycle = publishedRepairCount?.plus(1)?.coerceAtLeast(1)
    if (ledgerCycle != null) {
      return ledgerCycle
    }
    val ownerId = run.request.workflowId
    val phaseId = run.phaseId
    val latest =
      database.read { unitOfWork -> unitOfWork.monitorRepairValidationSnapshots.latestCycle(ownerId, phaseId) }
        ?: return 1
    val key = MonitorRepairValidationSnapshotKey(ownerId, latest, phaseId)
    return latest.takeIf { load(key) != null } ?: 1
  }

  private fun persist(
    identity: MonitorRepairValidationSnapshotKey,
    snapshot: ProjectCheckCommandSnapshot,
    run: PhaseRun,
    attempt: Int,
  ): Pair<RequiredPhaseWrite, ProjectCheckCommandSnapshot?> {
    accept(identity, snapshot)
    return load(identity)?.let { RequiredPhaseWrite.Acknowledged to it }
      ?: rejection(run, attempt, "Monitor repair could not persist the accepted validation command snapshot.")
  }

  private fun freezeOrReject(
    run: PhaseRun,
    attempt: Int,
    unresolvedDetail: String,
    missingCategoryDetail: String,
    seam: String,
  ): Pair<RequiredPhaseWrite, ProjectCheckCommandSnapshot?> {
    val frozen =
      resolveAndFreeze(run, seam)
        ?: return rejection(run, attempt, unresolvedDetail)
    if (frozen.validationCommands == null) {
      return rejection(run, attempt, missingCategoryDetail)
    }
    return RequiredPhaseWrite.Acknowledged to frozen
  }

  private fun resolveAndFreeze(
    run: PhaseRun,
    seam: String,
  ): ProjectCheckCommandSnapshot? {
    val committed = resolver.loadCommitted(run.request.repoRoot)
    val loaded =
      if (VALIDATION_CATEGORIES.all { committed.config.category(it) != null }) {
        committed
      } else {
        val request = discoveryRequest(run) ?: return null
        resolver.resolve(request)
      }
    val validationBranchPaths = listedPaths(run.request.repoRoot) ?: return null
    val resolution = qualityGateCycles.resolve(run.request, validationBranchPaths)
    val (packSlug, declaration) = packContextForFreeze(loaded, resolution, seam) ?: return null
    val gradleWrapper =
      repoLocalConfig.readRepoLocalConfig(ReadRepoLocalConfigRequest(run.request.repoRoot))
        .config.validationGate.gradleWrapper
    return ProjectCheckCommandSnapshotAdmission.freeze(
      loaded,
      VALIDATION_CATEGORIES,
      packSlug,
      declaration,
      gradleWrapper,
    )
  }

  private fun discoveryRequest(run: PhaseRun): ProjectCheckCommandResolverRequest? {
    val validationBranchPaths = listedPaths(run.request.repoRoot) ?: return null
    val resolution = qualityGateCycles.resolve(run.request, validationBranchPaths)
    val declaration =
      declaredGate(resolution) ?: qualityGateCycles.declaredValidationGates()
        .firstNotNullOfOrNull { candidate ->
          (candidate as? ValidationGateResolution.Declared)?.declaration
        } ?: return null
    val packSlug = declaredPackSlug(resolution) ?: "none"
    return ProjectCheckCommandResolverRequest(
      run.request.repoRoot,
      VALIDATION_CATEGORIES,
      ProjectCheckCommandDiscoveryRequest(
        packSlug = packSlug,
        validationGate = declaration,
        issueKey = run.request.issueKey,
        agentId = run.request.invokedAgentId,
        modelOverride = run.launchAssignment?.launch?.effectiveModel,
        effortOverride = run.launchAssignment?.launch?.requestedEffort,
        timeout = run.request.timeout,
      ),
    )
  }

  private fun admittedValidationSnapshot(run: PhaseRun): ProjectCheckCommandSnapshot? =
    run.request.acceptedGoalQualityExecutionPlan?.projectCheckCommands?.takeIf { it.validationCommands != null }
      ?: run.request.admittedExecution?.plan?.projectCheckCommands?.takeIf { it.validationCommands != null }

  private fun packContextForFreeze(
    loaded: ProjectCheckConfigAccessSnapshot,
    resolution: ValidationGateResolution,
    seam: String,
  ): Pair<String?, ValidationGateDeclaration?>? {
    if (resolution !is ValidationGateResolution.Incompatible) {
      return ProjectCheckCommandSnapshotAdmission.packContext(resolution)
    }
    diagnostics.warning("seam=$seam outcome=incompatible reason=${resolution.reason}")
    return if (loaded.config.category(ProjectCheckCategory.VALIDATION) == null) {
      null
    } else {
      null to null
    }
  }

  private fun listedPaths(repoRoot: Path): List<String>? =
    when (val paths = gitOperations.trackedPaths(repoRoot)) {
      is WorkflowGitNameListResult.Listed -> paths.names
      is WorkflowGitNameListResult.Failed -> {
        diagnostics.warning(
          "seam=validation_launch_snapshot outcome=unreadable_paths cause=${paths.error} repo_root=$repoRoot",
        )
        null
      }
    }

  private fun declaredGate(resolution: ValidationGateResolution) =
    when (resolution) {
      is ValidationGateResolution.Declared -> resolution.declaration
      is ValidationGateResolution.Absent -> null
      is ValidationGateResolution.Incompatible -> null
    }

  private fun declaredPackSlug(resolution: ValidationGateResolution) =
    when (resolution) {
      is ValidationGateResolution.Declared -> resolution.packSlug
      is ValidationGateResolution.Absent -> resolution.routedPackSlug
      is ValidationGateResolution.Incompatible -> null
    }

  private fun rejection(
    run: PhaseRun,
    attempt: Int,
    detail: String,
  ): Pair<RequiredPhaseWrite, ProjectCheckCommandSnapshot?> =
    RequiredPhaseWrite.Rejected(
      RequiredPhaseWriteKind.START,
      run.request.workflowId,
      run.phaseId,
      attempt,
      detail = detail,
    ) to null

  private companion object {
    val VALIDATION_CATEGORIES = setOf(ProjectCheckCategory.VALIDATION)
    const val VALIDATION_LAUNCH_SEAM = "validation_launch_snapshot"
    const val MONITOR_LAUNCH_SEAM = "monitor_repair_validation_snapshot"
  }
}
