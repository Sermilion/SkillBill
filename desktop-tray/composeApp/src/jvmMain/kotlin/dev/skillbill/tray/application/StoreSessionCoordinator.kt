package dev.skillbill.tray.application

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.WorkStoreSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Applies the preferences that act on the machine rather than on a screen: which database the application reads, and whether
 * it starts at login. The screens react to the same preferences on their own; the data layer holds back reads while the
 * selected database is being switched.
 */
class StoreSessionCoordinator(
  scope: CoroutineScope,
  settings: SettingsRepository,
  private val storeSession: WorkStoreSession,
  private val native: NativeServices,
) {
  private val mutableProblem = MutableStateFlow<Problem?>(null)

  /** The last preference that could not be applied, shown by the shell until the next successful application. */
  val problem: StateFlow<Problem?> = mutableProblem.asStateFlow()

  private var applied: AppSettings? = null

  init {
    scope.launch { settings.settings.collect(::apply) }
  }

  private suspend fun apply(result: SettingsState) {
    val next = (result as? SettingsState.Ready)?.settings ?: return
    val previous = applied
    applied = next
    if (previous == null || previous.startAtLogin != next.startAtLogin) {
      mutableProblem.value = if (native.setStartAtLogin(next.startAtLogin)) null else {
        Problem(ProblemCode.LOGIN_SETTING_FAILED, "Start at login could not be changed.")
      }
    }
    if (previous == null || previous.databasePath != next.databasePath) select(next.databasePath)
  }

  private suspend fun select(path: String?) {
    try {
      storeSession.selectDatabase(path)
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Exception) {
      System.getLogger("dev.skillbill.tray").log(System.Logger.Level.WARNING, "desktop:database_selection_failed", failure)
      mutableProblem.value = Problem(ProblemCode.DATABASE_ACCESS, "The selected database could not be opened.")
    }
  }
}
