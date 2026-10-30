package skillbill.ports.db

/**
 * Connections the runtime keeps open between reads so repeated reads skip reopening and reverifying the store.
 */
interface DatabaseReadConnections {
  /**
   * Closes every connection kept open for reads. Safe to call more than once and when none is open.
   * A later read reopens connections through the full verified path.
   */
  fun release()
}
