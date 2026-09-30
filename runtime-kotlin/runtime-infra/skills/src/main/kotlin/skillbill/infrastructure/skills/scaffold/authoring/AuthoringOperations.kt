package skillbill.infrastructure.skills.scaffold.authoring

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionContext
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentRegenerationRequest
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackDiscoveryContext
import skillbill.ports.scaffold.model.ScaffoldSkillStatus
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.GovernedAddonSelection
import java.nio.file.Files
import java.nio.file.Path

internal const val AUTHORING_EXPLANATION =
  "Governed skills split author-owned behavior into content.md and generated runtime wiring into " +
    "render/install output. " +
    "Use CLI commands to keep that boundary intact."

data class AuthoringTarget(
  val skillName: String,
  val packageName: String,
  val platform: String,
  val displayName: String,
  val family: String,
  val area: String,
  val skillFile: Path,
  val contentFile: Path,
  val codeReviewComposition: CodeReviewComposition? = null,
  val addonUsage: List<GovernedAddonSelection> = emptyList(),
  val internalFor: String? = null,
)

object AuthoringOperations {
  internal fun list(
    repoRoot: Path,
    skillNames: List<String>,
    externalDiscovery: PlatformPackDiscoveryContext? = null,
  ): AuthoringListResult {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val targets = selectedTargets(resolvedRoot, skillNames, externalDiscovery)
    return AuthoringListResult(
      repoRoot = resolvedRoot.toString(),
      skillCount = targets.size,
      skills = targets.map { target -> skillStatus(resolvedRoot, target, "none") },
    )
  }

  internal fun show(
    repoRoot: Path,
    skillName: String,
    contentMode: String,
    externalDiscovery: PlatformPackDiscoveryContext? = null,
  ): ScaffoldSkillStatus {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val target = resolveTarget(resolvedRoot, skillName, externalDiscovery)
    return skillStatus(resolvedRoot, target, contentMode)
  }

  internal fun explain(
    repoRoot: Path,
    skillName: String?,
    externalDiscovery: PlatformPackDiscoveryContext? = null,
  ): AuthoringExplain {
    val skill =
      skillName?.let { name ->
        val resolvedRoot = repoRoot.toAbsolutePath().normalize()
        val target = resolveTarget(resolvedRoot, name, externalDiscovery)
        AuthoringExplainSkill(
          skillName = target.skillName,
          contentFile = target.contentFile.toString(),
          renderCommand = "skill-bill render ${target.skillName} --repo-root $resolvedRoot",
          recommendedCommands =
            recommendedCommands(
              resolvedRoot,
              target,
              completionStatus = contentCompletionStatus(Files.readString(target.contentFile)),
              issues = emptyList(),
            ),
        )
      }
    return AuthoringExplain(
      explanation = AUTHORING_EXPLANATION,
      editableSurface = listOf("content.md"),
      generatedSurface = listOf("SKILL.md", "platform.yaml pointer files"),
      governedSidecars = emptyList(),
      normalWorkflow =
        listOf(
          "skill-bill new --payload <file>",
          "skill-bill fill <skill-name>",
          "skill-bill validate --skill-name <skill-name>",
          "skill-bill render <skill-name>",
        ),
      notes =
        listOf(
          "Author behavior changes in content.md.",
          "Preview generated wrappers with render instead of hand-editing SKILL.md.",
          "Use show to inspect completion and next commands.",
        ),
      skill = skill,
    )
  }

  internal fun validate(
    repoRoot: Path,
    skillNames: List<String>,
    externalDiscovery: PlatformPackDiscoveryContext? = null,
  ): AuthoringValidateResult {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    if (skillNames.isEmpty()) {
      val issues =
        discoverTargets(resolvedRoot, externalDiscovery = externalDiscovery).values.flatMap { target ->
          validateTarget(target, resolvedRoot)
        }
      return AuthoringValidateResult(
        repoRoot = resolvedRoot.toString(),
        mode = "repo",
        skillNames = null,
        status = if (issues.isEmpty()) "pass" else "fail",
        issues = issues,
        suggestedCommands = null,
      )
    }
    val issues =
      selectedTargets(resolvedRoot, skillNames, externalDiscovery).flatMap { target ->
        validateTarget(target, resolvedRoot)
      }
    val suggestedCommands =
      skillNames.flatMap { skillName ->
        val target = resolveTarget(resolvedRoot, skillName, externalDiscovery)
        recommendedCommands(
          resolvedRoot,
          target,
          completionStatus = contentCompletionStatus(Files.readString(target.contentFile)),
          issues = issues,
        )
      }.distinct()
    return AuthoringValidateResult(
      repoRoot = resolvedRoot.toString(),
      mode = "selected",
      skillNames = skillNames,
      status = if (issues.isEmpty()) "pass" else "fail",
      issues = issues,
      suggestedCommands = suggestedCommands,
    )
  }

  internal fun upgrade(
    repoRoot: Path,
    skillNames: List<String>,
    validate: Boolean,
    nativeAgentCompositionContext: NativeAgentCompositionContext,
    externalDiscovery: PlatformPackDiscoveryContext? = null,
  ): AuthoringUpgradeResult {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val targets = selectedTargets(resolvedRoot, skillNames, externalDiscovery)
    val originalBytes = mutableMapOf<Path, ByteArray>()
    val createdPaths = mutableListOf<Path>()
    val regenerated = mutableListOf<Path>()
    return runWithUpgradeRollback(originalBytes, createdPaths) {
      targets.forEach { target ->
        renderAuthoringTarget(resolvedRoot, target)
      }
      val nativeRegeneration =
        NativeAgentOperations.regenerate(
          NativeAgentRegenerationRequest(
            repoRoot = resolvedRoot,
            compositionContext = nativeAgentCompositionContext,
            skillNames = skillNames,
            originalBytes = originalBytes,
            createdPaths = createdPaths,
          ),
        )
      regenerated += nativeRegeneration.regeneratedFiles
      if (validate) {
        val issues = targets.flatMap { target -> validateTarget(target, resolvedRoot) }
        if (issues.isNotEmpty()) {
          throw SkillBillRuntimeException("Validator failed after upgrade:\n${issues.joinToString("\n")}")
        }
      }
      AuthoringUpgradeResult(
        repoRoot = resolvedRoot.toString(),
        regeneratedCount = regenerated.size,
        regeneratedFiles = regenerated.map { path -> path.toString() },
        contentMdTouched = false,
        shellCeremonyTouched = false,
        validatorRan = validate,
      )
    }
  }

  internal fun fill(
    repoRoot: Path,
    skillName: String,
    body: String,
    sectionName: String?,
    externalDiscovery: PlatformPackDiscoveryContext? = null,
  ): AuthoringFillResult {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val target = resolveTarget(resolvedRoot, skillName, externalDiscovery)
    val replacement =
      if (sectionName == null) {
        coerceFullContentText(target, body)
      } else {
        replaceSectionBody(Files.readString(target.contentFile), sectionName, body)
      }
    val mutation = mutateContent(resolvedRoot, target, replacement)
    return AuthoringFillResult(
      mutation = mutation,
      updatedSection = sectionName?.let(::sectionHeadingLabel),
      validatorRan = true,
    )
  }

  internal fun saveExactContent(
    repoRoot: Path,
    skillName: String,
    content: String,
    externalDiscovery: PlatformPackDiscoveryContext? = null,
  ): AuthoringSaveExactContentResult {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val target = resolveTarget(resolvedRoot, skillName, externalDiscovery)
    val mutation = mutateContent(resolvedRoot, target, content)
    return AuthoringSaveExactContentResult(
      mutation = mutation,
      validatorRan = true,
    )
  }

  internal fun editWithBodyFile(
    repoRoot: Path,
    skillName: String,
    body: String,
    sectionName: String?,
    externalDiscovery: PlatformPackDiscoveryContext? = null,
  ): AuthoringEditWithBodyFileResult {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val target = resolveTarget(resolvedRoot, skillName, externalDiscovery)
    val replacement =
      if (sectionName == null) {
        coerceFullContentText(target, body)
      } else {
        replaceSectionBody(Files.readString(target.contentFile), sectionName, body)
      }
    val mutation = mutateContent(resolvedRoot, target, replacement)
    return AuthoringEditWithBodyFileResult(
      usedEditor = false,
      guidedSections = emptyList(),
      updatedSection = sectionName?.let(::sectionHeadingLabel),
      validatorRan = true,
      mutation = mutation,
    )
  }
}
