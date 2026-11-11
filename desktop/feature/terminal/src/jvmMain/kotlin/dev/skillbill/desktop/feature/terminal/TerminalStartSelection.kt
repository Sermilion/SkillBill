package dev.skillbill.desktop.feature.terminal

import java.nio.file.Path

/** Where a tab opened without a directory starts: a work item's checkout, a spec project's checkout, a chat's repository, or home. */
internal sealed interface SelectedRoot {
  val directory: Path?
  val fallbackRoot: Path?
  val notice: String? get() = null

  data class Work(override val directory: Path, override val fallbackRoot: Path? = null, override val notice: String? = null) : SelectedRoot

  data class Spec(override val directory: Path?) : SelectedRoot {
    override val fallbackRoot: Path? = null
  }

  data class Chat(override val directory: Path) : SelectedRoot {
    override val fallbackRoot: Path? = null
  }

  data object None : SelectedRoot {
    override val directory: Path? = null
    override val fallbackRoot: Path? = null
  }
}

/**
 * The window's latest selection. A work item or spec project selection is the base; the New task screen's chat repository lies
 * over it while that screen shows, unless a newer base selection replaces it, and the base comes back once the screen leaves.
 */
internal class TerminalStartSelection {
  private var base: SelectedRoot = SelectedRoot.None
  private var chat: SelectedRoot.Chat? = null

  val current: SelectedRoot get() = chat ?: base

  /** A cleared selection drops only a work item base; a spec project stays selected. */
  fun itemSelected(root: SelectedRoot.Work?) {
    if (root != null) {
      base = root
      chat = null
    } else if (base is SelectedRoot.Work) {
      base = SelectedRoot.None
    }
  }

  fun specProjectSelected(checkout: Path?) {
    base = SelectedRoot.Spec(checkout)
    chat = null
  }

  /** Null when the New task screen leaves or its selected chat has no repository. */
  fun chatRepositorySelected(root: Path?) {
    chat = root?.let(SelectedRoot::Chat)
  }
}
