package skillbill.ports.idestatus

import skillbill.ports.idestatus.model.AgentBuildHistory
import skillbill.ports.idestatus.model.AgentBuildOutcome
import skillbill.ports.idestatus.model.AgentBuildRun
import skillbill.ports.idestatus.model.AgentBuildRunStart
import java.time.Instant

/**
 * Records the builds an agent run starts and answers the history questions the read surface asks.
 *
 * A row is open until [settle] sets its end, outcome and exit code. [abandonOpen] leaves the row open.
 * Rows without an end time are never pruned and never count toward [history]. Store failures surface as
 * the typed persistence failures; the store does not swallow them, so callers decide whether a failure is
 * best-effort.
 */
interface AgentBuildRunRepository {
  /** Inserts an open row for [start]. */
  fun start(start: AgentBuildRunStart)

  /**
   * Sets the end, outcome and exit code of an open build once. In the same transaction it deletes settled
   * rows for the same repository identity and command beyond the newest 20 by end time.
   *
   * @return false when the build is unknown or already settled.
   */
  fun settle(
    buildId: String,
    endedAt: Instant,
    outcome: AgentBuildOutcome,
    exitCode: Int?,
  ): Boolean

  /**
   * Marks the open builds of [runId] with outcome unknown, leaving their end time empty.
   *
   * @return the number of rows marked.
   */
  fun abandonOpen(runId: String): Int

  /**
   * Returns the build for [runIds], matched against both run id and parent run id: the active row with the
   * latest start if any, else the row with the latest start, else null. Abandoned rows remain eligible as
   * observations but do not retain active-row priority.
   */
  fun latestForRun(runIds: Set<String>): AgentBuildRun?

  /**
   * Returns the average time and previous build of commands equivalent to [command] in [repositoryIdentity],
   * across run identities. Uses at most the newest 20 settled rows that started before and ended no later
   * than [startedBefore]. The average is their arithmetic mean in milliseconds rounded down, null with
   * no matching history. Previous is the most recently ended matching row. Gradle wrapper commands with
   * the same tasks and workload options share history despite different logging options.
   */
  fun history(
    repositoryIdentity: String,
    command: String,
    startedBefore: Instant,
  ): AgentBuildHistory
}
