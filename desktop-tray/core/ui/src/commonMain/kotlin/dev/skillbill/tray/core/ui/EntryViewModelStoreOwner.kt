package dev.skillbill.tray.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

private class EntryViewModelStoreOwner : ViewModelStoreOwner {
  override val viewModelStore = ViewModelStore()
}

/**
 * One [ViewModelStore] per [key]. ViewModels created in it live while the key is composed, are cleared when the key changes,
 * and are cleared when the call leaves the composition. Desktop windows have no store owner of their own, so each window or
 * selected entry supplies one here.
 */
@Composable
fun rememberEntryViewModelStoreOwner(key: Any?): ViewModelStoreOwner {
  val owner = remember(key) { EntryViewModelStoreOwner() }
  DisposableEffect(owner) {
    onDispose { owner.viewModelStore.clear() }
  }
  return owner
}

/** The ViewModel of [owner] stored under [key], created by [create] the first time. */
@Composable
inline fun <reified VM : ViewModel> rememberViewModel(owner: ViewModelStoreOwner, key: String, crossinline create: () -> VM): VM =
  viewModel(
    viewModelStoreOwner = owner,
    key = key,
    factory = viewModelFactory { initializer { create() } },
  )
