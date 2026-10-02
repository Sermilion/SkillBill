package skillbill.infrastructure.skills.install.plan

import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.packRootsBySlug
import skillbill.install.model.InstallAgentDefaultTarget
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallPlatformSkillMaterializationRequest
import skillbill.install.model.InstallPolicyInput
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.install.model.SupportedAgent
import skillbill.install.model.buildInstallPlanWireMap
import skillbill.install.policy.InstallPlanPolicy
import skillbill.model.toPath
import skillbill.ports.install.InstallPlanWireValidator
import skillbill.ports.install.plan.model.InstallPlanningFacts
import skillbill.ports.repository.toFileLocation
import skillbill.review.plan.ReviewFallbackResolver
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal fun buildInstallPlan(
  request: InstallPlanRequest,
  wireValidator: InstallPlanWireValidator,
): InstallPlan {
  requireSupportedAgentContract()
  val platformManifests = discoverPlatformManifests(request)
  val policyInput = buildInstallPolicyInput(request, platformManifests, enforceContractVersion = true)
  val draft = InstallPlanPolicy.buildPlanDraft(policyInput)
  validateInstallPlanInternalSkills(draft.skills)
  val staging = buildInstallStagingIntent(request, draft.skills, platformManifests)
  val plan = draft.toInstallPlan(staging)

  wireValidator.validate(buildInstallPlanWireMap(plan))
  return plan
}

private fun requireSupportedAgentContract() {
  require(SUPPORTED_AGENTS.map(SupportedAgent::wireValue) == SupportedAgent.supportedIds) {
    "Install plan supported-agent contract drifted. Domain=${SupportedAgent.supportedIds}; core=$SUPPORTED_AGENTS."
  }
}

private fun buildInstallPolicyInput(
  request: InstallPlanRequest,
  platformManifests: List<PlatformManifest>,
  enforceContractVersion: Boolean,
  enforceGovernedReviewStructure: Boolean = true,
): InstallPolicyInput {
  val baseSkills = discoverBaseSkills(request.targetPaths.skillsRoot.toPath())
  val resolvedReviewFallback =
    baseSkills
      .takeIf { skills -> skills.any { it.name == PACK_SIDECAR_PARENT_SKILL } }
      ?.let { ReviewFallbackResolver.resolveOptional(platformManifests) }
  val discoveredPlatformPacks = platformManifests.toDiscoverySnapshots()
  val materializationPlan =
    InstallPlanPolicy.planPlatformSkillMaterialization(
      InstallPlatformSkillMaterializationRequest(
        installRequest = request,
        platformPacks = discoveredPlatformPacks,
      ),
    )
  val selectedPlatformSlugs =
    (
      materializationPlan.selectedPlatformSlugs +
        listOfNotNull(resolvedReviewFallback?.slug)
    ).toSet()
  return InstallPolicyInput(
    request = request,
    baseSkills = baseSkills,
    resolvedReviewFallbackSlug = resolvedReviewFallback?.slug,
    platformPacks =
      platformManifests.map { manifest ->
        InstallPlatformPackSnapshot(
          slug = manifest.slug,
          packRoot = manifest.packRoot,
          skills =
            if (manifest.slug in selectedPlatformSlugs) {
              platformSkills(
                manifest,
                enforceContractVersion,
                packRootsBySlug(platformManifests),
                enforceGovernedReviewStructure,
              )
            } else {
              emptyList()
            },
          baselineLayers = manifest.codeReviewComposition?.baselineLayers.orEmpty(),
        )
      },
    detectedAgentTargets =
      detectAgents(request.home.toPath(), installPlanEnvironment(request)).map { target ->
        InstallAgentTarget(
          agent = SupportedAgent.fromId(target.name),
          path = target.path,
          source = InstallAgentTargetSource.DETECTED,
        )
      },
    defaultAgentTargets = multiRootDefaultTargets(request.home.toPath(), installPlanEnvironment(request)),
  )
}

internal fun enumerateInstallPlanSkills(
  request: InstallPlanRequest,
  enforceContractVersion: Boolean = true,
  catalogLoader: PlatformPackCatalogLoader? = null,
  enforceGovernedReviewStructure: Boolean = true,
): List<InstallPlanSkill> {
  requireSupportedAgentContract()
  val platformManifests =
    discoverPlatformManifests(
      request,
      enforceContractVersion,
      catalogLoader,
      enforceGovernedReviewStructure,
    )
  val skills =
    InstallPlanPolicy.buildPlanDraft(
      buildInstallPolicyInput(
        request,
        platformManifests,
        enforceContractVersion,
        enforceGovernedReviewStructure,
      ),
    ).skills
  validateInstallPlanInternalSkills(skills)
  return skills
}

internal fun collectInstallPlanningFacts(
  request: InstallPlanRequest,
  catalogLoader: PlatformPackCatalogLoader? = null,
): InstallPlanningFacts {
  requireSupportedAgentContract()
  val platformManifests = discoverPlatformManifests(request, catalogLoader = catalogLoader)
  return InstallPlanningFacts(
    baseSkills = discoverBaseSkills(request.targetPaths.skillsRoot.toPath()),
    platformManifests = platformManifests,
    detectedAgentTargets =
      detectAgents(request.home.toPath(), installPlanEnvironment(request)).map { target ->
        InstallAgentTarget(
          agent = SupportedAgent.fromId(target.name),
          path = target.path,
          source = InstallAgentTargetSource.DETECTED,
        )
      },
    defaultAgentTargets = multiRootDefaultTargets(request.home.toPath(), installPlanEnvironment(request)),
  )
}

internal fun materializeSelectedPlatformSkills(
  platformManifests: List<PlatformManifest>,
  selectedPlatformSlugs: List<String>,
): List<InstallPlatformPackSnapshot> {
  val selected = selectedPlatformSlugs.toSet()
  return platformManifests.map { manifest ->
    InstallPlatformPackSnapshot(
      slug = manifest.slug,
      packRoot = manifest.packRoot,
      skills =
        if (manifest.slug in selected) {
          platformSkills(manifest, packRootsBySlug = packRootsBySlug(platformManifests))
        } else {
          emptyList()
        },
      baselineLayers = manifest.codeReviewComposition?.baselineLayers.orEmpty(),
    )
  }
}

private fun installPlanEnvironment(request: InstallPlanRequest): Map<String, String> = request.environment

private fun multiRootDefaultTargets(
  home: Path,
  environment: Map<String, String>,
): List<InstallAgentDefaultTarget> =
  agentPaths(home, installConfigRoots(home, environment)).flatMap { (agent, path) ->
    if (agent == SupportedAgent.CLAUDE) {
      claudeSkillTargets(home, environment).map { skillPath ->
        InstallAgentDefaultTarget(agent = agent, path = skillPath.toFileLocation())
      }
    } else if (agent == SupportedAgent.CODEX) {
      codexSkillTargets(home, environment).map { skillPath ->
        InstallAgentDefaultTarget(agent = agent, path = skillPath.toFileLocation())
      }
    } else {
      listOf(InstallAgentDefaultTarget(agent = agent, path = path.toFileLocation()))
    }
  }
