package dev.skillbill.tray.core.ui.navigation

/**
 * What a route's arguments came to when an entry was asked to build its ViewModel. A route that cannot name a valid target is
 * [Rejected] with the route type and the reason, so the failure is attributable and the factory is never called.
 */
sealed interface EntryArgsOutcome<out A : Any> {
  data class Valid<A : Any>(val args: A) : EntryArgsOutcome<A>

  data class Rejected(val routeType: String, val reason: String) : EntryArgsOutcome<Nothing>
}
