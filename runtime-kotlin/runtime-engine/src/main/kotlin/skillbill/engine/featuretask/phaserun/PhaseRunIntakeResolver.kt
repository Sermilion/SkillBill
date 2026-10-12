package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.error.featuretask.PhaseIntakeRequiredError
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.skeleton.PhaseIntakeRequirement
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path

@Inject
class PhaseRunIntakeResolver(
  private val specPathResolver: FeatureSpecPathResolverPort,
  private val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
) {
  internal fun resolve(
    definition: SkeletonDefinition,
    request: PhaseRunRequest,
    currentBranch: String?,
  ): PhaseRunIntake {
    val intake = request.intake?.trim().orEmpty()
    val tokens = IntakeIssueKeys.tokens(intake)
    val specPath = IntakeIssueKeys.specPathToken(tokens)?.let(request.repoRoot::resolve)
    val issueKey = IntakeIssueKeys.ofIntake(intake)
    val reviewMode = request.codeReviewMode ?: CodeReviewExecutionMode.DEFAULT
    return when (definition.intake) {
      PhaseIntakeRequirement.OPTIONAL ->
        PhaseRunIntake(
          issueKey = issueKey ?: currentBranch?.let(IntakeIssueKeys::ofBranch) ?: request.definitionId,
          runInvariants =
            FeatureTaskRuntimeRunInvariants(
              specReference = request.intake?.takeIf(String::isNotBlank) ?: "$PHASE_SPEC_PREFIX${request.definitionId}",
              acceptanceCriteria = listOf(PHASE_ACCEPTANCE_CRITERION),
              mandatesAndOverrides = emptyList(),
              codeReviewMode = reviewMode,
            ),
        )
      PhaseIntakeRequirement.ISSUE_KEY -> {
        val key =
          issueKey ?: throw PhaseIntakeRequiredError(
            definition.id,
            "the intake must name an issue key such as SKILL-123, an issue URL, or a spec path under " +
              ".feature-specs/<KEY>-<name>/.",
          )
        val invariants =
          specInvariants(key, specPath, bareKey = tokens.size == 1, repoRoot = request.repoRoot)
            ?: FeatureTaskRuntimeRunInvariants(
              specReference = intake,
              acceptanceCriteria = listOf(intake),
              mandatesAndOverrides = emptyList(),
            )
        PhaseRunIntake(key, invariants.copy(codeReviewMode = reviewMode))
      }
    }
  }

  private fun specInvariants(
    issueKey: String?,
    explicitSpecPath: Path?,
    bareKey: Boolean,
    repoRoot: Path,
  ): FeatureTaskRuntimeRunInvariants? {
    val specPath =
      explicitSpecPath
        ?: issueKey?.takeIf { bareKey }?.let { key ->
          specPathResolver
            .resolve(FeatureSpecPathResolveInput(issueKey = key, explicitSpecPath = null, repoRoot = repoRoot))
            .specPath
            ?.let(Path::of)
        }
        ?: return null
    return when (val read = invariantsSource.read(specPath)) {
      is FeatureTaskRuntimeRunInvariantsRead.Read -> read.invariants
      is FeatureTaskRuntimeRunInvariantsRead.Rejected -> null
    }
  }

  private companion object {
    const val PHASE_SPEC_PREFIX = "phase:"
    const val PHASE_ACCEPTANCE_CRITERION = "The phase leaves no unresolved Blocker or Major finding."
  }
}

internal data class PhaseRunIntake(
  val issueKey: String,
  val runInvariants: FeatureTaskRuntimeRunInvariants,
)
