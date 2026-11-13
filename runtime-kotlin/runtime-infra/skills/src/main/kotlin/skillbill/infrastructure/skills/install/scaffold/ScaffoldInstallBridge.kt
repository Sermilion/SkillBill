package skillbill.infrastructure.skills.install.scaffold

import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.skills.install.plan.InstallContext
import skillbill.infrastructure.skills.install.plan.detectAgents
import skillbill.infrastructure.skills.install.plan.installSkill
import skillbill.infrastructure.skills.install.plan.resolveInstallEnvironment
import skillbill.infrastructure.skills.install.plan.resolveInstallHome
import skillbill.infrastructure.skills.scaffold.authoring.parseInternalForFrontmatter
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackDiscoveryContext
import skillbill.infrastructure.skills.scaffold.platformpack.loader.discoverPlatformPackManifests
import skillbill.infrastructure.skills.scaffold.runtime.service.ADD_ON_INSTALL_NOTE
import skillbill.infrastructure.skills.scaffold.runtime.service.PLATFORM_PACK_INSTALL_NOTE
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldInstallOutcome
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldPlan
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldRuntimeContext
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldTransaction
import skillbill.infrastructure.skills.scaffold.runtime.service.noAgentsNote
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTransaction
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.policy.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.SKILL_KIND_AGENT_ADDON
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_PACK
import java.nio.file.Files
import java.nio.file.Path

internal fun performScaffoldInstall(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
  runtime: ScaffoldRuntimeContext = ScaffoldRuntimeContext(resolveInstallHome(null, JdkHostPlatformPort)),
): ScaffoldInstallOutcome {
  if (plan.externalPackRegistrationMode == "register") {
    return ScaffoldInstallOutcome(
      targets = emptyList(),
      notes =
        listOf(
          "Registered existing external platform pack without rewriting pack files.",
        ),
    )
  }
  val hostPlatform = JdkHostPlatformPort
  val home = runtime.userHome
  val environment = resolveInstallEnvironment(runtime.environment, hostPlatform)
  val agents = detectAgents(home, environment)
  var installTx = InstallTransaction()
  val internalPlatformSkills = internalPlatformInstallSkills(plan)
  val skillsRoot = repoRoot.resolve("skills")
  val installPaths =
    when (plan.kind) {
      SKILL_KIND_ADD_ON -> emptyList()
      SKILL_KIND_AGENT_ADDON -> plan.agentAddonConsumers.map { consumer -> skillsRoot.resolve(consumer) }
      SKILL_KIND_PLATFORM_PACK -> platformPackInstallPaths(plan, repoRoot, internalPlatformSkills)
      else -> listOf(plan.skillPath)
    }
  val packsRoot = repoRoot.resolve("platform-packs")
  val manifests =
    runtime.catalogLoader?.loadEffectiveManifests(
      PlatformPackDiscoveryContext(
        repoRoot = repoRoot,
        userHome = home,
        environment = environment,
        catalogLoader = runtime.catalogLoader,
      ),
    ) ?: if (Files.isDirectory(packsRoot)) discoverPlatformPackManifests(packsRoot) else emptyList()
  val context =
    InstallContext(
      repoRoot = repoRoot,
      home = home,
      manifests = manifests,
      selectedPackSkills = internalPlatformSkills,
      selectedPlatformSlugs = setOf(plan.platform),
      environment = environment,
      catalogLoader = runtime.catalogLoader,
    )
  val targets = mutableListOf<Path>()
  val issues = mutableListOf<InstallApplyIssue>()
  installPaths.forEach { installPath ->
    val outcome = installSkill(installPath, agents, transaction = installTx, context = context)
    installTx = outcome.transaction ?: installTx
    targets += outcome.linkPaths
    issues += outcome.issues
    txn.installTargets += outcome.linkPaths
    txn.replacedInstallTargets += outcome.replacedSymlinks
    txn.retiredInstallTargets += outcome.retiredSymlinks
  }
  return ScaffoldInstallOutcome(targets, scaffoldInstallNotes(plan.kind, agents.isEmpty()), issues)
}

private fun scaffoldInstallNotes(
  kind: String,
  noAgents: Boolean,
): List<String> =
  when {
    kind == SKILL_KIND_ADD_ON -> listOf(ADD_ON_INSTALL_NOTE)
    kind == SKILL_KIND_AGENT_ADDON -> listOf("Agent add-on consumers rendered and installed atomically.")
    noAgents -> listOf(noAgentsNote())
    kind == SKILL_KIND_PLATFORM_PACK -> listOf(PLATFORM_PACK_INSTALL_NOTE)
    else -> emptyList()
  }

internal fun internalPlatformInstallSkills(plan: ScaffoldPlan): List<InstallPlanSkill> {
  if (plan.kind != SKILL_KIND_PLATFORM_PACK) {
    return emptyList()
  }
  return plan.installPaths.mapNotNull { installPath ->
    val internalFor = parseInternalForFrontmatter(installPath.resolve("content.md")) ?: return@mapNotNull null
    InstallPlanSkill(
      name = installPath.fileName.toString(),
      sourceDir = installPath.toAbsolutePath().normalize().toFileLocation(),
      kind = InstallPlanSkillKind.PLATFORM_PACK,
      platformSlug = plan.platform,
      internalFor = internalFor,
    )
  }
}

internal fun platformPackInstallPaths(
  plan: ScaffoldPlan,
  repoRoot: Path,
  internalPlatformSkills: List<InstallPlanSkill>,
): List<Path> {
  val internalSkillDirs = internalPlatformSkills.map { skill -> skill.sourceDir.toPath() }.toSet()
  val listedPaths =
    plan.installPaths.filterNot { installPath ->
      installPath.toAbsolutePath().normalize() in internalSkillDirs
    }
  val parentPaths =
    internalPlatformSkills
      .mapNotNull(InstallPlanSkill::internalFor)
      .distinct()
      .map { parent -> repoRoot.resolve("skills").resolve(parent) }
  return (listedPaths + parentPaths).distinctBy { path -> path.toAbsolutePath().normalize() }
}
