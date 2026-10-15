package dev.skillbill.tray.core.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Names one database generation: the number the data layer advances on every database selection and whenever the file under
 * the open connection is replaced. A route that carries one is only valid while the session is still at that generation.
 */
@Serializable
data class DatabaseGenerationRef(val generation: Long)

/**
 * A destination in the common shell. Each feature declares its own routes; composeApp registers every one of them in the
 * saved-state serializers module and in the entry adapters. A route carries durable identity and small arguments only:
 * never a repository, a component, a path object or a callback, and holding one never authorizes a control.
 */
interface AppRoute : NavKey {
  /**
   * The saveable identity of the entry this route opens. It names the entry's ViewModelStore and saved state, so routes that
   * change only a detail of the same entry (a list's filter) share it and a different generation never does.
   */
  val entryKey: String

  /** The generation this route was made under, or null when the route names nothing that lives in one database. */
  val generation: DatabaseGenerationRef?
}
