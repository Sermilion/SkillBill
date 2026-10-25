package dev.skillbill.tray.core.data.di

import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.repository.RoomWorkBoardRepository
import dev.skillbill.tray.core.data.repository.RuntimeAgentHomeRepository
import dev.skillbill.tray.core.data.repository.RoomWorkDetailRepository
import dev.skillbill.tray.core.data.repository.RuntimeSpecBrowserRepository
import dev.skillbill.tray.core.data.repository.RuntimeWorkControlRepository
import dev.skillbill.tray.core.data.session.DatabaseSelection
import dev.skillbill.tray.core.data.session.DatabaseSession
import dev.skillbill.tray.core.data.session.SessionFactory
import dev.skillbill.tray.core.data.session.SessionParts
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.ContributesSubcomponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Everything bound to one selected database. A component lives from the selection until the selection changes or the
 * app exits; the app-scope [dev.skillbill.tray.core.data.session.WorkStoreSessionManager] creates and closes them.
 */
@ContributesSubcomponent(DatabaseSessionScope::class)
@SingleIn(DatabaseSessionScope::class)
interface DatabaseSessionComponent {
  val selection: DatabaseSelection
  val session: DatabaseSession
  val board: RoomWorkBoardRepository
  val detail: RoomWorkDetailRepository
  val control: RuntimeWorkControlRepository
  val specs: RuntimeSpecBrowserRepository
  val agentHomes: RuntimeAgentHomeRepository

  @ContributesSubcomponent.Factory(AppScope::class)
  interface Factory {
    fun createDatabaseSessionComponent(): DatabaseSessionComponent
  }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ComponentSessionFactory(private val components: DatabaseSessionComponent.Factory) : SessionFactory {
  override fun create(path: String?): SessionParts {
    val component = components.createDatabaseSessionComponent()
    component.selection.choose(path)
    return SessionParts(component.session, component.board, component.detail, component.control, component.specs, component.agentHomes)
  }
}
