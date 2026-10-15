package dev.skillbill.tray.feature.detail

import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.ui.navigation.AppRoute
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import dev.skillbill.tray.core.ui.navigation.EntryArgsOutcome
import kotlinx.serialization.Serializable

/** The detail of a live work item, opened from the work list. */
@Serializable
data class WorkDetailRoute(
  val workId: String,
  val repositoryIdentity: String,
  override val generation: DatabaseGenerationRef? = null,
) : AppRoute {
  override val entryKey: String get() = detailEntryKey("work-detail", workId, repositoryIdentity, generation)
}

/** The detail of a finished work item, opened from the history list. */
@Serializable
data class HistoryDetailRoute(
  val workId: String,
  val repositoryIdentity: String,
  override val generation: DatabaseGenerationRef? = null,
) : AppRoute {
  override val entryKey: String get() = detailEntryKey("history-detail", workId, repositoryIdentity, generation)
}

private fun detailEntryKey(kind: String, workId: String, repositoryIdentity: String, generation: DatabaseGenerationRef?) =
  "$kind:${generation?.generation}:$repositoryIdentity:$workId"

/** What a detail entry needs to build its ViewModel: the one work item it shows. */
data class DetailArgs(val identity: WorkIdentity) {
  companion object {
    /** Validates the route's arguments; a route that names no item, or is not a detail route, is rejected with the reason. */
    fun from(route: AppRoute): EntryArgsOutcome<DetailArgs> = when (route) {
      is WorkDetailRoute -> arguments(route::class.simpleName, route.workId, route.repositoryIdentity)
      is HistoryDetailRoute -> arguments(route::class.simpleName, route.workId, route.repositoryIdentity)
      else -> EntryArgsOutcome.Rejected(route::class.simpleName.orEmpty(), "Not a work detail route")
    }

    private fun arguments(routeType: String?, workId: String, repositoryIdentity: String): EntryArgsOutcome<DetailArgs> =
      if (workId.isBlank() || repositoryIdentity.isBlank()) {
        EntryArgsOutcome.Rejected(routeType.orEmpty(), "A work detail needs a work id and a repository identity")
      } else {
        EntryArgsOutcome.Valid(DetailArgs(WorkIdentity(workId, repositoryIdentity)))
      }
  }
}

/** The route that opens [identity]'s detail from the work list under [generation]. */
fun workDetailRoute(identity: WorkIdentity, generation: DatabaseGenerationRef?) =
  WorkDetailRoute(identity.id, identity.repositoryIdentity, generation)

/** The route that opens [identity]'s detail from the history list under [generation]. */
fun historyDetailRoute(identity: WorkIdentity, generation: DatabaseGenerationRef?) =
  HistoryDetailRoute(identity.id, identity.repositoryIdentity, generation)

/** The identity a detail route shows, or null for any other route. */
fun AppRoute.detailIdentity(): WorkIdentity? = (DetailArgs.from(this) as? EntryArgsOutcome.Valid)?.args?.identity
