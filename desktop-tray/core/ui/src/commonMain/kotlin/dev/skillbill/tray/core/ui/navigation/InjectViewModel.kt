package dev.skillbill.tray.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Holds the generated component one entry builds its ViewModels from. It lives in the entry's ViewModelStore, so the component
 * is created once per live entry, survives recomposition, and is dropped with the entry's ViewModels when the entry is removed,
 * its window is disposed or its database generation ends.
 */
class EntryComponentHolder<C : Any>(val component: C) : ViewModel()

/**
 * The entry's component, created by [create] the first time the entry composes and retained by the entry's ViewModelStore.
 * It must be called inside a navigation entry, where the store belongs to that entry.
 */
@Composable
inline fun <reified C : Any> rememberEntryComponent(crossinline create: () -> C): C =
  viewModel<EntryComponentHolder<C>>(
    key = "entry-component:" + C::class.simpleName,
    factory = viewModelFactory { initializer { EntryComponentHolder(create()) } },
  ).component

/** The ViewModel of the current entry stored under [key], built by [create] the first time and retained until the entry goes. */
@Composable
inline fun <reified VM : ViewModel> rememberEntryViewModel(key: String, crossinline create: () -> VM): VM =
  viewModel(key = key, factory = viewModelFactory { initializer { create() } })
