package skillbill.engine.goalrunner.planning.recovery

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.parentSpecPath
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacketPayloadKeys
import skillbill.engine.goalrunner.planning.context.goalPlanningImmutableDecompositionHash
import skillbill.engine.goalrunner.planning.model.GoalPlanningStatusAlignRequest
import skillbill.engine.goalrunner.planning.outcome.canonicalRepository
import skillbill.engine.goalrunner.planning.outcome.lexicalPath
import skillbill.engine.goalrunner.planning.remedies.alignPlanningStatusWithLaunchRecoverability
import skillbill.engine.goalrunner.planning.remedies.goalPlanningRemedySubtaskId
import skillbill.engine.goalrunner.planning.remedies.statusRecoverabilityOrRefuse
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.text.sha256HexUtf8
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Path

fun interface GoalPlanningStatusReasonCoherence {
  fun align(request: GoalPlanningStatusAlignRequest): GoalPlanningStatusSnapshot
}

@Inject
class LaunchAlignedGoalPlanningStatusReasonCoherence(
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val manifestFileStore: DecompositionManifestStore,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) : GoalPlanningStatusReasonCoherence {
  override fun align(request: GoalPlanningStatusAlignRequest): GoalPlanningStatusSnapshot {
    if (!request.snapshot.sharedPreplanPrepared) return request.snapshot
    val recoverability =
      statusRecoverabilityOrRefuse {
        classifyForStatus(request)
      }
    val remedySubtaskId =
      request.snapshot.currentPlanningSubtaskId
        ?.takeIf { it > 0 }
        ?: goalPlanningRemedySubtaskId(request.manifest.subtasks)
    return alignPlanningStatusWithLaunchRecoverability(
      snapshot = request.snapshot,
      recoverability = recoverability,
      issueKey = request.issueKey,
      remedySubtaskId = remedySubtaskId,
    )
  }

  private fun classifyForStatus(request: GoalPlanningStatusAlignRequest): GoalPlanningProvenanceRecoverability {
    val canonicalRepository =
      repositoryEnclosingRootPort.canonicalPath(
        repositoryEnclosingRootPort.enclosingRepositoryRoot(request.repoRoot),
      )
    val identity =
      GoalPlanningIdentity(
        request.parentWorkflowId,
        FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(request.issueKey),
        repositoryEnclosingRootPort.repositoryIdentity(request.repoRoot),
      )
    val existing =
      checkpoint.findSharedPreplan(identity)
        ?: return GoalPlanningProvenanceRecoverability.Reuse(
          GoalPlanningContractProvenance(
            parentSpecHash = "",
            decompositionManifestHash = "",
            planningContractId = GOAL_PLANNING_PREPARATION_SCHEMA_ID,
          ),
        )
    val parentSpecPath = lexicalPath(canonicalRepository, request.manifest.parentSpecPath)
    val currentParentSpec = manifestFileStore.readText(parentSpecPath)
    val current =
      GoalPlanningContractProvenance(
        parentSpecHash = sha256HexUtf8(currentParentSpec),
        decompositionManifestHash = goalPlanningImmutableDecompositionHash(request.manifest),
        planningContractId = GOAL_PLANNING_PREPARATION_SCHEMA_ID,
      )
    val packetParentSpec = planningPacketParentSpec(existing)
    val savedParentSpec =
      if (existing.provenance.parentSpecHash == current.parentSpecHash) {
        currentParentSpec
      } else {
        packetParentSpec
      }
    return classifyGoalPlanningProvenanceRecoverability(
      existing = existing,
      current = current,
      savedParentSpec = savedParentSpec,
      currentParentSpec = currentParentSpec,
    )
  }

  private fun planningPacketParentSpec(existing: SharedGoalPreplanCheckpoint): String? {
    val packet =
      JsonCodec.parseObjectOrNull(existing.preplanPayload)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.get("_goal_planning_shared_context")
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: return null
    return packet[GoalPlanningSharedContextPacketPayloadKeys.PARENT_SPEC] as? String
  }

  private fun lexicalPath(
    canonicalRepository: Path,
    governingPath: String,
  ): Path {
    val path = Path.of(governingPath)
    return (if (path.isAbsolute) path else canonicalRepository.resolve(path)).toAbsolutePath().normalize()
  }
}
