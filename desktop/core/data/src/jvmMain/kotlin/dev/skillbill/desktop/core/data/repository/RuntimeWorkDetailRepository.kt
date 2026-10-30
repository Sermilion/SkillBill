package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailListOutcome
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** The app-scoped detail: it follows the database session and delegates to that session's detail observations. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeWorkDetailRepository(private val sessions: DatabaseSessionManager) : WorkDetailRepository {
  override fun observe(item: WorkItem): Flow<DetailState> = sessions.sessionFlow(
    opening = DetailState(item, DetailSections.Pending),
    failed = { DetailState(item, DetailSections.Failed(ReadProblem.Storage(it))) },
  ) { it.details.observe(item) }

  override suspend fun refresh(id: String) {
    sessions.sessions.value?.details?.refresh(id)
  }

  override suspend fun loadList(id: String, list: DetailList, anchor: String?): DetailListOutcome =
    sessions.sessions.value?.details?.loadList(id, list, anchor) ?: DetailListOutcome.End

  override suspend fun prefetch(id: String): RunOptions? = sessions.sessions.value?.details?.prefetch(id)
}
