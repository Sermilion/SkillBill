package skillbill.agent.buildrun

import skillbill.agent.model.BuildCommandEvent
import skillbill.agent.model.BuildCommandEventKind
import skillbill.agent.model.BuildMatch
import skillbill.agent.model.BuildOutcome
import skillbill.agent.model.BuildTrackerEvent
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val COMMAND_TOKEN_START = """(^|[\s/'"`;&|(])"""
private const val COMMAND_TOKEN_END = """($|[\s'"`;&|)])"""

class BuildTracker(private val clock: Clock) {
  private val builds = mutableListOf<TrackedBuild>()
  private val calls = mutableMapOf<String, TrackedCall>()
  private var finished = false

  @Synchronized
  fun observe(
    matches: List<BuildMatch>,
    observedAt: Instant = clock.instant(),
  ): List<BuildTrackerEvent> {
    if (finished) return emptyList()
    val existing = builds.toList()
    val newBuilds = mutableListOf<TrackedBuild>()
    val seenKeys = matches.map { BuildKey(it.pid, it.startedAt) }.toSet()
    builds.filter { it.endedAt == null && it.key !in seenKeys }.forEach { build ->
      build.endedAt = observedAt
    }
    val knownKeys = builds.map(TrackedBuild::key).toSet()
    matches.filter { BuildKey(it.pid, it.startedAt) !in knownKeys }.distinctBy { it.pid }.forEach { match ->
      val startedAt = match.startedAt ?: observedAt
      val build = TrackedBuild(BuildKey(match.pid, match.startedAt), match, startedAt, correlate(match, startedAt))
      builds += build
      newBuilds += build
    }
    val settlements =
      reconcileCalls() +
        builds.filter { it.callId == null && it.endedAt != null && !it.settled }.map(::settleUnknown)
    val existingSettlements = settlements.filter { event -> existing.any { it.matches(event) } }
    val newSettlements = settlements - existingSettlements.toSet()
    return existingSettlements + newBuilds.map(::started) + newSettlements
  }

  @Synchronized
  fun command(event: BuildCommandEvent): List<BuildTrackerEvent> {
    if (finished) return emptyList()
    val call = calls.getOrPut(event.callId) { TrackedCall(event.callId, event.commandText, event.observedAt) }
    if (event.kind == BuildCommandEventKind.STARTED || call.completion != null) return emptyList()
    call.completion = event
    if (event.completionProcessSamples == null) {
      call.eligibleKeysAtCompletion =
        builds.filter { it.callId == call.id && it.endedAt != null && !it.settled }.map(TrackedBuild::key).toSet()
      return reconcileCalls()
    }
    return emptyList()
  }

  @Synchronized
  fun finish(observedAt: Instant = clock.instant()): List<BuildTrackerEvent> {
    if (finished) return emptyList()
    finished = true
    builds.filter { it.endedAt == null }.forEach { it.endedAt = observedAt }
    val settlements = reconcileCalls()
    val remaining = builds.filterNot(TrackedBuild::settled).map(::settleUnknown)
    return settlements + remaining
  }

  private fun correlate(
    match: BuildMatch,
    startedAt: Instant,
  ): String? {
    val names = setOf(basename(match.executable), basename(match.displayExecutable))
    return calls.values
      .filter { call ->
        !call.startedAt.isAfter(startedAt) &&
          call.completion?.observedAt?.isBefore(startedAt) != true &&
          names.any { name -> mentionsExecutable(call.commandText, name) }
      }.maxByOrNull(TrackedCall::startedAt)
      ?.id
  }

  private fun mentionsExecutable(
    commandText: String,
    name: String,
  ): Boolean = Regex(COMMAND_TOKEN_START + Regex.escape(name) + COMMAND_TOKEN_END).containsMatchIn(commandText)

  private fun reconcileCalls(): List<BuildTrackerEvent> {
    val events = mutableListOf<BuildTrackerEvent>()
    calls.values.forEach { call ->
      val completion = call.completion ?: return@forEach
      val correlated = builds.filter { it.callId == call.id && !it.settled }
      val eligible = correlated.filter { build -> call.isEligible(build) }
      if (call.winner == null && eligible.isNotEmpty() && eligible.all { it.endedAt != null }) {
        val winner =
          eligible.maxWithOrNull(
            compareBy<TrackedBuild> { it.endedAt }
              .thenBy { builds.indexOf(it) },
          )
        if (winner != null) {
          call.winner = winner.key
          eligible.forEach { build ->
            events +=
              if (build === winner) {
                settle(build, completionOutcome(completion), completionExit(completion))
              } else {
                settleUnknown(build)
              }
          }
        }
      }
      correlated.filter { it.endedAt != null && it !in eligible }.forEach { events += settleUnknown(it) }
    }
    return events
  }

  private fun started(build: TrackedBuild): BuildTrackerEvent =
    BuildTrackerEvent.Started(
      pid = build.match.pid,
      displayCommand = build.match.displayCommand,
      shortCommand = build.match.shortCommand,
      toolLabel = build.match.toolLabel,
      projectKind = build.match.projectKind,
      executable = build.match.executable,
      displayExecutable = build.match.displayExecutable,
      startedAt = build.startedAt,
    )

  private fun settleUnknown(build: TrackedBuild): BuildTrackerEvent = settle(build, BuildOutcome.UNKNOWN, null)

  private fun settle(
    build: TrackedBuild,
    outcome: BuildOutcome,
    exitCode: Int?,
  ): BuildTrackerEvent {
    build.settled = true
    val endedAt = build.endedAt ?: build.startedAt
    return BuildTrackerEvent.Settled(
      pid = build.match.pid,
      startedAt = build.startedAt,
      endedAt = endedAt,
      durationMs = Duration.between(build.startedAt, endedAt).toMillis().coerceAtLeast(0),
      outcome = outcome,
      exitCode = exitCode,
    )
  }

  private fun completionOutcome(event: BuildCommandEvent): BuildOutcome =
    when {
      event.exitCode != null -> if (event.exitCode == 0) BuildOutcome.PASSED else BuildOutcome.FAILED
      event.kind == BuildCommandEventKind.COMPLETED -> BuildOutcome.PASSED
      else -> BuildOutcome.FAILED
    }

  private fun completionExit(event: BuildCommandEvent): Int? =
    event.exitCode ?: if (event.kind == BuildCommandEventKind.COMPLETED) 0 else null

  private data class BuildKey(
    val pid: Long,
    val startedAt: Instant?,
  )

  private fun TrackedBuild.matches(event: BuildTrackerEvent): Boolean =
    event is BuildTrackerEvent.Settled && event.pid == match.pid && event.startedAt == startedAt

  private class TrackedBuild(
    val key: BuildKey,
    val match: BuildMatch,
    val startedAt: Instant,
    val callId: String?,
  ) {
    var endedAt: Instant? = null
    var settled: Boolean = false
  }

  private class TrackedCall(
    val id: String,
    val commandText: String,
    val startedAt: Instant,
  ) {
    var completion: BuildCommandEvent? = null
    var eligibleKeysAtCompletion: Set<BuildKey>? = null
    var winner: BuildKey? = null

    fun isEligible(build: TrackedBuild): Boolean {
      if (winner != null) return false
      val event = completion ?: return false
      val samples = event.completionProcessSamples
      return if (samples == null) {
        build.key in eligibleKeysAtCompletion.orEmpty()
      } else {
        samples.none { sample ->
          sample.pid == build.key.pid &&
            (sample.startedAt == null || build.key.startedAt == null || sample.startedAt == build.key.startedAt)
        }
      }
    }
  }
}
