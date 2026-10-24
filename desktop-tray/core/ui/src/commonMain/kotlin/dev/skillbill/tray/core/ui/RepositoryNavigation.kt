package dev.skillbill.tray.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.skillbill.tray.core.domain.repository.Outcome

interface RepositoryNavigation {
  suspend fun openRepository(root: String, branch: String? = null): Outcome<Unit>
  suspend fun openDirectory(root: String): Outcome<Unit>
  suspend fun openFile(path: String): Outcome<Unit>
}

val LocalRepositoryNavigation = staticCompositionLocalOf<RepositoryNavigation> {
  error("Repository navigation is not available outside a desktop window.")
}
