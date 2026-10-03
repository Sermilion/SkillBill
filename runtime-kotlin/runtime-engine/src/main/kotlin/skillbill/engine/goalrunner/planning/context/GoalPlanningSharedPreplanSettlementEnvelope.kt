package skillbill.engine.goalrunner.planning.context

import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.decomposition.parentSpecPath
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.outcome.resolvedGovernedPath
import skillbill.engine.goalrunner.planning.outcome.stopped
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningRecoveryKind
import skillbill.engine.goalrunner.planning.remedies.goalPlanningIncompatibleProvenanceStopReason
import skillbill.engine.goalrunner.planning.remedies.goalPlanningRemedySubtaskId
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore

internal fun freshPlanningPacket(
  shared: GoalPlanningSharedContext,
  state: GoalRunnerManifestState,
  contextDiscovery: GoalPlanningContextDiscovery,
  manifestFileStore: DecompositionManifestStore,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Map<String, Any?> {
  val discovered = contextDiscovery.loadPlanningContext(shared.repoRoot)
  val decomposition =
    manifestFileStore.readText(
      resolvedGovernedPath(
        shared.repoRoot,
        state.manifest.parentSpecPath.substringBeforeLast("/") + "/" + DECOMPOSITION_MANIFEST_FILENAME,
        repositoryEnclosingRootPort,
      ),
    )
  val packet =
    linkedMapOf<String, Any?>(
      GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION to GoalPlanningSharedContextPacket.VERSION,
      GoalPlanningSharedContextPacketPayloadKeys.REPOSITORY_IDENTITY to shared.repositoryIdentity,
      GoalPlanningSharedContextPacketPayloadKeys.NORMALIZED_ISSUE_KEY to shared.normalizedIssueKey,
      DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH to state.manifest.parentSpecPath,
      GoalPlanningSharedContextPacketPayloadKeys.PARENT_SPEC to
        shared.parentSpec.take(GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS),
      GoalPlanningSharedContextPacketPayloadKeys.DECOMPOSITION_MANIFEST to
        decomposition.take(GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS),
      GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY to GoalPlanningSharedContextPacket.catalog(discovered),
      GoalPlanningSharedContextPacketPayloadKeys.VALIDATION_GUIDANCE to
        discovered.validationGuidance.take(
          GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS,
        ),
      GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS to
        GoalPlanningSharedContextPacket.orderedSubtasks(state.manifest.subtasks),
    )
  return packet + (
    GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256 to GoalPlanningSharedContextPacket.digest(packet)
  )
}

internal fun incompatibleProvenance(
  shared: GoalPlanningSharedContext,
  kind: GoalPlanningRecoveryKind,
): GoalPlanningSweepOutcome.Stopped =
  stopped(
    shared,
    0,
    goalPlanningIncompatibleProvenanceStopReason(
      shared.issueKey,
      goalPlanningRemedySubtaskId(shared.manifest.subtasks),
      kind,
    ),
    GoalPlanningSweepConstants.PHASE_PREPLAN,
  )
