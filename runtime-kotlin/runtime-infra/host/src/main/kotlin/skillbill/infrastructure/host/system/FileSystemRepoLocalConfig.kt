
package skillbill.infrastructure.host.system

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import me.tatarka.inject.annotations.Inject
import skillbill.config.model.RepoLocalConfig
import skillbill.config.model.RepoLocalConfigFileKeys
import skillbill.config.model.RepoLocalConfigKey
import skillbill.config.model.ReviewContextBudgetParse
import skillbill.config.model.ValidationGateRepoConfig
import skillbill.config.model.ValidationGateRepoConfigParse
import skillbill.config.model.parseReviewContextBudget
import skillbill.config.model.parseSpecType
import skillbill.config.model.parseValidationGateRepoConfig
import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.malformedRepoLocalConfigError
import skillbill.error.shellcontent.unreadableRepoLocalConfigError
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemRepoLocalConfig(
  private val diagnostics: RuntimeDiagnostics,
  private val projectStore: ProjectStorePort,
) : RepoLocalConfigPort {
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }

  override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest): ReadRepoLocalConfigResult {
    val configPath =
      readableRepoLocalConfigPath(diagnostics, request.repoRoot, projectStore.resolve(request.repoRoot).configPath)
        ?: return ReadRepoLocalConfigResult(RepoLocalConfig.defaults())
    val payload = readConfigPayload(configPath)
    val raw = parseConfigMap(configPath, payload)
    rejectRemovedParallelAgentConfig(configPath, raw)
    return ReadRepoLocalConfigResult(buildConfig(configPath, raw))
  }

  private fun buildConfig(
    path: Path,
    raw: Map<String, Any?>,
  ): RepoLocalConfig =
    RepoLocalConfig(
      specType =
        parseKnownKey(path, raw, RepoLocalConfigKey.SPEC_TYPE) { value -> parseSpecType(value) }
          ?: RepoLocalConfig.defaults().specType,
      reviewContextBudget =
        if (raw.containsKey(RepoLocalConfigFileKeys.REVIEW_CONTEXT_BUDGET)) {
          parseReviewContextBudget(path, raw[RepoLocalConfigFileKeys.REVIEW_CONTEXT_BUDGET])
        } else {
          ReviewContextBudgetPolicy.DEFAULT
        },
      validationGate =
        if (raw.containsKey(RepoLocalConfigFileKeys.VALIDATION_GATE)) {
          parseValidationGate(path, raw[RepoLocalConfigFileKeys.VALIDATION_GATE])
        } else {
          ValidationGateRepoConfig.defaults()
        },
    )

  private fun rejectRemovedParallelAgentConfig(
    path: Path,
    raw: Map<String, Any?>,
  ) {
    if (!raw.containsKey(RepoLocalConfigFileKeys.REMOVED_PARALLEL_AGENT)) return
    val normalized = raw[RepoLocalConfigFileKeys.REMOVED_PARALLEL_AGENT]?.toString()?.trim()?.lowercase()
    if (normalized.isNullOrBlank() || normalized == "none") return
    throw malformedRepoLocalConfigError(
      path = path.toString(),
      key = RepoLocalConfigFileKeys.REMOVED_PARALLEL_AGENT,
      value = raw[RepoLocalConfigFileKeys.REMOVED_PARALLEL_AGENT].toString(),
      reason = "names a removed capability; delete this key or set it to none.",
    )
  }

  private fun parseValidationGate(
    path: Path,
    value: Any?,
  ): ValidationGateRepoConfig =
    when (val parsed = parseValidationGateRepoConfig(value)) {
      is ValidationGateRepoConfigParse.Valid -> parsed.config
      is ValidationGateRepoConfigParse.Invalid -> throw malformedRepoLocalConfigError(
        path = path.toString(),
        key = parsed.keyPath,
        value = parsed.value,
        reason = parsed.reason,
      )
    }

  private fun parseReviewContextBudget(
    path: Path,
    value: Any?,
  ): ReviewContextBudgetPolicy {
    val raw = value as? Map<*, *>
    if (raw != null && raw.containsKey("provider_token_thresholds")) {
      diagnostics.warning(
        "Repo-local config degradation: seam=provider_token_thresholds used=ignored " +
          "expected=absent cause=retired configuration block",
      )
    }
    return when (val parsed = parseReviewContextBudget(value)) {
      is ReviewContextBudgetParse.Valid -> parsed.policy
      is ReviewContextBudgetParse.Invalid -> throw malformedRepoLocalConfigError(
        path.toString(),
        parsed.keyPath,
        parsed.value,
        parsed.reason,
      )
    }
  }

  private fun <T> parseKnownKey(
    path: Path,
    raw: Map<String, Any?>,
    configKey: RepoLocalConfigKey,
    parser: (String?) -> T?,
  ): T? {
    if (!raw.containsKey(configKey.key)) return null
    val rawValue = raw[configKey.key]
    val asString = rawValue?.toString()
    return parser(asString) ?: throw malformedRepoLocalConfigError(
      path = path.toString(),
      key = configKey.key,
      value = rawValue?.toString() ?: "null",
      reason = "is not a recognized value for this key.",
    )
  }

  private fun parseConfigMap(
    path: Path,
    payload: String,
  ): Map<String, Any?> {
    if (payload.isBlank()) return emptyMap()
    return try {
      JsonCodec.anyToStringAnyMap(yamlMapper.readValue(payload, Any::class.java)) ?: emptyMap()
    } catch (error: JacksonException) {
      throw malformedRepoLocalConfigError(
        path = path.toString(),
        key = "",
        value = "<document>",
        reason = "is not a valid YAML mapping.",
        cause = error,
      )
    }
  }

  private fun readConfigPayload(path: Path): String =
    try {
      Files.readString(path)
    } catch (error: IOException) {
      throw unreadableRepoLocalConfigError(path.toString(), error)
    } catch (error: SecurityException) {
      throw unreadableRepoLocalConfigError(path.toString(), error)
    }
}

internal const val REPO_LOCAL_CONFIG_FILE_NAME: String = "config.yaml"
