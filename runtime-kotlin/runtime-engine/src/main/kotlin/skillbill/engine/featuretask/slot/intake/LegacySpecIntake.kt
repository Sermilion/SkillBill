package skillbill.engine.featuretask.slot.intake

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecOrigin
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.projectstore.model.SpecPathLocation
import skillbill.ports.projectstore.model.classifySpecPath
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.model.WorkflowStatus
import java.io.IOException
import java.nio.file.Path

/**
 * Where a new launch whose spec is a `.feature-specs/` spec, named by a path token or found through the issue-key
 * lookup, goes: [NotLegacy] applies the ordinary intake rules,
 * [ResumeInPlace] continues on the legacy spec an active workflow already records, and [Replan] carries a fresh intake
 * that plans a bundle into the project store. A legacy spec that cannot be read replans from its issue key and feature
 * name alone, and a missing legacy spec is [NotLegacy]; no lookup failure blocks the launch.
 */
internal sealed interface LegacySpecRoute {
  data object NotLegacy : LegacySpecRoute

  data object ResumeInPlace : LegacySpecRoute

  data class Replan(val intake: String) : LegacySpecRoute
}

@Inject
public class LegacySpecIntake(
  private val database: DatabaseSessionFactory,
  private val repositories: RepositoryEnclosingRootPort,
  private val projectStore: ProjectStorePort,
  private val fileStore: DecompositionManifestStore,
  private val diagnostics: RuntimeDiagnostics,
  private val specPathResolver: FeatureSpecPathResolverPort,
) {
  internal fun routeIssueKey(
    issueKey: String,
    repoRoot: Path,
  ): LegacySpecRoute {
    val resolved =
      try {
        specPathResolver.resolve(FeatureSpecPathResolveInput(issueKey, null, repoRoot))
      } catch (error: SkillBillRuntimeException) {
        degrade(issueKey, "spec_lookup_failed", error)
        return LegacySpecRoute.NotLegacy
      } catch (error: IOException) {
        degrade(issueKey, "spec_lookup_failed", error)
        return LegacySpecRoute.NotLegacy
      }
    return if (resolved is FeatureSpecPathResolveResult.SingleMatch && resolved.origin == FeatureSpecOrigin.LEGACY) {
      route(repoRoot.resolve(resolved.specPath), issueKey, repoRoot)
    } else {
      LegacySpecRoute.NotLegacy
    }
  }

  internal fun route(
    referencedSpec: Path,
    issueKey: String,
    repoRoot: Path,
  ): LegacySpecRoute {
    val root = repoRoot.toAbsolutePath().normalize()
    val legacy =
      projectStore.resolve(root).classifySpecPath(referencedSpec.toAbsolutePath().normalize(), root)
        as? SpecPathLocation.Legacy
        ?: return LegacySpecRoute.NotLegacy
    return when {
      !legacySpecExists(referencedSpec, legacy.relative) -> LegacySpecRoute.NotLegacy
      recordsLegacySpec(legacy.relative, issueKey, root) -> LegacySpecRoute.ResumeInPlace
      else -> replanIntake(referencedSpec, legacy.relative, issueKey)
    }
  }

  private fun legacySpecExists(
    referencedSpec: Path,
    legacyRelative: String,
  ): Boolean =
    try {
      fileStore.isRegularFile(referencedSpec)
    } catch (error: SkillBillRuntimeException) {
      degrade(legacyRelative, "legacy_spec_unreadable", error)
      true
    } catch (error: IOException) {
      degrade(legacyRelative, "legacy_spec_unreadable", error)
      true
    }

  private fun recordsLegacySpec(
    legacyRelative: String,
    issueKey: String,
    repoRoot: Path,
  ): Boolean =
    try {
      val store = projectStore.resolve(repoRoot)
      database.readIfPresent { unitOfWork ->
        unitOfWork.workflowStates
          .findStandaloneFeatureTaskCandidates(issueKey.trim().uppercase(), repositories.repositoryIdentity(repoRoot))
          .filter { candidate -> isActive(candidate.workflow.workflowStatus) }
          .mapNotNull { candidate -> candidate.identity?.governedSpecPath }
          .any { governed -> relativeForm(governed, store, repoRoot) == legacyRelative }
      } == true
    } catch (error: SkillBillRuntimeException) {
      degrade(legacyRelative, "workflow_lookup_failed", error)
      false
    } catch (error: IOException) {
      degrade(legacyRelative, "workflow_lookup_failed", error)
      false
    }

  private fun replanIntake(
    referencedSpec: Path,
    legacyRelative: String,
    issueKey: String,
  ): LegacySpecRoute.Replan {
    val header = "$issueKey ${issueAndFeature(referencedSpec.parent.fileName.toString()).second}"
    return try {
      LegacySpecRoute.Replan("$header\n${fileStore.readText(referencedSpec)}")
    } catch (error: SkillBillRuntimeException) {
      degrade(legacyRelative, "legacy_spec_unreadable", error)
      LegacySpecRoute.Replan(header)
    } catch (error: IOException) {
      degrade(legacyRelative, "legacy_spec_unreadable", error)
      LegacySpecRoute.Replan(header)
    }
  }

  private fun relativeForm(
    governed: String,
    store: ProjectStoreLocation,
    repoRoot: Path,
  ): String =
    Path.of(governed).takeIf(Path::isAbsolute)
      ?.let { absolute -> store.classifySpecPath(absolute.normalize(), repoRoot)?.relative }
      ?: governed

  private fun isActive(workflowStatus: String): Boolean =
    WorkflowStatus.fromWire(workflowStatus)?.let { it !in WorkflowStatus.terminalStatuses } == true

  private fun degrade(
    legacyRelative: String,
    cause: String,
    error: Throwable,
  ) {
    diagnostics.warning(
      "seam=legacy_spec_intake value_expected=store_bundle value_used=$legacyRelative cause=$cause",
      error,
    )
  }
}
