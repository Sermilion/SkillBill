package dev.skillbill.tray.application

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.RuntimeBuildProblem
import dev.skillbill.tray.core.domain.model.RuntimeBuildRefusedException
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = System.getLogger("dev.skillbill.tray")

/**
 * Applies the preferences that act on the machine rather than on a screen: which database the application reads, and whether
 * it starts at login. A database switch goes through [switchDatabase], which the app's transition coordinator owns so every
 * window leaves the old database before it closes. The screens react to the same preferences on their own.
 *
 * Every [retry] re-reads the installed runtime's build record. A refused build closes the open session and stays refused until
 * a later retry finds a matched pair.
 */
class StoreSessionCoordinator(
  scope: CoroutineScope,
  settings: SettingsRepository,
  private val switchDatabase: suspend (String?) -> Unit,
  private val checkRuntimeBuild: suspend () -> RuntimeBuildProblem?,
  private val native: NativeServices,
) {
  private val mutableProblem = MutableStateFlow<Problem?>(null)

  /** The last preference that could not be applied, shown by the shell until the next successful application. */
  val problem: StateFlow<Problem?> = mutableProblem.asStateFlow()

  @Volatile private var applied: AppSettings? = null

  // A retry and a settings change select one at a time, and each reads the path inside the lock, so the last selection is
  // always the latest preference.
  private val selection = Mutex()

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
    if (previous == null || previous.databasePath != next.databasePath) selection.withLock { select(next.databasePath) }
  }

  /**
   * Re-reads the installed runtime's build record. A selection the runtime build refused is selected again; an open session
   * whose runtime is now another build is selected again too, which closes it and publishes the refusal.
   */
  suspend fun retry() {
    if (applied == null) return
    if (mutableProblem.value?.code == ProblemCode.RUNTIME_BUILD_MISMATCH || runtimeBuildRefused()) {
      selection.withLock { select(applied?.databasePath) }
    }
  }

  // A check that fails is no proof of a match, so the selection runs again and its own gate decides.
  private suspend fun runtimeBuildRefused(): Boolean = try {
    checkRuntimeBuild() != null
  } catch (failure: CancellationException) {
    throw failure
  } catch (failure: Exception) {
    logger.log(System.Logger.Level.WARNING, "desktop:runtime_build_check_failed", failure)
    true
  }

  private suspend fun select(path: String?) {
    try {
      switchDatabase(path)
      if (mutableProblem.value?.code == ProblemCode.RUNTIME_BUILD_MISMATCH) mutableProblem.value = null
    } catch (failure: CancellationException) {
      throw failure
    } catch (refused: RuntimeBuildRefusedException) {
      logger.log(System.Logger.Level.WARNING, "desktop:runtime_build_refused", refused)
      mutableProblem.value = Problem(
        ProblemCode.RUNTIME_BUILD_MISMATCH,
        "The installed Skill Bill runtime is not the build this app was packaged with.",
        runtimeBuild = refused.problem,
      )
    } catch (failure: Exception) {
      logger.log(System.Logger.Level.WARNING, "desktop:database_selection_failed", failure)
      mutableProblem.value = Problem(ProblemCode.DATABASE_ACCESS, "The selected database could not be opened.")
    }
  }
}
