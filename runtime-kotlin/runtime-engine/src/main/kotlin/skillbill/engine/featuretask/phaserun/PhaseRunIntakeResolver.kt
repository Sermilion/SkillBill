package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.error.featuretask.PhaseIntakeRequiredError
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
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
    val tokens = intake.split(WHITESPACE).filter(String::isNotBlank)
    val specPath = tokens.firstOrNull { it.endsWith(SPEC_EXTENSION) }?.let(request.repoRoot::resolve)
    val issueKey =
      tokens.firstOrNull(ISSUE_KEY::matches)?.uppercase()
        ?: tokens.firstNotNullOfOrNull(::issueKeyOfUrl)
        ?: specPath?.let(::issueKeyOfSpecPath)
    val reviewMode = request.codeReviewMode ?: CodeReviewExecutionMode.DEFAULT
    return when (definition.intake) {
      PhaseIntakeRequirement.OPTIONAL ->
        PhaseRunIntake(
          issueKey = issueKey ?: currentBranch?.let(::issueKeyOfBranch) ?: request.definitionId,
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
    return try {
      invariantsSource.read(specPath)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun issueKeyOfSpecPath(specPath: Path): String? =
    specPath.normalize().toList().map(Path::toString).zipWithNext()
      .firstOrNull { (parent, _) -> parent == FEATURE_SPECS_DIRECTORY }
      ?.let { (_, directory) -> issueAndFeature(directory).first.takeIf(ISSUE_KEY::matches) }

  private fun issueKeyOfUrl(token: String): String? =
    token.takeIf { it.contains(URL_SCHEME_SEPARATOR) }
      ?.substringAfter(URL_SCHEME_SEPARATOR)
      ?.substringBefore('?')
      ?.substringBefore('#')
      ?.split('/')
      ?.drop(1)
      ?.firstOrNull(ISSUE_KEY::matches)
      ?.uppercase()

  private fun issueKeyOfBranch(branch: String): String? {
    val leaf = branch.substringAfterLast('/')
    return ISSUE_KEY.matchEntire(leaf)?.value?.uppercase()
      ?: issueAndFeature(leaf).first.takeIf(ISSUE_KEY::matches)
  }

  private companion object {
    val WHITESPACE = Regex("\\s+")
    val ISSUE_KEY = Regex("(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")
    const val SPEC_EXTENSION = ".md"
    const val URL_SCHEME_SEPARATOR = "://"
    const val FEATURE_SPECS_DIRECTORY = ".feature-specs"
    const val PHASE_SPEC_PREFIX = "phase:"
    const val PHASE_ACCEPTANCE_CRITERION = "The phase leaves no unresolved Blocker or Major finding."
  }
}

internal data class PhaseRunIntake(
  val issueKey: String,
  val runInvariants: FeatureTaskRuntimeRunInvariants,
)
