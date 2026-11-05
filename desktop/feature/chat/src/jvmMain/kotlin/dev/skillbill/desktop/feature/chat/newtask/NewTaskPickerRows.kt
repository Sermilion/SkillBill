package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.domain.model.RepositoryBranches
import kotlin.time.Instant

internal sealed interface BranchRow {
  val value: String

  data class Default(val name: String?) : BranchRow {
    override val value: String = ""
  }

  data class Branch(val name: String, val committedAt: Instant) : BranchRow {
    override val value: String = name
  }
}

internal data class BranchGroup(val head: String, val rows: List<BranchRow.Branch>)

internal data class BranchListing(val default: BranchRow.Default?, val groups: List<BranchGroup>) {
  val rows: List<BranchRow> get() = listOfNotNull(default) + groups.flatMap { it.rows }
}

internal fun branchListing(read: RepositoryBranches?, filter: String): BranchListing {
  val query = filter.trim()
  fun matches(name: String) = query.isEmpty() || name.contains(query, ignoreCase = true)
  val default = BranchRow.Default(read?.defaultBranch)
    .takeIf { matches(it.name ?: "default branch") || "default".contains(query, ignoreCase = true) }
  val groups = read?.let {
    listOf(
      BranchGroup("Local", it.local.map { row -> BranchRow.Branch(row.name, row.committedAt) }.filter { row -> matches(row.name) }),
      BranchGroup("Remote", it.remote.map { row -> BranchRow.Branch(row.name, row.committedAt) }.filter { row -> matches(row.name) }),
    ).filter { group -> group.rows.isNotEmpty() }
  }.orEmpty()
  return BranchListing(default, groups)
}

@Stable
internal class PickerHighlight {
  private var key by mutableStateOf<String?>(null)
  private var touched by mutableStateOf(false)

  fun index(keys: List<String>, preferred: String?): Int? {
    if (keys.isEmpty()) return null
    key?.let { current -> return keys.indexOf(current).takeIf { it >= 0 } ?: 0 }
    if (touched) return null
    return preferred?.let(keys::indexOf)?.takeIf { it >= 0 } ?: 0
  }

  fun move(delta: Int, keys: List<String>, preferred: String?) {
    if (keys.isEmpty()) {
      key = null
      touched = true
      return
    }
    val current = index(keys, preferred)
    val next = if (current == null) {
      if (delta < 0) keys.lastIndex else 0
    } else {
      (current + delta).mod(keys.size)
    }
    key = keys[next]
    touched = true
  }

  fun resetToFirst(keys: List<String>) {
    key = keys.firstOrNull()
    touched = true
  }

  fun target(key: String) {
    this.key = key
    touched = true
  }
}
