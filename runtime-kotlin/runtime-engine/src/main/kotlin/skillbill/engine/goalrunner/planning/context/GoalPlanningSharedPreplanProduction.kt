package skillbill.engine.goalrunner.planning.context

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.engine.goalplanning.GoalPlanningMigrationAdmission
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningPhaseAttemptGate
import skillbill.engine.goalrunner.planning.model.GoalPlanningLaunch
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.engine.goalrunner.planning.model.GoalPlanningProduceAttemptArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.outcome.canonicalRepository
import skillbill.engine.goalrunner.planning.outcome.proseRecordPayload
import skillbill.engine.goalrunner.planning.outcome.resolvedGovernedPath
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.text.sha256HexUtf8
import java.nio.file.Path

@Inject
class GoalPlanningSharedPreplanProduction(
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  private val manifestFileStore: DecompositionManifestStore,
  private val contextDiscovery: GoalPlanningContextDiscovery,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val attemptGate: GoalPlanningPhaseAttemptGate,
  private val migrationAdmission: GoalPlanningMigrationAdmission,
) {
  internal fun findAdmittedSharedPreplan(identity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? {
    migrationAdmission.admit(identity)
    return checkpoint.findSharedPreplan(identity)
  }

  internal fun produceSharedPreplan(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    provenance: GoalPlanningContractProvenance,
    launch: GoalPlanningLaunch,
  ): Result<SharedPreplanProduction> =
    produceSharedPreplanCheckpoint(shared, request, provenance, launch).mapCatching { production ->
      production.also {
        if (it is SharedPreplanProduction.Produced) checkpoint.recheckpointSharedPreplan(it.checkpoint)
      }
    }.onFailure { error ->
      error.rethrowIfCooperativeCancellationOrInterruption()
    }

  internal fun produceSharedPreplanCheckpoint(
    shared: GoalPlanningSharedContext,
    request: GoalRunnerRunRequest,
    provenance: GoalPlanningContractProvenance,
    launch: GoalPlanningLaunch,
  ): Result<SharedPreplanProduction> =
    runCatching {
      val runInvariants = invariantsSource.read(shared.parentSpecPath)
      val preplanProduction =
        attemptGate.producePhase(
          GoalPlanningProduceAttemptArgs(
            phase =
              GoalPlanningPhaseContext(
                shared = shared,
                request = request,
                subtask = null,
                runInvariants = runInvariants,
                phaseId = GoalPlanningSweepConstants.PHASE_PREPLAN,
                launch = launch,
              ),
            recordedOutputs = emptyList(),
          ),
        )
      when (preplanProduction) {
        is GoalPlanningPhaseProduction.Stopped -> SharedPreplanProduction.Stopped(preplanProduction.outcome)
        is GoalPlanningPhaseProduction.RequiredWriteRejected ->
          SharedPreplanProduction.RequiredWriteRejected(preplanProduction.rejection)
        else ->
          producedSharedPreplan(shared, provenance, preplanProduction as GoalPlanningPhaseProduction.Captured)
      }
    }

  private fun producedSharedPreplan(
    shared: GoalPlanningSharedContext,
    provenance: GoalPlanningContractProvenance,
    captured: GoalPlanningPhaseProduction.Captured,
  ): SharedPreplanProduction.Produced {
    val preplanPayload =
      enrichPreplan(
        proseRecordPayload(GoalPlanningSweepConstants.PHASE_PREPLAN, captured.payload),
        shared.planningPacket,
      )
    return SharedPreplanProduction.Produced(
      SharedGoalPreplanCheckpoint(
        identity = GoalPlanningIdentity(shared.parentWorkflowId, shared.normalizedIssueKey, shared.repositoryIdentity),
        provenance = provenance,
        payloadSha256 = sha256HexUtf8(preplanPayload),
        preplanPayload = preplanPayload,
      ),
    )
  }

  private fun enrichPreplan(
    payload: String,
    packet: Map<String, Any?>,
  ): String {
    val root =
      JsonCodec.parseObjectOrNull(payload)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: error("preplan payload is not a JSON object")
    val produced =
      JsonCodec.anyToStringAnyMap(root[SharedPayloadKeys.PRODUCED_OUTPUTS])
        ?: error("preplan produced_outputs is not an object")
    return JsonCodec.mapToJsonString(
      root + (
        SharedPayloadKeys.PRODUCED_OUTPUTS to
          (produced + (GoalPlanningSweepConstants.SHARED_CONTEXT_FIELD to packet))
      ),
    )
  }

  internal fun planningPacketFrom(record: SharedGoalPreplanCheckpoint): Map<String, Any?>? =
    JsonCodec.parseObjectOrNull(record.preplanPayload)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get(GoalPlanningSweepConstants.SHARED_CONTEXT_FIELD)
      ?.let(JsonCodec::anyToStringAnyMap)

  internal fun gatherSharedContext(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    recoveredPacket: Map<String, Any?>?,
  ): GoalPlanningSharedContext {
    val canonicalRepository = canonicalRepository(request.repoRoot, repositoryEnclosingRootPort)
    val parentSpecGoverningPath = state.manifest.parentSpecPath
    val manifestGoverningPath = parentSpecGoverningPath.substringBeforeLast("/") + "/" + DECOMPOSITION_MANIFEST_FILENAME
    val resolvedParentSpecPath =
      resolvedGovernedPath(
        canonicalRepository,
        parentSpecGoverningPath,
        repositoryEnclosingRootPort,
      )
    val parentSpec = manifestFileStore.readText(resolvedParentSpecPath)
    val decomposition =
      manifestFileStore.readText(
        resolvedGovernedPath(canonicalRepository, manifestGoverningPath, repositoryEnclosingRootPort),
      )
    val parentSpecHash = sha256HexUtf8(parentSpec)
    val decompositionManifestHash = goalPlanningImmutableDecompositionHash(state.manifest)
    val repositoryIdentity = repositoryEnclosingRootPort.repositoryIdentity(request.repoRoot)
    val planningPacket =
      recoveredPacket?.let(GoalPlanningSharedContextPacket::migrate)
        ?: createPlanningPacket(
          PlanningPacketInputs(
            state = state,
            canonicalRepository = canonicalRepository,
            parentSpecGoverningPath = parentSpecGoverningPath,
            parentSpec = parentSpec,
            decomposition = decomposition,
            repositoryIdentity = repositoryIdentity,
          ),
        )
    GoalPlanningSharedContextPacket.validate(
      packet = planningPacket,
      repositoryIdentity = repositoryIdentity,
      normalizedIssueKey = state.manifest.issueKey.trim().uppercase(),
      parentSpecPath = parentSpecGoverningPath,
      subtasks = state.manifest.subtasks,
    )
    return GoalPlanningSharedContext(
      issueKey = request.issueKey,
      normalizedIssueKey = state.manifest.issueKey.trim().uppercase(),
      parentWorkflowId = state.parentWorkflowId,
      manifest = state.manifest,
      controlState = state.controlState,
      repositoryIdentity = repositoryIdentity,
      parentSpec = parentSpec,
      parentSpecHash = parentSpecHash,
      decompositionManifestHash = decompositionManifestHash,
      repoRoot = canonicalRepository,
      invokedAgentId = request.invokedAgentId,
      configuredAgentOverrideId = request.configuredAgentOverrideId,
      specSource = state.manifest.specSource,
      parentSpecPath = resolvedParentSpecPath,
      planningPacket = planningPacket,
    )
  }

  private data class PlanningPacketInputs(
    val state: GoalRunnerManifestState,
    val canonicalRepository: Path,
    val parentSpecGoverningPath: String,
    val parentSpec: String,
    val decomposition: String,
    val repositoryIdentity: String,
  )

  private fun createPlanningPacket(inputs: PlanningPacketInputs): Map<String, Any?> {
    val discovered = contextDiscovery.loadPlanningContext(inputs.canonicalRepository)
    val packet =
      linkedMapOf<String, Any?>(
        GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION to GoalPlanningSharedContextPacket.VERSION,
        GoalPlanningSharedContextPacketPayloadKeys.REPOSITORY_IDENTITY to inputs.repositoryIdentity,
        GoalPlanningSharedContextPacketPayloadKeys.NORMALIZED_ISSUE_KEY to
          inputs.state.manifest.issueKey.trim().uppercase(),
        DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH to inputs.parentSpecGoverningPath,
        GoalPlanningSharedContextPacketPayloadKeys.PARENT_SPEC to
          inputs.parentSpec.take(GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS),
        GoalPlanningSharedContextPacketPayloadKeys.DECOMPOSITION_MANIFEST to
          inputs.decomposition.take(GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS),
        GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY to
          GoalPlanningSharedContextPacket.catalog(
            discovered,
          ),
        GoalPlanningSharedContextPacketPayloadKeys.VALIDATION_GUIDANCE to
          discovered.validationGuidance.take(GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS),
        GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS to
          GoalPlanningSharedContextPacket.orderedSubtasks(inputs.state.manifest.subtasks),
      )
    return packet + (
      GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256 to
        GoalPlanningSharedContextPacket.digest(packet)
    )
  }
}
