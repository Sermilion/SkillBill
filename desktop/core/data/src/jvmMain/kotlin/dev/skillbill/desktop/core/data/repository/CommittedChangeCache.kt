package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.FileDiffState
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

internal data class CommittedListKey(val worktreeRoot: String, val fromSha: String, val toSha: String)

internal data class CommittedPatchKey(
  val worktreeRoot: String,
  val fromSha: String,
  val toSha: String,
  val path: String,
  val oldPath: String?,
)

@Inject
@SingleIn(AppScope::class)
class CommittedChangeCache(internal val capacity: Int = 512) {
  private val lock = Any()
  private val lists = object : LinkedHashMap<CommittedListKey, ChangeSet>(capacity + 1, 1f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CommittedListKey, ChangeSet>) = size > capacity
  }
  private val patches = object : LinkedHashMap<CommittedPatchKey, FileDiffState>(capacity + 1, 1f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CommittedPatchKey, FileDiffState>) = size > capacity
  }

  init {
    require(capacity >= 1)
  }

  internal fun list(key: CommittedListKey): ChangeSet? = synchronized(lock) { lists[key] }

  internal fun putList(key: CommittedListKey, value: ChangeSet) {
    synchronized(lock) { lists[key] = value }
  }

  internal fun patch(key: CommittedPatchKey): FileDiffState? = synchronized(lock) { patches[key] }

  internal fun putPatch(key: CommittedPatchKey, value: FileDiffState) {
    if (value !is FileDiffState.Shown && value != FileDiffState.Binary && value !is FileDiffState.TooLarge) return
    synchronized(lock) { patches[key] = value }
  }
}
