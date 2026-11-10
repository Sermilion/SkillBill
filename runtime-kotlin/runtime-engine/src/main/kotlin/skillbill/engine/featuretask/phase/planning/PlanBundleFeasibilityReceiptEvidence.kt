package skillbill.engine.featuretask.phase.planning

import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityAcceptedLine
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityDependencyHash
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityIdentity
import skillbill.workflow.taskruntime.model.planning.transitiveDependencyIds

internal fun feasibilityAcceptedLines(
  baseline: PlanBundleFeasibilityIdentity,
  receipt: PlanBundleFeasibility.ReviewReceipt,
): List<PlanBundleFeasibilityAcceptedLine> {
  val identityById = baseline.subtasks.associateBy { it.id }
  return receipt.lines.filterNot { it.conflict }.map { line ->
    val subtask = identityById.values.first { line.ref in it.criterionRefs }
    PlanBundleFeasibilityAcceptedLine(
      ref = line.ref,
      line = line.originalLine,
      judgedParentSha256 = baseline.parentSha256,
      judgedManifestSha256 = baseline.manifestSha256,
      judgedSpecSha256 = subtask.specSha256,
      dependencyHashes =
        baseline.transitiveDependencyIds(subtask.id).map { dependencyId ->
          PlanBundleFeasibilityDependencyHash(
            dependencyId,
            identityById.getValue(dependencyId).specSha256,
          )
        },
    )
  }
}
