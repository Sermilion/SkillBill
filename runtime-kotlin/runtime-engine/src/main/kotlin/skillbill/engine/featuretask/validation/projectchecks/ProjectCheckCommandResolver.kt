package skillbill.engine.featuretask.validation.projectchecks

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.config.projectchecks.ProjectCheckCategory
import skillbill.contracts.config.projectchecks.ProjectCheckConfig
import skillbill.contracts.config.projectchecks.ProjectCheckConfigDiscovery
import skillbill.contracts.config.projectchecks.ProjectCheckDiscoveryResponse
import skillbill.contracts.config.projectchecks.wireValue
import skillbill.ports.config.projectchecks.ProjectCheckDiscoveryLock
import skillbill.ports.diagnostics.RuntimeDiagnostics

@Inject
public class ProjectCheckCommandResolver(
  private val configAccess: ProjectCheckConfigAccess,
  private val discovery: ProjectCheckCommandDiscovery,
  private val lock: ProjectCheckDiscoveryLock,
  private val diagnostics: RuntimeDiagnostics,
) {
  public fun resolve(request: ProjectCheckCommandResolverRequest): ProjectCheckConfigAccessSnapshot {
    var attempt = 0
    var lastProjectId: String? = null
    while (attempt < ASSOCIATION_RETRY_LIMIT) {
      val snapshot = configAccess.load(request.workingDirectory)
      lastProjectId = snapshot.location.projectId
      val missing = missingCategories(snapshot.config, request.requestedCategories)
      if (missing.isEmpty()) {
        diagnoseKnownEmpty(snapshot.config, request.requestedCategories)
        return snapshot
      }
      val owned =
        lock.withProjectOwnership(snapshot.location.projectId) {
          refreshAndDiscover(request, snapshot.location.projectId)
        }
      if (owned != null) {
        diagnoseKnownEmpty(owned.config, request.requestedCategories)
        return owned
      }
      attempt++
      diagnostics.info(
        "seam=project_check_resolver outcome=association_retry project=${snapshot.location.projectId}",
      )
    }
    diagnostics.warning(
      "seam=project_check_resolver outcome=association_retry_exhausted project=${lastProjectId ?: "<unresolved>"}",
    )
    return configAccess.load(request.workingDirectory)
  }

  private fun refreshAndDiscover(
    request: ProjectCheckCommandResolverRequest,
    boundProjectId: String,
  ): ProjectCheckConfigAccessSnapshot? {
    val refreshed = configAccess.load(request.workingDirectory)
    if (refreshed.location.projectId != boundProjectId) {
      return null
    }
    val stillMissing = missingCategories(refreshed.config, request.requestedCategories)
    if (stillMissing.isEmpty()) {
      return refreshed
    }
    return when (
      val outcome =
        discovery.discover(
          ProjectCheckCommandDiscoveryInvocation(
            refreshed.location.projectId,
            request.workingDirectory,
            stillMissing,
            request.discovery,
          ),
        )
    ) {
      is ProjectCheckCommandDiscoveryOutcome.Succeeded ->
        publishDiscovered(request, refreshed, stillMissing, outcome.response)
      is ProjectCheckCommandDiscoveryOutcome.ContractRejected -> {
        diagnostics.warning(
          "seam=project_check_resolver outcome=discovery_rejected project=${refreshed.location.projectId} " +
            "categories=${stillMissing.joinToString { it.wireValue }}",
          outcome.failure,
        )
        refreshed
      }
      is ProjectCheckCommandDiscoveryOutcome.Unavailable -> refreshed
    }
  }

  private fun publishDiscovered(
    request: ProjectCheckCommandResolverRequest,
    refreshed: ProjectCheckConfigAccessSnapshot,
    stillMissing: Set<ProjectCheckCategory>,
    response: ProjectCheckDiscoveryResponse,
  ): ProjectCheckConfigAccessSnapshot? {
    if (response.projectId != refreshed.location.projectId) {
      return null
    }
    val afterDiscovery = configAccess.load(request.workingDirectory)
    if (afterDiscovery.location.projectId != refreshed.location.projectId) {
      return null
    }
    val config =
      configAccess.fillMissing(
        refreshed.location.projectId,
        response.toConfigDiscovery(),
      )
    diagnoseFirstCommittedConflicts(refreshed.location.projectId, stillMissing, response, config)
    return ProjectCheckConfigAccessSnapshot(refreshed.location, config)
  }

  private fun missingCategories(
    config: ProjectCheckConfig,
    requested: Set<ProjectCheckCategory>,
  ): Set<ProjectCheckCategory> = requested.filter { config.category(it) == null }.toSet()

  private fun diagnoseFirstCommittedConflicts(
    projectId: String,
    discovered: Set<ProjectCheckCategory>,
    proposed: ProjectCheckDiscoveryResponse,
    committed: ProjectCheckConfig,
  ) {
    discovered.forEach { category ->
      if (proposed.categories[category] != committed.category(category)) {
        diagnostics.warning(
          "seam=project_check_resolver outcome=first_committed_conflict project=$projectId " +
            "category=${category.wireValue}",
        )
      }
    }
  }

  private fun diagnoseKnownEmpty(
    config: ProjectCheckConfig,
    requested: Set<ProjectCheckCategory>,
  ) {
    requested.forEach { category ->
      val commands = config.category(category)?.commands
      if (commands != null && commands.isEmpty()) {
        diagnostics.info(
          "seam=project_check_resolver outcome=known_empty project=${config.projectId} category=${category.wireValue}",
        )
      }
    }
  }

  private fun ProjectCheckDiscoveryResponse.toConfigDiscovery(): ProjectCheckConfigDiscovery =
    ProjectCheckConfigDiscovery(
      buildCommands = categories[ProjectCheckCategory.BUILD],
      lintCommands = categories[ProjectCheckCategory.LINT],
      testCommands = categories[ProjectCheckCategory.TEST],
      formattingCommands = categories[ProjectCheckCategory.FORMATTING],
      validationCommands = categories[ProjectCheckCategory.VALIDATION],
    )

  private companion object {
    const val ASSOCIATION_RETRY_LIMIT = 4
  }
}
