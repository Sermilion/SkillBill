package skillbill.ports.apprun

import skillbill.ports.apprun.model.AppRunProcessExit
import skillbill.ports.apprun.model.AppRunProcessSpec

interface AppRunProcessPort {
  /**
   * Starts a process with merged output. Lines arrive in output order on a reader thread.
   * The spec environment extends the inherited environment. Start failures include the command and cause.
   */
  fun start(
    spec: AppRunProcessSpec,
    onLine: (String) -> Unit,
  ): AppRunProcess
}

interface AppRunProcess {
  /** Waits for process and reader completion and returns the exit status. */
  fun awaitExit(): AppRunProcessExit

  /** Stops the process and its descendants. */
  fun stop()
}
