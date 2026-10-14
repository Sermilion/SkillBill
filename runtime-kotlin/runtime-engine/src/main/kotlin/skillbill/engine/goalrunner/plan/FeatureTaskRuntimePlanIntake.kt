package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.execution.governedFeatureTaskSpecPath
import skillbill.engine.goalrunner.intake.GoalIntakePreparation
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSeed
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import java.nio.file.Path

/** How the plan's `intake` step settled the parent spec. */
sealed interface PlanIntakeSettlement {
  /** An operator spec with a readable acceptance list was left as it is. */
  data class Kept(val specPath: Path) : PlanIntakeSettlement

  /** The runtime wrote the parent spec from free text, or quoted an operator spec that lacked acceptance criteria. */
  data class Wrote(val specPath: Path) : PlanIntakeSettlement

  /** An earlier attempt already finished intake and recorded the plan seed, so nothing was written. */
  data class AlreadySettled(val specPath: Path) : PlanIntakeSettlement

  /** Intake cannot produce the spec the workflow governs. */
  data class Refused(val reason: String) : PlanIntakeSettlement
}

@Inject
class FeatureTaskRuntimePlanIntake(
  private val intakePreparation: GoalIntakePreparation,
  private val seedRecorder: FeatureTaskRuntimePlanSeedRecorder,
  private val fileStore: DecompositionManifestStore,
  private val repositories: RepositoryEnclosingRootPort,
) {
  fun settle(
    workflowId: String,
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    governedSpecPath: String,
  ): PlanIntakeSettlement {
    val governed = Path.of(governedSpecPath)
    val recorded = seedRecorder.read(workflowId)
    if (recorded != null && intakeFinished(recorded, governed)) {
      refreshRuntimeSpecHash(workflowId, recorded, governed)
      return PlanIntakeSettlement.AlreadySettled(governed)
    }
    val resolution =
      try {
        intakePreparation.resolvePlanSpec(issueKey, intake, repoRoot)
      } catch (error: InvalidFeatureSpecPreparationRequestError) {
        return PlanIntakeSettlement.Refused("${error.reason} $RESUME_HINT")
      }
    val resolvedGoverned = governedPathOrNull(workflowId, repoRoot, resolution.specPath)
    if (resolvedGoverned == null || resolvedGoverned != governedPathOrNull(workflowId, repoRoot, governed)) {
      return PlanIntakeSettlement.Refused(
        "Intake resolves to ${resolution.specPath}, but the workflow governs $governedSpecPath; " +
          "resume with the original intake. $RESUME_HINT",
      )
    }
    val intakeSha256 = intake?.let(::sha256HexUtf8)
    if (resolution.specOrigin == FeatureTaskRuntimePlanSpecOrigin.SEEDED) {
      seedRecorder.record(
        workflowId,
        FeatureTaskRuntimePlanSeed(intakeSha256, resolution.specOrigin, specHashOrEmpty(resolution.specPath)),
      )
    }
    val spec = intakePreparation.intakePlanSpec(issueKey, intake, repoRoot, resolution)
    seedRecorder.record(
      workflowId,
      FeatureTaskRuntimePlanSeed(intakeSha256, spec.specOrigin, sha256HexUtf8(fileStore.readText(spec.specPath))),
    )
    return when (spec.specOrigin) {
      FeatureTaskRuntimePlanSpecOrigin.OPERATOR -> PlanIntakeSettlement.Kept(spec.specPath)
      FeatureTaskRuntimePlanSpecOrigin.SEEDED -> PlanIntakeSettlement.Wrote(spec.specPath)
    }
  }

  private fun intakeFinished(
    seed: FeatureTaskRuntimePlanSeed,
    governed: Path,
  ): Boolean =
    seed.specOrigin == FeatureTaskRuntimePlanSpecOrigin.OPERATOR || intakePreparation.keepsOperatorSpec(governed)

  private fun refreshRuntimeSpecHash(
    workflowId: String,
    seed: FeatureTaskRuntimePlanSeed,
    governed: Path,
  ) {
    val current = specHashOrEmpty(governed)
    if (seed.specOrigin == FeatureTaskRuntimePlanSpecOrigin.SEEDED && seed.specSha256 != current) {
      seedRecorder.record(workflowId, seed.copy(specSha256 = current))
    }
  }

  private fun specHashOrEmpty(specPath: Path): String =
    sha256HexUtf8(if (fileStore.isRegularFile(specPath)) fileStore.readText(specPath) else "")

  private fun governedPathOrNull(
    workflowId: String,
    repoRoot: Path,
    specPath: Path,
  ): String? =
    try {
      val anchored =
        if (specPath.startsWith(repoRoot)) {
          repositories.canonicalPath(repoRoot).resolve(repoRoot.relativize(specPath))
        } else {
          specPath
        }
      repositories.governedFeatureTaskSpecPath(workflowId, repoRoot, anchored)
    } catch (_: SkillBillRuntimeException) {
      null
    }

  private companion object {
    const val RESUME_HINT = "Resume with `skill-bill phase plan <KEY> <requirements>`."
  }
}
