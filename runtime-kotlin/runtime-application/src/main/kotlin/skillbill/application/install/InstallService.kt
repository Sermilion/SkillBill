package skillbill.application.install

import me.tatarka.inject.annotations.Inject
import skillbill.install.model.BaselineManifest
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlatformPackDiscoverySnapshot
import skillbill.install.model.InstallPlatformSkillMaterializationRequest
import skillbill.install.model.InstallReconcileApplyOutcome
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.ReconciliationPlan
import skillbill.install.model.SharedInstallSelection
import skillbill.install.model.buildInstallPlanWireMap
import skillbill.install.model.selectedPlatformSlugs
import skillbill.install.policy.InstallPlanPolicy
import skillbill.model.toPath
import skillbill.ports.install.InstallPlanWireValidator
import skillbill.ports.install.apply.InstallApplyExecutionPort
import skillbill.ports.install.apply.model.InstallApplyExecutionRequest
import skillbill.ports.install.baseline.BaselineManifestPersistencePort
import skillbill.ports.install.baseline.model.ReadBaselineManifestRequest
import skillbill.ports.install.baseline.model.WriteBaselineManifestRequest
import skillbill.ports.install.link.InstallSkillLinkPort
import skillbill.ports.install.link.model.InstallSkillLinkRequest
import skillbill.ports.install.plan.InstallPlanningFactsPort
import skillbill.ports.install.plan.InstallPlatformSkillMaterializationPort
import skillbill.ports.install.plan.InstallStagingIntentPort
import skillbill.ports.install.plan.model.InstallPlanningFactsRequest
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortRequest
import skillbill.ports.install.plan.model.InstallStagingIntentRequest
import skillbill.ports.install.reconcile.InstallReconcileApplyPort
import skillbill.ports.install.reconcile.InstallReconcilePort
import skillbill.ports.install.reconcile.model.InstallReconcileApplyRequest
import skillbill.ports.install.reconcile.model.InstallReconcileRequest
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionRequest
import skillbill.ports.telemetry.transport.TelemetryLevelMutator
import skillbill.review.plan.ReviewFallbackResolver
import java.nio.file.Path

@Inject
class InstallService(
  private val planningFactsPort: InstallPlanningFactsPort,
  private val platformSkillMaterializationPort: InstallPlatformSkillMaterializationPort,
  private val stagingIntentPort: InstallStagingIntentPort,
  private val reconcilePort: InstallReconcilePort,
  private val reconcileApplyPort: InstallReconcileApplyPort,
  private val baselineManifestPersistencePort: BaselineManifestPersistencePort,
  private val applyExecutionPort: InstallApplyExecutionPort,
  private val skillLinkPort: InstallSkillLinkPort,
  private val installSelectionPersistencePort: InstallSelectionPersistencePort,
  private val installPlanWireValidator: InstallPlanWireValidator,
) {
  fun planInstall(request: InstallPlanRequest): InstallPlan {
    val facts = planningFactsPort.collectPlanningFacts(InstallPlanningFactsRequest(request)).facts
    val resolvedReviewFallback =
      facts.baseSkills
        .takeIf { skills -> skills.any { it.name == PACK_SIDECAR_PARENT_SKILL } }
        ?.let { ReviewFallbackResolver.resolveOptional(facts.platformManifests) }
    val materializationPlan =
      InstallPlanPolicy.planPlatformSkillMaterialization(
        InstallPlatformSkillMaterializationRequest(
          installRequest = request,
          platformPacks =
            facts.platformManifests.map { manifest ->
              InstallPlatformPackDiscoverySnapshot(
                slug = manifest.slug,
                packRoot = manifest.packRoot,
                baselineLayers = manifest.codeReviewComposition?.baselineLayers.orEmpty(),
              )
            },
        ),
      )
    val platformPacks =
      platformSkillMaterializationPort.materializePlatformSkills(
        InstallPlatformSkillMaterializationPortRequest(
          installRequest = request,
          platformManifests = facts.platformManifests,
          selectedPlatformSlugs =
            (
              materializationPlan.selectedPlatformSlugs +
                listOfNotNull(resolvedReviewFallback?.slug)
            ).distinct(),
        ),
      ).platformPacks
    val draft =
      InstallPlanPolicy.buildPlanDraft(
        facts.toPolicyInput(request, platformPacks, resolvedReviewFallback?.slug),
      )
    val staging =
      stagingIntentPort.buildStagingIntent(
        InstallStagingIntentRequest(
          installRequest = request,
          draft = draft,
          platformManifests = facts.platformManifests,
        ),
      ).staging
    return validatedInstallPlan(draft, staging, installPlanWireValidator)
  }

  fun reconcile(request: InstallReconcileRequest): ReconciliationPlan = reconcilePort.reconcile(request).plan

  fun applyReconcile(request: InstallReconcileApplyRequest): InstallReconcileApplyOutcome {
    val applied = reconcileApplyPort.apply(request)
    val before =
      baselineManifestPersistencePort
        .readBaseline(ReadBaselineManifestRequest(installHome = request.home))
        .manifest
    val updated = refreshBaselineFromPlan(request.home, applied.plan)
    return InstallReconcileApplyOutcome(
      plan = applied.plan,
      installedPaths = applied.installedPaths,
      prunedPaths = applied.prunedPaths,
      refreshed = updated != before,
    )
  }

  fun refreshBaselineFromPlan(
    home: Path,
    plan: ReconciliationPlan,
  ): BaselineManifest {
    val current =
      baselineManifestPersistencePort
        .readBaseline(ReadBaselineManifestRequest(installHome = home))
        .manifest
    val updated = current.withEntries(plan.baselineOverlay).withoutEntries(plan.prunedPaths)
    if (updated != current) {
      baselineManifestPersistencePort.writeBaseline(
        WriteBaselineManifestRequest(installHome = home, manifest = updated),
      )
    }
    return updated
  }

  fun applyInstall(
    plan: InstallPlan,
    telemetryLevelMutator: TelemetryLevelMutator? = null,
  ): InstallApplyResult {
    val result =
      applyExecutionPort.applyInstall(
        InstallApplyExecutionRequest(
          plan = plan,
          telemetryLevelMutator = telemetryLevelMutator,
        ),
      ).result
    persistSuccessfulInstallSelection(plan, result)
    return result
  }

  fun validateInstallPlanWire(plan: InstallPlan) {
    installPlanWireValidator.validate(buildInstallPlanWireMap(plan))
  }

  fun discoverPlatformPackSlugs(request: InstallPlanRequest): Set<String> =
    planningFactsPort
      .collectPlanningFacts(InstallPlanningFactsRequest(request))
      .facts
      .platformManifests
      .mapTo(mutableSetOf()) { manifest -> manifest.slug }

  fun linkSkill(
    source: Path,
    targetDir: Path,
    agent: String,
    repoRoot: Path? = null,
    home: Path? = null,
  ): List<Path> =
    skillLinkPort.linkSkill(
      InstallSkillLinkRequest(
        source = source,
        targetDir = targetDir,
        agent = agent,
        repoRoot = repoRoot,
        home = home,
      ),
    ).linkedPaths

  private fun persistSuccessfulInstallSelection(
    plan: InstallPlan,
    result: InstallApplyResult,
  ) {
    if (result.status == InstallApplyStatus.FAILURE) {
      return
    }
    installSelectionPersistencePort.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(
        installHome = plan.request.home.toPath(),
        selection =
          SharedInstallSelection(
            selectedAgents =
              result.resolvedInstalledAgents.agents.ifEmpty {
                plan.agents.mapTo(mutableSetOf()) { target -> target.agent }
              },
            platformPackSelection = persistedPlatformPackSelection(plan),
            telemetryLevel = plan.telemetryLevel,
            mcpRegistrationChoice = plan.request.mcpRegistrationChoice,
          ),
      ),
    )
  }

  private fun persistedPlatformPackSelection(plan: InstallPlan): PlatformPackSelection =
    PlatformPackSelection(
      mode = plan.request.platformPackSelection.mode,
      selectedSlugs =
        if (plan.request.platformPackSelection.mode == PlatformPackSelectionMode.SELECTED) {
          plan.selectedPlatformSlugs.toSet()
        } else {
          emptySet()
        },
    )
}
