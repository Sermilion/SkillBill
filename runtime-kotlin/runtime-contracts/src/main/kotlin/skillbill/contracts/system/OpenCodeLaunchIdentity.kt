package skillbill.contracts.system

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest

data class OpenCodeEnvironmentSelection(
  val supplied: Boolean,
  val value: String?,
)

enum class OpenCodeLaunchIdentityFailureCode : RuntimeFailureCode {
  INVALID_PATH,
}

object OpenCodeLaunchIdentityKeys {
  const val HOME = "home"
  const val CONFIG_HOME = "config_home"
  const val DATA_HOME = "data_home"
  const val CACHE_HOME = "cache_home"
  const val STATE_HOME = "state_home"
  const val CONFIG_DIRECTORY = "config_directory"
  const val CONFIG_FILE = "config_file"
  const val DISABLE_PROJECT_CONFIG = "disable_project_config"
  const val EFFECTIVE_HOME = "effective_home"
  const val EFFECTIVE_CONFIG_HOME = "effective_config_home"
  const val EFFECTIVE_DATA_HOME = "effective_data_home"
  const val EFFECTIVE_CACHE_HOME = "effective_cache_home"
  const val EFFECTIVE_STATE_HOME = "effective_state_home"
  const val EFFECTIVE_CONFIG_DIRECTORY = "effective_config_directory"
  const val EFFECTIVE_CONFIG_FILE = "effective_config_file"
  const val INLINE_CONFIG_PRESENT = "inline_config_present"
  const val INLINE_CONFIG_FINGERPRINT = "inline_config_fingerprint"
  const val SUPPLIED = "supplied"
  const val VALUE = "value"
}

object OpenCodeEnvironmentKeys {
  const val HOME = "HOME"
  const val XDG_CONFIG_HOME = "XDG_CONFIG_HOME"
  const val XDG_DATA_HOME = "XDG_DATA_HOME"
  const val XDG_CACHE_HOME = "XDG_CACHE_HOME"
  const val XDG_STATE_HOME = "XDG_STATE_HOME"
  const val CONFIG_FILE = "OPENCODE_CONFIG"
  const val CONFIG_CONTENT = "OPENCODE_CONFIG_CONTENT"
  const val DISABLE_PROJECT_CONFIG = "OPENCODE_DISABLE_PROJECT_CONFIG"
  val ALL: Set<String> =
    setOf(
      HOME, XDG_CONFIG_HOME, XDG_DATA_HOME, XDG_CACHE_HOME, XDG_STATE_HOME,
      AgentHomeEnvironmentKeys.OPENCODE_CONFIG_DIR, CONFIG_FILE, CONFIG_CONTENT, DISABLE_PROJECT_CONFIG,
    )
}

data class OpenCodeLaunchIdentity(
  val home: OpenCodeEnvironmentSelection,
  val configHome: OpenCodeEnvironmentSelection,
  val dataHome: OpenCodeEnvironmentSelection,
  val cacheHome: OpenCodeEnvironmentSelection,
  val stateHome: OpenCodeEnvironmentSelection,
  val configDirectory: OpenCodeEnvironmentSelection,
  val configFile: OpenCodeEnvironmentSelection,
  val disableProjectConfig: OpenCodeEnvironmentSelection,
  val effectiveHome: String,
  val effectiveConfigHome: String,
  val effectiveDataHome: String,
  val effectiveCacheHome: String,
  val effectiveStateHome: String,
  val effectiveConfigDirectory: String,
  val effectiveConfigFile: String?,
  val inlineConfigPresent: Boolean,
  val inlineConfigFingerprint: String?,
) {
  fun conflictsWith(current: OpenCodeLaunchIdentity): String? {
    val roots =
      listOf(
        Triple(
          OpenCodeEnvironmentKeys.HOME,
          home to effectiveHome,
          current.home to current.effectiveHome,
        ),
        Triple(
          OpenCodeEnvironmentKeys.XDG_CONFIG_HOME,
          configHome to effectiveConfigHome,
          current.configHome to current.effectiveConfigHome,
        ),
        Triple(
          OpenCodeEnvironmentKeys.XDG_DATA_HOME,
          dataHome to effectiveDataHome,
          current.dataHome to current.effectiveDataHome,
        ),
        Triple(
          OpenCodeEnvironmentKeys.XDG_CACHE_HOME,
          cacheHome to effectiveCacheHome,
          current.cacheHome to current.effectiveCacheHome,
        ),
        Triple(
          OpenCodeEnvironmentKeys.XDG_STATE_HOME,
          stateHome to effectiveStateHome,
          current.stateHome to current.effectiveStateHome,
        ),
        Triple(
          AgentHomeEnvironmentKeys.OPENCODE_CONFIG_DIR,
          configDirectory to effectiveConfigDirectory,
          current.configDirectory to current.effectiveConfigDirectory,
        ),
        Triple(
          OpenCodeEnvironmentKeys.CONFIG_FILE,
          configFile to effectiveConfigFile,
          current.configFile to current.effectiveConfigFile,
        ),
      )
    val inlineChanged =
      inlineConfigPresent != current.inlineConfigPresent ||
        inlineConfigFingerprint != current.inlineConfigFingerprint
    return roots.firstOrNull { (_, accepted, effective) -> accepted != effective }?.first
      ?: when {
        disableProjectConfig != current.disableProjectConfig -> OpenCodeEnvironmentKeys.DISABLE_PROJECT_CONFIG
        inlineChanged ->
          OpenCodeEnvironmentKeys.CONFIG_CONTENT
        else -> null
      }
  }

  companion object {
    private const val BYTE_MASK = 0xff

    fun capture(
      environment: Map<String, String>,
      userHome: Path,
      workingDirectory: Path,
      explicitProfile: String? = null,
    ): OpenCodeLaunchIdentity {
      val home = selection(environment, OpenCodeEnvironmentKeys.HOME)
      val configHome = selection(environment, OpenCodeEnvironmentKeys.XDG_CONFIG_HOME)
      val dataHome = selection(environment, OpenCodeEnvironmentKeys.XDG_DATA_HOME)
      val cacheHome = selection(environment, OpenCodeEnvironmentKeys.XDG_CACHE_HOME)
      val stateHome = selection(environment, OpenCodeEnvironmentKeys.XDG_STATE_HOME)
      val directory = selection(environment, AgentHomeEnvironmentKeys.OPENCODE_CONFIG_DIR)
      val selectedDirectory =
        if (explicitProfile != null) OpenCodeEnvironmentSelection(true, explicitProfile) else directory
      val configFile = selection(environment, OpenCodeEnvironmentKeys.CONFIG_FILE)
      val disableProject = selection(environment, OpenCodeEnvironmentKeys.DISABLE_PROJECT_CONFIG)
      val effectiveHome =
        absolute(home.value?.takeIf(String::isNotBlank)?.let(::path) ?: userHome, workingDirectory)
      val homePath = Path.of(effectiveHome)
      val effectiveConfigHome = root(configHome, homePath.resolve(".config"), workingDirectory)
      val effectiveDataHome = root(dataHome, homePath.resolve(".local/share"), workingDirectory)
      val effectiveCacheHome = root(cacheHome, homePath.resolve(".cache"), workingDirectory)
      val effectiveStateHome = root(stateHome, homePath.resolve(".local/state"), workingDirectory)
      val effectiveConfigDirectory =
        absolute(
          selectedDirectory.value?.takeIf(String::isNotBlank)?.let(::path)
            ?: Path.of(effectiveConfigHome).resolve("opencode"),
          workingDirectory,
        )
      val effectiveConfigFile =
        configFile.value?.takeIf(String::isNotBlank)?.let {
          absolute(path(it), workingDirectory)
        }
      val inline = environment[OpenCodeEnvironmentKeys.CONFIG_CONTENT]
      return OpenCodeLaunchIdentity(
        home, configHome, dataHome, cacheHome, stateHome, selectedDirectory, configFile, disableProject,
        effectiveHome, effectiveConfigHome, effectiveDataHome, effectiveCacheHome, effectiveStateHome,
        effectiveConfigDirectory, effectiveConfigFile, inline != null, inline?.let(::fingerprint),
      )
    }

    private fun selection(
      environment: Map<String, String>,
      key: String,
    ): OpenCodeEnvironmentSelection = OpenCodeEnvironmentSelection(environment.containsKey(key), environment[key])

    private fun root(
      selection: OpenCodeEnvironmentSelection,
      fallback: Path,
      cwd: Path,
    ): String = absolute(selection.value?.takeIf(String::isNotBlank)?.let(::path) ?: fallback, cwd)

    private fun path(value: String): Path =
      try {
        Path.of(value)
      } catch (failure: InvalidPathException) {
        throw SkillBillRuntimeException(
          OpenCodeLaunchIdentityFailureCode.INVALID_PATH,
          "An OpenCode identity path is invalid. Correct the provider environment and retry.",
          failure,
        )
      }

    private fun absolute(
      path: Path,
      cwd: Path,
    ): String = (if (path.isAbsolute) path else cwd.resolve(path)).normalize().toString()

    private fun fingerprint(content: String): String =
      MessageDigest.getInstance("SHA-256").digest(content.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
  }
}
