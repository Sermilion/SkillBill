package skillbill.infrastructure.host.system

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.apprun.APP_RUN_CONFIG_CONTRACT_VERSION
import skillbill.contracts.apprun.AppRunConfigKeys
import skillbill.error.apprun.invalidAppRunConfigError
import skillbill.error.apprun.unsupportedAppRunConfigVersionError
import skillbill.ports.apprun.AppRunConfigContractValidator
import skillbill.ports.apprun.AppRunConfigPort
import skillbill.ports.apprun.model.AppRunConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemAppRunConfig(
  private val validator: AppRunConfigContractValidator,
) : AppRunConfigPort {
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }

  override fun read(checkoutRoot: Path): AppRunConfig {
    val path = checkoutRoot.resolve(AppRunConfigFileKeys.FILE_NAME)
    val missing =
      try {
        Files.notExists(path)
      } catch (failure: SecurityException) {
        throw invalidAppRunConfigError(path.toString(), "<root>", "could not be inspected", failure)
      }
    if (missing) return AppRunConfig(module = null, variant = AppRunConfigFileKeys.DEFAULT_VARIANT)
    val regularFile =
      try {
        Files.isRegularFile(path)
      } catch (failure: SecurityException) {
        throw invalidAppRunConfigError(path.toString(), "<root>", "could not be inspected", failure)
      }
    if (!regularFile) {
      throw invalidAppRunConfigError(path.toString(), "<root>", "must be a regular file")
    }
    val document =
      try {
        Files.readString(path)
      } catch (failure: IOException) {
        throw invalidAppRunConfigError(path.toString(), "<root>", "could not be read", failure)
      } catch (failure: SecurityException) {
        throw invalidAppRunConfigError(path.toString(), "<root>", "could not be read", failure)
      }
    val raw =
      try {
        yamlMapper.readValue(document, Any::class.java)
      } catch (failure: IOException) {
        throw invalidAppRunConfigError(path.toString(), "<root>", "is not valid YAML", failure)
      }
    val map =
      raw as? Map<*, *>
        ?: throw invalidAppRunConfigError(path.toString(), "<root>", "must be a YAML mapping")
    if (map.keys.any { it !is String }) {
      throw invalidAppRunConfigError(path.toString(), "<root>", "mapping keys must be strings")
    }
    val typed = map.entries.associate { (key, value) -> key as String to value }
    if (
      typed.containsKey(AppRunConfigKeys.CONTRACT_VERSION) &&
      typed[AppRunConfigKeys.CONTRACT_VERSION] != APP_RUN_CONFIG_CONTRACT_VERSION
    ) {
      throw unsupportedAppRunConfigVersionError(
        path.toString(),
        typed[AppRunConfigKeys.CONTRACT_VERSION].toString(),
      )
    }
    validator.validate(document, path.toString())
    val module = (typed[AppRunConfigKeys.MODULE] as? String)?.removePrefix(":")
    val variant =
      typed[AppRunConfigKeys.VARIANT] as? String ?: AppRunConfigFileKeys.DEFAULT_VARIANT
    return AppRunConfig(module, variant)
  }
}

private object AppRunConfigFileKeys {
  const val FILE_NAME = ".skill-bill/app-run.yaml"
  const val DEFAULT_VARIANT = "debug"
}
