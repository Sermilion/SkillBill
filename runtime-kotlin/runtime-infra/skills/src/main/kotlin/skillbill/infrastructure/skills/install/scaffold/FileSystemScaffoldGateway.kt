package skillbill.infrastructure.skills.install.scaffold
import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonCatalogueEntry
import skillbill.error.shellcontent.missingAgentAddonDeclaration
import skillbill.infrastructure.skills.agentaddon.AgentAddonDeliveryResolver
import skillbill.infrastructure.skills.install.nativeagent.installNativeAgentCompositionContext
import skillbill.infrastructure.skills.scaffold.authoring.AuthoringOperations
import skillbill.infrastructure.skills.scaffold.authoring.AuthoringRenderResult
import skillbill.infrastructure.skills.scaffold.authoring.recommendedCommands
import skillbill.infrastructure.skills.scaffold.authoring.renderAuthoringTarget
import skillbill.infrastructure.skills.scaffold.catalog.ScaffoldCatalog
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackDiscoveryContext
import skillbill.model.EnvironmentContext
import skillbill.model.toPath
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.catalog.model.ScaffoldExplainResult
import skillbill.ports.scaffold.catalog.model.ScaffoldExplainSkill
import skillbill.ports.scaffold.catalog.model.ScaffoldListResult
import skillbill.ports.scaffold.catalog.model.ScaffoldShowResult
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.ports.scaffold.model.ScaffoldCompletionStatus
import skillbill.ports.scaffold.model.ScaffoldRenderBlock
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import skillbill.ports.scaffold.model.ScaffoldSkillStatus
import skillbill.ports.scaffold.repo.model.ScaffoldUpgradeResult
import skillbill.ports.scaffold.repo.model.ScaffoldValidateResult
import skillbill.ports.scaffold.repo.model.ScaffoldValidationMode
import skillbill.ports.scaffold.repo.model.ScaffoldValidationStatus
import skillbill.ports.scaffold.source.model.ScaffoldEditWithBodyFileResult
import skillbill.ports.scaffold.source.model.ScaffoldFillResult
import skillbill.ports.scaffold.source.model.ScaffoldSaveExactContentResult
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import java.nio.file.Files
import java.nio.file.Path
import skillbill.infrastructure.skills.agentaddon.inspectAgentAddons as inspectFsAgentAddons

private const val CONTENT_PREVIEW_MAX_CHARS = 500

@Inject
class FileSystemScaffoldGateway(
  private val scaffoldOrchestrator: FileSystemScaffoldOrchestrator,
  private val environmentContext: EnvironmentContext,
  private val catalogLoader: PlatformPackCatalogLoader,
) : ScaffoldGateway {
  override fun list(
    repoRoot: Path,
    skillNames: List<String>,
  ): ScaffoldListResult {
    val addonNames = skillNames.filter { it.startsWith(AGENT_ADDON_PREFIX) }
    val governedNames = skillNames.filterNot { it.startsWith(AGENT_ADDON_PREFIX) }
    val result = AuthoringOperations.list(repoRoot, governedNames, discovery(repoRoot))
    val addonCatalogue =
      if (skillNames.isEmpty()) {
        inspectFsAgentAddons(repoRoot).entries
      } else {
        AgentAddonDeliveryResolver().catalogue(repoRoot)
      }
    val addons =
      addonCatalogue
        .filter { skillNames.isEmpty() || it.identity in addonNames }
        .map { it.toSkillStatus(repoRoot, "none") }
    val governedSkills =
      if (skillNames.isEmpty()) {
        result.skills
      } else {
        result.skills.filter { it.skillName in governedNames }
      }
    val skills = governedSkills + addons
    return ScaffoldListResult(
      repoRoot = result.repoRoot,
      skillCount = skills.size,
      skills = skills,
    )
  }

  override fun show(
    repoRoot: Path,
    skillName: String,
    contentMode: String,
  ): ScaffoldShowResult =
    ScaffoldShowResult(
      status =
        if (skillName.startsWith(AGENT_ADDON_PREFIX)) {
          requireAgentAddonEntry(repoRoot, skillName).toSkillStatus(repoRoot, contentMode)
        } else {
          AuthoringOperations.show(repoRoot, skillName, contentMode, discovery(repoRoot))
        },
    )

  override fun explain(
    repoRoot: Path,
    skillName: String?,
  ): ScaffoldExplainResult {
    if (skillName?.startsWith(AGENT_ADDON_PREFIX) == true) {
      val addon = requireAgentAddonEntry(repoRoot, skillName)
      return ScaffoldExplainResult(
        explanation = "Agent add-ons are governed extension sources delivered to declared skill consumers.",
        editableSurface = listOf("agent-addon.yaml", "content.md"),
        generatedSurface = listOf("agent-addon-<slug>.md install pointers"),
        governedSidecars = emptyList(),
        normalWorkflow = listOf("skill-bill show $skillName", "skill-bill validate", "skill-bill render skill-bill"),
        notes =
          listOf(
            "Supported agents: ${addon.agentIds.joinToString()}",
            "Consumers: ${addon.consumers.joinToString()}",
          ),
        skill =
          ScaffoldExplainSkill(
            skillName = addon.identity,
            contentFile = addon.contentPath.toString(),
            renderCommand = "skill-bill render skill-bill --repo-root ${repoRoot.toAbsolutePath().normalize()}",
            recommendedCommands = listOf("skill-bill validate", "skill-bill render skill-bill"),
          ),
      )
    }
    val result = AuthoringOperations.explain(repoRoot, skillName, discovery(repoRoot))
    return ScaffoldExplainResult(
      explanation = result.explanation,
      editableSurface = result.editableSurface,
      generatedSurface = result.generatedSurface,
      governedSidecars = result.governedSidecars,
      normalWorkflow = result.normalWorkflow,
      notes = result.notes,
      skill =
        result.skill?.let { skill ->
          ScaffoldExplainSkill(
            skillName = skill.skillName,
            contentFile = skill.contentFile,
            renderCommand = skill.renderCommand,
            recommendedCommands = skill.recommendedCommands,
          )
        },
    )
  }

  override fun validate(
    repoRoot: Path,
    skillNames: List<String>,
  ): ScaffoldValidateResult {
    val result = AuthoringOperations.validate(repoRoot, skillNames, discovery(repoRoot))
    return ScaffoldValidateResult(
      repoRoot = result.repoRoot,
      mode =
        requireNotNull(ScaffoldValidationMode.fromWire(result.mode)) {
          "Unknown scaffold validation mode '${result.mode}'."
        },
      status =
        requireNotNull(ScaffoldValidationStatus.fromWire(result.status)) {
          "Unknown scaffold validation status '${result.status}'."
        },
      issues = result.issues,
      skillNames = result.skillNames,
      suggestedCommands = result.suggestedCommands,
    )
  }

  override fun upgrade(
    repoRoot: Path,
    skillNames: List<String>,
    validate: Boolean,
  ): ScaffoldUpgradeResult {
    val result =
      AuthoringOperations.upgrade(
        repoRoot,
        skillNames,
        validate,
        installNativeAgentCompositionContext(),
        discovery(repoRoot),
      )
    return ScaffoldUpgradeResult(
      repoRoot = result.repoRoot,
      regeneratedCount = result.regeneratedCount,
      regeneratedFiles = result.regeneratedFiles,
      contentMdTouched = result.contentMdTouched,
      shellCeremonyTouched = result.shellCeremonyTouched,
      validatorRan = result.validatorRan,
    )
  }

  override fun fill(
    repoRoot: Path,
    skillName: String,
    body: String,
    sectionName: String?,
  ): ScaffoldFillResult {
    val result = AuthoringOperations.fill(repoRoot, skillName, body, sectionName, discovery(repoRoot))
    return ScaffoldFillResult(
      status = result.mutation.status,
      wrapperRegenerated = result.mutation.wrapperRegenerated,
      updatedSection = result.updatedSection,
      validatorRan = result.validatorRan,
    )
  }

  override fun saveExactContent(
    repoRoot: Path,
    skillName: String,
    content: String,
  ): ScaffoldSaveExactContentResult {
    val result = AuthoringOperations.saveExactContent(repoRoot, skillName, content, discovery(repoRoot))
    return ScaffoldSaveExactContentResult(
      status = result.mutation.status,
      wrapperRegenerated = result.mutation.wrapperRegenerated,
      validatorRan = result.validatorRan,
    )
  }

  override fun editWithBodyFile(
    repoRoot: Path,
    skillName: String,
    body: String,
    sectionName: String?,
  ): ScaffoldEditWithBodyFileResult {
    val result =
      AuthoringOperations.editWithBodyFile(
        repoRoot,
        skillName,
        body,
        sectionName,
        discovery(repoRoot),
      )
    return ScaffoldEditWithBodyFileResult(
      usedEditor = result.usedEditor,
      guidedSections = result.guidedSections,
      updatedSection = result.updatedSection,
      validatorRan = result.validatorRan,
      status = result.mutation.status,
      wrapperRegenerated = result.mutation.wrapperRegenerated,
    )
  }

  override fun scaffold(
    request: ScaffoldCommandRequest,
    dryRun: Boolean,
  ) = scaffoldOrchestrator.scaffold(request, dryRun)

  override fun render(
    repoRoot: Path,
    skillName: String,
  ): ScaffoldRenderResult = renderAuthoringTarget(repoRoot, skillName, discovery(repoRoot)).toPortRenderResult()

  private fun discovery(repoRoot: Path): PlatformPackDiscoveryContext =
    PlatformPackDiscoveryContext(
      repoRoot = repoRoot.toAbsolutePath().normalize(),
      userHome = environmentContext.userHome,
      environment = environmentContext.environment,
      catalogLoader = catalogLoader,
    )
}

private const val AGENT_ADDON_PREFIX = "agent-addon:"

private fun requireAgentAddonEntry(
  repoRoot: Path,
  identity: String,
): AgentAddonCatalogueEntry =
  AgentAddonDeliveryResolver().catalogue(repoRoot).firstOrNull { it.identity == identity }
    ?: throw missingAgentAddonDeclaration(
      identity.removePrefix(AGENT_ADDON_PREFIX),
      repoRoot.resolve("agent-addons").toString(),
    )

private fun AgentAddonCatalogueEntry.toSkillStatus(
  repoRoot: Path,
  contentMode: String,
): ScaffoldSkillStatus {
  val contentText = Files.readString(contentPath.toPath())
  return ScaffoldSkillStatus(
    skillName = identity,
    packageName = "agent-addons",
    platform = "",
    family = "agent-addon",
    area = "",
    contentFile = contentPath.toString(),
    renderCommand = "skill-bill render skill-bill --repo-root ${repoRoot.toAbsolutePath().normalize()}",
    completionStatus = ScaffoldCompletionStatus.AUTHORED,
    sectionCount = 0,
    sections = emptyList(),
    recommendedCommands = listOf("skill-bill validate", "skill-bill render skill-bill"),
    contentPreview = if (contentMode == "preview") contentText.take(CONTENT_PREVIEW_MAX_CHARS) else null,
    content = if (contentMode == "full") contentText else null,
    category = "agent-addon",
    slug = slug,
    description = description,
    supportedAgents = agentIds,
    consumers = consumers,
    manifestFile = manifestPath.toString(),
  )
}

@Inject
class FileSystemScaffoldCatalogGateway : ScaffoldCatalogGateway {
  override fun approvedCodeReviewAreas() = ScaffoldCatalog.approvedCodeReviewAreas

  override fun preShellFamilies() = ScaffoldCatalog.preShellFamilies

  override fun shelledFamilies() = ScaffoldCatalog.shelledFamilies

  override fun platformPackPresets() = ScaffoldCatalog.platformPackPresets

  override fun scaffoldPayloadVersion() = ScaffoldCatalog.scaffoldPayloadVersion

  override fun discoverPilotedPlatformPacks(packsRoot: Path) =
    ScaffoldCatalog.discoverPilotedPlatformPacks(packsRoot).map { pack ->
      PilotedPlatformPackProjection(slug = pack.slug, displayName = pack.displayName)
    }

  override fun discoverPlatformManifests(packsRoot: Path) = ScaffoldCatalog.discoverPilotedPlatformPacks(packsRoot)

  override fun discoverBaselineReviewCatalog(packsRoot: Path) = ScaffoldCatalog.discoverBaselineReviewCatalog(packsRoot)
}

private fun AuthoringRenderResult.toPortRenderResult(): ScaffoldRenderResult =
  ScaffoldRenderResult(
    repoRoot = repoRoot,
    skillName = skillName,
    blocks = blocks.map { block -> ScaffoldRenderBlock(header = block.header, content = block.content) },
  )
