package dev.skillbill.desktop.benchmark

import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

data class ScenarioResults(
  val headerMillis: List<Long>,
  val sectionsMillis: List<Long>,
  val signalMillis: List<Long>,
  val missedSignals: Int,
  val filterMillis: List<Long>,
  val boardEmissions: Int,
  val burstBoardEmissions: List<Int>,
  val burstRuntimeReads: List<Int>,
  val runtimeReads: List<Long>,
)

private data class BoardEmission(val atNanos: Long, val board: BoardState.Loaded)

fun runScenarios(database: String, home: String, bursts: Int, samples: Int): ScenarioResults = runBlocking {
  val diagnostics = RecordingDiagnostics()
  val app = BenchmarkComponent::class.create(
    HostEnvironment(emptyMap(), home, home), DiagnosticsSwitch(true), diagnostics,
  )
  val header = mutableListOf<Long>()
  val sections = mutableListOf<Long>()
  val signal = mutableListOf<Long>()
  val filters = mutableListOf<Long>()
  val burstBoardEmissions = mutableListOf<Int>()
  val burstRuntimeReads = mutableListOf<Int>()
  val emissions = AtomicInteger()
  try {
    app.appSettingsRepository.setDatabasePath(database)
    app.databaseSessionManager.start()
    val root = Path.of(home)
    val namespace = java.util.UUID.randomUUID().toString()
    val writer = runtimeWriter(Path.of(database), root)
    var live = registerLive(writer, maxOf(12, samples), root, namespace)
    val query = BoardQuery(HistoryWindow.Day, "SKILL-BENCH-${namespace.take(8)}", maxOf(50, samples))
    val sampleIds = live.take(samples).mapTo(HashSet()) { it.record.invocationId }
    val loaded = withTimeout(30.seconds) {
      app.workBoardRepository.observe(query).first { state ->
        when (state) {
          is BoardState.Loaded -> sampleIds.all { id -> state.items.any { it.control.invocationId == id } }
          is BoardState.Failed -> error("Benchmark board read failed after registration: ${state.problem}.")
          else -> false
        }
      } as BoardState.Loaded
    }
    val sampleItems = loaded.items.filter { it.control.invocationId in sampleIds }
    check(sampleItems.size == samples) {
      "Benchmark board returned ${sampleItems.size} of $samples registered sample records."
    }
    val boardEvents = Channel<BoardEmission>(Channel.UNLIMITED)
    val boardObservations = BoardObservationCursor(boardEvents)
    app.applicationCoroutineScope.scope.launch {
      app.workBoardRepository.observe(query).collect { state ->
        if (state is BoardState.Loaded) {
          emissions.incrementAndGet()
          boardEvents.send(BoardEmission(System.nanoTime(), state))
        }
      }
    }
    for (item in sampleItems.distinctBy(WorkItem::id)) {
      var headerAtNanos: Long? = null
      var sectionsAtNanos: Long? = null
      val startedAtNanos = System.nanoTime()
      val end = app.workDetailRepository.observe(item)
        .onEach { state ->
          val observedAt = System.nanoTime()
          if (headerAtNanos == null) headerAtNanos = observedAt
          when (val detailSections = state.sections) {
            DetailSections.Gone -> error("Benchmark detail '${item.id}' disappeared before its sections loaded.")
            is DetailSections.Failed -> error("Benchmark detail '${item.id}' failed: ${detailSections.problem}.")
            is DetailSections.Loaded -> if (sectionsAtNanos == null) sectionsAtNanos = observedAt
            DetailSections.Pending -> Unit
          }
        }
        .first { it.sections !is DetailSections.Pending }
      check(end.sections is DetailSections.Loaded) { "Benchmark detail '${item.id}' did not load its sections." }
      header += (checkNotNull(headerAtNanos) - startedAtNanos) / 1_000_000
      sections += (checkNotNull(sectionsAtNanos) - startedAtNanos) / 1_000_000
    }
    for (filter in sampleItems.mapNotNull(WorkItem::issueKey).distinct().take(samples)) {
      var resultAtNanos: Long? = null
      val startedAtNanos = System.nanoTime()
      val state = app.workBoardRepository.observe(query.copy(filter = filter, complete = false))
        .onEach { observed ->
          if (observed is BoardState.Loaded && resultAtNanos == null) resultAtNanos = System.nanoTime()
          if (observed is BoardState.Failed) error("Benchmark filter '$filter' failed: ${observed.problem}.")
        }
        .first { it is BoardState.Loaded || it is BoardState.Failed }
      check(state is BoardState.Loaded) { "Benchmark filter '$filter' did not load results." }
      filters += (checkNotNull(resultAtNanos) - startedAtNanos) / 1_000_000
    }

    var missedSignals = 0
    repeat(bursts) {
      val candidate = live.first().record.invocationId
      boardObservations.awaitLiveCandidate(candidate)
      val beforeEmissions = emissions.get()
      val beforeReads = diagnostics.snapshot().size
      val updated = replayActivity(writer, live)
      burstBoardEmissions += emissions.get() - beforeEmissions
      burstRuntimeReads += diagnostics.snapshot().size - beforeReads
      val lifecycle = commitLifecycle(writer, updated, root, namespace)
      live = lifecycle.live
      try {
        withTimeout(5.seconds) {
          while (true) {
            val event = boardObservations.receive()
            if (event.atNanos >= lifecycle.committedAtNanos && event.showsTransition(lifecycle.finishedId, lifecycle.replacementId)) {
              signal += (event.atNanos - lifecycle.committedAtNanos) / 1_000_000
              break
            }
          }
        }
      } catch (_: TimeoutCancellationException) {
        missedSignals++
      }
    }
    ScenarioResults(
      header, sections, signal, missedSignals, filters, emissions.get(), burstBoardEmissions, burstRuntimeReads, diagnostics.snapshot(),
    )
  } finally {
    try {
      app.closeSession()
    } finally {
      app.applicationCoroutineScope.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }
  }
}

private class BoardObservationCursor(private val events: Channel<BoardEmission>) {
  private var latest: BoardEmission? = null

  suspend fun awaitLiveCandidate(candidateId: String) {
    withTimeout(30.seconds) {
      while (true) {
        val observation = latest ?: receive()
        if (observation.showsLive(candidateId)) return@withTimeout
        receive()
      }
    }
  }

  suspend fun receive(): BoardEmission = events.receive().also { latest = it }
}

private fun BoardEmission.showsLive(invocationId: String): Boolean =
  board.sections.any { section -> section.items.any { it.control.invocationId == invocationId && it.liveSection != null } }

private fun BoardEmission.showsTransition(finishedId: String, replacementId: String): Boolean {
  val all = board.items.associateBy { it.control.invocationId }
  val finished = all[finishedId]
  val replacement = all[replacementId]
  return finished?.liveSection == null && replacement?.liveSection != null
}
