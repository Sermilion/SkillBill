package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.port.RuntimeAgentHomeGateway
import dev.skillbill.tray.core.domain.repository.AgentHomeRepository
import dev.skillbill.tray.core.domain.repository.Outcome
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** One session's view of the agent account directories; the listing reads the file system, so it runs on the IO dispatcher. */
@Inject
@SingleIn(DatabaseSessionScope::class)
class RuntimeAgentHomeRepository(
  private val gateway: RuntimeAgentHomeGateway,
  private val dispatchers: DispatcherProvider,
) : AgentHomeRepository {
  override suspend fun claudeConfigDirectories(): Outcome<List<String>> =
    withContext(dispatchers.io) { Outcome.Value(gateway.claudeConfigDirectories()) }
}
