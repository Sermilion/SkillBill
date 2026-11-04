package dev.skillbill.desktop.app.shell

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.SpecNode
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailResolution
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.FavoriteSpecKey
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey
import dev.skillbill.desktop.core.domain.settings.orderedFavorites
import dev.skillbill.desktop.core.domain.settings.orderedSpecFavorites
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.board.ShellBoard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

sealed interface FavoriteSidebarEntry {
  val key: FavoriteWorkKey
  data class Loading(override val key: FavoriteWorkKey) : FavoriteSidebarEntry
  data class Resolved(override val key: FavoriteWorkKey, val item: WorkItem) : FavoriteSidebarEntry
  data class Missing(override val key: FavoriteWorkKey) : FavoriteSidebarEntry
  data class Failed(override val key: FavoriteWorkKey) : FavoriteSidebarEntry
  data class OtherDatabase(override val key: FavoriteWorkKey) : FavoriteSidebarEntry
}

sealed interface FavoriteSpecSidebarEntry {
  val key: FavoriteSpecKey
  data class Loading(override val key: FavoriteSpecKey) : FavoriteSpecSidebarEntry
  data class Resolved(override val key: FavoriteSpecKey, val title: String, val issueKey: String?) : FavoriteSpecSidebarEntry
  data class Missing(override val key: FavoriteSpecKey) : FavoriteSpecSidebarEntry
  data class Failed(override val key: FavoriteSpecKey) : FavoriteSpecSidebarEntry
}

@Inject
@SingleIn(WindowScope::class)
class FavoritesSidebarState(
  private val settings: AppSettingsRepository,
  session: DatabaseSessionController,
  board: WorkBoardRepository,
  details: WorkDetailRepository,
  private val specs: SpecRepository,
  private val scope: WindowCoroutineScope,
  private val toasts: ToastQueue,
) {
  private val mutableEntries = MutableStateFlow<List<FavoriteSidebarEntry>>(emptyList())
  val entries: StateFlow<List<FavoriteSidebarEntry>> = mutableEntries
  private val mutableSpecEntries = MutableStateFlow<List<FavoriteSpecSidebarEntry>>(emptyList())
  val specEntries: StateFlow<List<FavoriteSpecSidebarEntry>> = mutableSpecEntries

  init {
    scope.scope.launch {
      val events = Channel<Event>(Channel.UNLIMITED)
      val latestFavoriteKeys = AtomicReference<Set<FavoriteWorkKey>>(emptySet())
      val refreshSession = AtomicReference<DatabaseSessionState?>(null)
      launch {
        combine(settings.favoriteWork, session.state) { keys, current -> keys to current }
          .collect { (keys, current) ->
            if (keys is Outcome.Success) latestFavoriteKeys.set(keys.value)
            events.send(Event.Data(keys, current))
          }
      }
      launch {
        board.observeRefreshes(ShellBoard.query).collect {
          val current = session.state.value
          if (refreshSession.getAndSet(current) == current) events.send(Event.Refresh)
        }
      }

      var confirmedKeys: Set<FavoriteWorkKey> = emptySet()
      var keys: List<FavoriteWorkKey> = emptyList()
      var currentSession: DatabaseSessionState? = null
      var initialized = false
      var batchId = 0L
      var batchJob: kotlinx.coroutines.Job? = null
      var pendingRefresh = false
      val resolved = linkedMapOf<FavoriteWorkKey, FavoriteSidebarEntry>()

      fun publish() {
        mutableEntries.value = keys.mapNotNull(resolved::get)
      }

      fun classify(key: FavoriteWorkKey, current: DatabaseSessionState): FavoriteSidebarEntry {
        val open = current as? DatabaseSessionState.Open
        val identity = open?.databaseFile?.normalized()
        return when {
          open == null || open.databaseFile == null -> FavoriteSidebarEntry.Failed(key)
          identity == null || normalized(key.databaseFile.path) != identity.path || key.databaseFile.fileKey != identity.fileKey -> FavoriteSidebarEntry.OtherDatabase(key)
          else -> FavoriteSidebarEntry.Loading(key)
        }
      }

      fun startBatch() {
        val open = currentSession as? DatabaseSessionState.Open
        val sessionId = open?.sessionId
        val identity = open?.databaseFile?.normalized()
        val batchKeys = keys
        val id = ++batchId
        batchJob = launch {
          val semaphore = Semaphore(4)
          coroutineScope {
            batchKeys.map { key -> async {
              val entry = when {
                open == null || open.databaseFile == null -> FavoriteSidebarEntry.Failed(key)
                identity == null || normalized(key.databaseFile.path) != identity.path || key.databaseFile.fileKey != identity.fileKey -> FavoriteSidebarEntry.OtherDatabase(key)
                else -> semaphore.withPermit {
                  when (val result = try { details.resolve(key.workItemId) } catch (failure: CancellationException) { throw failure } catch (_: Exception) { null }) {
                    is WorkDetailResolution.Resolved -> FavoriteSidebarEntry.Resolved(key, result.item)
                    WorkDetailResolution.Missing -> FavoriteSidebarEntry.Missing(key)
                    is WorkDetailResolution.Failed, null -> FavoriteSidebarEntry.Failed(key)
                  }
                }
              }
              events.send(Event.Result(id, key, sessionId, identity, entry))
            } }.forEach { it.await() }
          }
          events.send(Event.BatchFinished(id))
        }
      }

      fun beginPass(resetResolved: Boolean) {
        batchJob?.cancel()
        if (resetResolved) {
          resolved.clear()
          keys.forEach { resolved[it] = classify(it, currentSession!!) }
        } else {
          val previous = resolved.toMap()
          resolved.clear()
          keys.forEach { key -> resolved[key] = previous[key]?.takeIf { it is FavoriteSidebarEntry.Resolved } ?: classify(key, currentSession!!) }
        }
        publish()
        startBatch()
      }

      for (event in events) {
        when (event) {
          is Event.Data -> {
            if (event.keys is Outcome.Success) confirmedKeys = event.keys.value
            val nextKeys = confirmedKeys.orderedFavorites(::normalized)
            val sessionChanged = !initialized || event.current != currentSession
            val keysChanged = nextKeys != keys
            if (sessionChanged || keysChanged) {
              initialized = true
              currentSession = event.current
              keys = nextKeys
              pendingRefresh = false
              beginPass(resetResolved = sessionChanged)
            }
          }
          Event.Refresh -> {
            if (batchJob?.isActive == true) {
              pendingRefresh = true
            } else if (initialized) {
              beginPass(resetResolved = false)
            }
          }
          is Event.Result -> {
            val currentOpen = session.state.value as? DatabaseSessionState.Open
            if (event.batchId == batchId && event.key in keys && event.key in latestFavoriteKeys.get() && currentOpen != null && currentOpen.sessionId == event.sessionId && currentOpen.databaseFile?.normalized() == event.identity) {
              resolved[event.key] = event.entry
              publish()
            }
          }
          is Event.BatchFinished -> if (event.batchId == batchId) {
            batchJob = null
            if (pendingRefresh) {
              pendingRefresh = false
              beginPass(resetResolved = false)
            }
          }
        }
      }
    }

    scope.scope.launch {
      val events = Channel<SpecEvent>(Channel.UNLIMITED)
      launch {
        combine(settings.favoriteSpecs, session.state) { keys, current -> SpecEvent.Input(keys, current) }
          .collect(events::send)
      }

      var confirmedKeys = emptySet<FavoriteSpecKey>()
      var settingsFailed = false
      var currentSession: DatabaseSessionState? = null
      var sessionId: Long? = null
      var projects: List<SpecProject>? = null
      var projectsFailed = false
      var trees = mutableMapOf<String, SpecTreeState>()
      val treeJobs = mutableMapOf<String, Job>()
      val treeGenerations = mutableMapOf<String, Long>()
      var keysGeneration = 0L
      var listingJob: Job? = null
      var listingGeneration = 0L

      fun publishSpecEntries() {
        val sorted = confirmedKeys.orderedSpecFavorites()
        mutableSpecEntries.value = sorted.map { key ->
          when {
            settingsFailed -> FavoriteSpecSidebarEntry.Failed(key)
            currentSession is DatabaseSessionState.Failed -> FavoriteSpecSidebarEntry.Failed(key)
            currentSession !is DatabaseSessionState.Open -> FavoriteSpecSidebarEntry.Loading(key)
            projectsFailed -> FavoriteSpecSidebarEntry.Failed(key)
            projects == null -> FavoriteSpecSidebarEntry.Loading(key)
            else -> resolveSpecEntry(key, projects.orEmpty(), trees[key.projectId])
          }
        }
      }

      fun cancelTree(projectId: String) {
        treeJobs.remove(projectId)?.cancel()
        treeGenerations[projectId] = (treeGenerations[projectId] ?: 0L) + 1L
        trees.remove(projectId)
      }

      fun observeTrees(openSessionId: Long) {
        val neededProjects = confirmedKeys.map { it.projectId }.toSet()
        treeJobs.keys.filter { it !in neededProjects }.toList().forEach(::cancelTree)
        neededProjects.forEach { projectId ->
          if (projectId !in treeJobs) {
            val generation = (treeGenerations[projectId] ?: 0L) + 1L
            treeGenerations[projectId] = generation
            treeJobs[projectId] = launch {
              try {
                specs.observeTree(projectId).collect { tree ->
                  events.send(SpecEvent.Tree(openSessionId, projectId, generation, tree))
                }
              } catch (failure: CancellationException) {
                throw failure
              } catch (failure: Exception) {
                events.send(SpecEvent.Tree(openSessionId, projectId, generation, SpecTreeState.Unavailable(ReadProblem.Runtime(failure.message ?: "Spec tree read failed."))))
              }
            }
          }
        }
      }

      fun refreshProjects(openSessionId: Long) {
        listingJob?.cancel()
        val generation = ++listingGeneration
        val expectedKeysGeneration = keysGeneration
        projects = null
        projectsFailed = false
        publishSpecEntries()
        listingJob = launch {
          val result: Outcome<List<SpecProject>, ReadProblem> = try {
            specs.projects()
          } catch (failure: CancellationException) {
            throw failure
          } catch (failure: Exception) {
            Outcome.Failure(ReadProblem.Runtime(failure.message ?: "Spec project read failed."))
          }
          events.send(SpecEvent.Projects(openSessionId, expectedKeysGeneration, generation, result))
        }
      }

      for (event in events) {
        when (event) {
          is SpecEvent.Input -> {
            val recoveringSettings = settingsFailed && event.keys is Outcome.Success
            var keysChanged = false
            if (event.keys is Outcome.Success) {
              val nextKeys = event.keys.value
              if (nextKeys != confirmedKeys) {
                keysChanged = true
                confirmedKeys = nextKeys
                keysGeneration++
                listingJob?.cancel()
                projects = null
                treeJobs.keys.filter { it !in nextKeys.map(FavoriteSpecKey::projectId).toSet() }.toList().forEach(::cancelTree)
              }
              settingsFailed = false
            } else {
              settingsFailed = true
            }
            val nextSessionId = (event.current as? DatabaseSessionState.Open)?.sessionId
            val sessionChanged = event.current != currentSession || nextSessionId != sessionId
            currentSession = event.current
            if (sessionChanged) {
              listingJob?.cancel()
              listingJob = null
              listingGeneration++
              projects = null
              trees.clear()
              treeJobs.keys.toList().forEach(::cancelTree)
              sessionId = nextSessionId
              if (nextSessionId != null) observeTrees(nextSessionId)
            } else if (nextSessionId != null) {
              observeTrees(nextSessionId)
            }
            publishSpecEntries()
            if (nextSessionId != null && confirmedKeys.isNotEmpty() && !settingsFailed && (sessionChanged || keysChanged || recoveringSettings || projects == null)) {
              refreshProjects(nextSessionId)
            }
          }
          is SpecEvent.Tree -> {
            val open = session.state.value as? DatabaseSessionState.Open
            if (event.sessionId == sessionId && event.sessionId == open?.sessionId &&
              event.generation == treeGenerations[event.projectId] && event.projectId in confirmedKeys.map(FavoriteSpecKey::projectId).toSet()
            ) {
              trees[event.projectId] = event.state
              if (settingsFailed) publishSpecEntries() else refreshProjects(event.sessionId)
            }
          }
          is SpecEvent.Projects -> {
            val open = session.state.value as? DatabaseSessionState.Open
            if (event.sessionId == sessionId && event.sessionId == open?.sessionId &&
              event.keysGeneration == keysGeneration && event.generation == listingGeneration
            ) {
              listingJob = null
              when (val result = event.result) {
                is Outcome.Success -> {
                  projects = result.value
                  projectsFailed = false
                }
                is Outcome.Failure -> {
                  projects = null
                  projectsFailed = true
                }
              }
              publishSpecEntries()
            }
          }
        }
      }
    }
  }

  fun remove(key: FavoriteWorkKey) {
    scope.scope.launch {
      when (try { settings.setFavorite(key, false) is Outcome.Success } catch (failure: CancellationException) { throw failure } catch (_: Exception) { false }) {
        true -> Unit
        false -> toasts.show(ToastMessage(ToastTone.Problem, "Favorite could not be removed. It remains saved."))
      }
    }
  }

  fun removeSpec(key: FavoriteSpecKey) {
    scope.scope.launch {
      when (try { settings.setSpecFavorite(key, false) is Outcome.Success } catch (failure: CancellationException) { throw failure } catch (_: Exception) { false }) {
        true -> Unit
        false -> toasts.show(ToastMessage(ToastTone.Problem, "Favorite could not be removed. It remains saved."))
      }
    }
  }

  private fun resolveSpecEntry(
    key: FavoriteSpecKey,
    projects: List<SpecProject>,
    tree: SpecTreeState?,
  ): FavoriteSpecSidebarEntry {
    if (projects.none { it.projectId == key.projectId }) return FavoriteSpecSidebarEntry.Missing(key)
    return when (tree) {
      is SpecTreeState.Unavailable -> FavoriteSpecSidebarEntry.Failed(key)
      SpecTreeState.NoCheckout -> FavoriteSpecSidebarEntry.Failed(key)
      is SpecTreeState.NoSpecs -> FavoriteSpecSidebarEntry.Missing(key)
      is SpecTreeState.Loaded -> {
        val bundle = findBundle(tree.tree.roots, key.path)
        if (bundle != null) FavoriteSpecSidebarEntry.Resolved(key, bundle.title, bundle.issueKey)
        else FavoriteSpecSidebarEntry.Missing(key)
      }
      else -> FavoriteSpecSidebarEntry.Loading(key)
    }
  }

  private fun findBundle(nodes: List<SpecNode>, path: String): SpecNode.Bundle? =
    nodes.firstNotNullOfOrNull { node ->
      when (node) {
        is SpecNode.Bundle -> if (node.path == path) node else findBundle(node.children, path)
        is SpecNode.Folder -> findBundle(node.children, path)
        is SpecNode.File -> null
      }
    }

  private sealed interface Event {
    data class Data(val keys: Outcome<Set<FavoriteWorkKey>, *>, val current: DatabaseSessionState) : Event
    data object Refresh : Event
    data class Result(val batchId: Long, val key: FavoriteWorkKey, val sessionId: Long?, val identity: DatabaseFileIdentity?, val entry: FavoriteSidebarEntry) : Event
    data class BatchFinished(val batchId: Long) : Event
  }

  private sealed interface SpecEvent {
    data class Input(val keys: Outcome<Set<FavoriteSpecKey>, *>, val current: DatabaseSessionState) : SpecEvent
    data class Tree(val sessionId: Long, val projectId: String, val generation: Long, val state: SpecTreeState) : SpecEvent
    data class Projects(
      val sessionId: Long,
      val keysGeneration: Long,
      val generation: Long,
      val result: Outcome<List<SpecProject>, *>,
    ) : SpecEvent
  }

  private fun normalized(path: String): String = runCatching { Path.of(path).toAbsolutePath().normalize().toString() }.getOrDefault(path)
  private fun DatabaseFileIdentity.normalized() = DatabaseFileIdentity(normalized(path), fileKey)
}
