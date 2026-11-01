package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.ActivityResult
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.repository.WorkActivityRepository
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeWorkActivityRepository(private val sessions: DatabaseSessionManager) : WorkActivityRepository {
  override fun observe(goalWorkflowId: String, running: Boolean): Flow<ActivityResult> = sessions.sessionFlow(
    opening = ActivityResult.Loading,
    failed = { ActivityResult.Unavailable(ReadProblem.Storage(it)) },
  ) { it.activity.observe(goalWorkflowId, running) }
}
