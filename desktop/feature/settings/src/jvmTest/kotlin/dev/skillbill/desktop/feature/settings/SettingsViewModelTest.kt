package dev.skillbill.desktop.feature.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.ConfigWriteOutcome
import dev.skillbill.desktop.core.domain.model.MachineConfig
import dev.skillbill.desktop.core.domain.model.MachineConfigChange
import dev.skillbill.desktop.core.domain.model.PhaseInstructionFilesRead
import dev.skillbill.desktop.core.domain.model.ProjectConfig
import dev.skillbill.desktop.core.domain.model.ProjectConfigChange
import dev.skillbill.desktop.core.domain.model.TelemetryLevel
import dev.skillbill.desktop.core.domain.model.TelemetryLevelRead
import dev.skillbill.desktop.core.domain.repository.ClaudeConfigDirectoriesRead
import dev.skillbill.desktop.core.domain.repository.ConfigRead
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.SettingsRejection
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
  @Test
  fun spacesOnlyPathNeverWritesAndShowsFieldError() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    var ownedModel: SettingsViewModel? = null
    try {
      val repository = CountingSettingsRepository()
      val model = viewModel(repository)
      ownedModel = model
      val effects = mutableListOf<SettingsEffect>()
      backgroundScope.launch { model.effects.collect { effects += it } }
      advanceUntilIdle()

      model.onAction(SettingsAction.PathChanged("   "))
      model.onAction(SettingsAction.SavePath)
      advanceUntilIdle()

      assertEquals(0, repository.pathWrites)
      assertEquals(PathError.OnlySpaces, model.state.value.pathError)
      assertTrue(effects.none { it is SettingsEffect.ShowToast })
    } finally {
      ownedModel?.viewModelScope?.cancel()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun failedLoginRegistrationLeavesPreferenceOffAndShowsReason() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    var ownedModel: SettingsViewModel? = null
    try {
      val repository = CountingSettingsRepository()
      val reason = "A system policy blocks start at login on this machine."
      val model = viewModel(repository, registrar = object : LoginItemRegistrar {
        override suspend fun setEnabled(enabled: Boolean) = LoginItemResult.Failed(reason)
      })
      ownedModel = model
      advanceUntilIdle()

      model.onAction(SettingsAction.StartAtLoginChanged(true))
      advanceUntilIdle()

      assertEquals(false, repository.settingsState.startAtLogin)
      assertEquals(reason, model.state.value.loginError)
    } finally {
      ownedModel?.viewModelScope?.cancel()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun failedLoginPreferenceSaveReportsWhenOsCompensationAlsoFails() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    var ownedModel: SettingsViewModel? = null
    try {
      val repository = CountingSettingsRepository(failLoginPreferenceWrite = true)
      val registrations = mutableListOf<Boolean>()
      val registrar = object : LoginItemRegistrar {
        override suspend fun setEnabled(enabled: Boolean): LoginItemResult {
          registrations += enabled
          return if (enabled) LoginItemResult.Applied else LoginItemResult.Failed("Could not remove the login item.")
        }
      }
      val model = viewModel(repository, registrar)
      ownedModel = model
      advanceUntilIdle()

      model.onAction(SettingsAction.StartAtLoginChanged(true))
      advanceUntilIdle()

      assertEquals(listOf(true, false), registrations)
      assertEquals(false, repository.settingsState.startAtLogin)
      assertTrue(model.state.value.loginError.orEmpty().contains("may differ from the saved preference"))
      assertTrue(model.state.value.loginError.orEmpty().contains("Could not remove the login item."))
    } finally {
      ownedModel?.viewModelScope?.cancel()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun oldSessionClaudeDirectoryReadCannotReplaceCurrentResults() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    var ownedModel: SettingsViewModel? = null
    try {
      val session = object : DatabaseSessionController {
        override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Opening(null))
        override fun reopen() = Unit
      }
      val oldRead = CompletableDeferred<List<String>>()
      val currentRead = CompletableDeferred<List<String>>()
      var readIndex = 0
      val config = object : RuntimeConfigRepository by runtimeConfig {
        override suspend fun claudeConfigDirectories(sessionId: Long): ClaudeConfigDirectoriesRead =
          ClaudeConfigDirectoriesRead.Success(if (readIndex++ == 0) oldRead.await() else currentRead.await())
      }
      val model = viewModel(CountingSettingsRepository(), config = config, session = session)
      ownedModel = model
      advanceUntilIdle()

      session.state.value = DatabaseSessionState.Open("old.db", 1)
      runCurrent()
      session.state.value = DatabaseSessionState.Open("current.db", 2)
      runCurrent()
      currentRead.complete(listOf("/current/.claude"))
      runCurrent()
      oldRead.complete(listOf("/old/.claude"))
      runCurrent()

      assertEquals(listOf("/current/.claude"), (model.state.value.claudeDiscovery as ClaudeDirectoryDiscovery.Success).directories)
    } finally {
      ownedModel?.viewModelScope?.cancel()
      Dispatchers.resetMain()
    }
  }

  private fun viewModel(
    repository: CountingSettingsRepository,
    registrar: LoginItemRegistrar = object : LoginItemRegistrar {
      override suspend fun setEnabled(enabled: Boolean) = LoginItemResult.Applied
    },
    config: RuntimeConfigRepository = runtimeConfig,
    session: DatabaseSessionController = object : DatabaseSessionController {
      override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Opening(null))
      override fun reopen() = Unit
    },
  ) = SettingsViewModel(
    repository,
    session,
    config,
    registrar,
  )

  private class CountingSettingsRepository(
    private val delegate: FakeAppSettingsRepository = FakeAppSettingsRepository(),
    private val failLoginPreferenceWrite: Boolean = false,
  ) : AppSettingsRepository by delegate {
    var pathWrites = 0
      private set
    val settingsState get() = delegate.settings.value

    override suspend fun setDatabasePath(path: String?): Outcome<Unit, SettingsRejection> {
      pathWrites++
      return delegate.setDatabasePath(path)
    }

    override suspend fun setStartAtLogin(enabled: Boolean): Outcome<Unit, SettingsRejection> =
      if (failLoginPreferenceWrite) Outcome.Failure(SettingsRejection.WriteFailed) else delegate.setStartAtLogin(enabled)
  }

  private val runtimeConfig = object : RuntimeConfigRepository {
    override fun configDefaults(): dev.skillbill.desktop.core.domain.model.ConfigDefaults = error("unused")
    override suspend fun modelMatrix(): dev.skillbill.desktop.core.domain.repository.ModelMatrixRead = error("unused")
    override suspend fun machineConfig(): ConfigRead<MachineConfig> = error("unused")
    override suspend fun projectConfig(repoRoot: String): ConfigRead<ProjectConfig> = error("unused")
    override suspend fun writeMachineConfig(changes: List<MachineConfigChange>, expected: dev.skillbill.desktop.core.domain.model.ConfigFingerprint?): ConfigWriteOutcome = error("unused")
    override suspend fun writeProjectConfig(repoRoot: String, changes: List<ProjectConfigChange>, expected: dev.skillbill.desktop.core.domain.model.ConfigFingerprint?): ConfigWriteOutcome = error("unused")
    override suspend fun telemetryLevel(): TelemetryLevelRead = error("unused")
    override suspend fun setTelemetryLevel(level: TelemetryLevel, expected: dev.skillbill.desktop.core.domain.model.ConfigFingerprint?): ConfigWriteOutcome = error("unused")
    override suspend fun detectedAgentsRead(): dev.skillbill.desktop.core.domain.model.DetectedAgentsRead = error("unused")
    override suspend fun claudeConfigDirectories(sessionId: Long): ClaudeConfigDirectoriesRead = ClaudeConfigDirectoriesRead.Success(emptyList())
    override suspend fun phaseInstructionFiles(repoRoot: String): PhaseInstructionFilesRead = error("unused")
  }
}
