package dev.skillbill.desktop.core.domain.repository

/** Asks the operator to pick a folder through the platform's folder dialog. */
fun interface FolderChooser {
  suspend fun choose(): FolderChoice
}

sealed interface FolderChoice {
  data class Chosen(val path: String) : FolderChoice

  data object Cancelled : FolderChoice

  data class Failed(val message: String) : FolderChoice
}
