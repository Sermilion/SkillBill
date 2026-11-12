package skillbill.infrastructure.contracts

import com.fasterxml.jackson.core.JsonProcessingException
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.apprun.APP_RUN_CONFIG_CONTRACT_VERSION
import skillbill.error.apprun.invalidAppRunConfigError
import skillbill.infrastructure.contracts.locator.apprun.AppRunConfigSchemaPaths
import skillbill.ports.apprun.AppRunConfigContractValidator

@Inject
class AppRunConfigSchemaValidator : AppRunConfigContractValidator {
  override fun validate(
    document: String,
    sourceLabel: String,
  ) {
    val instance =
      try {
        ClasspathContractSchemaLoader.sharedYamlMapper().readTree(document)
      } catch (failure: JsonProcessingException) {
        throw invalidAppRunConfigError(sourceLabel, "<root>", "is not valid YAML", failure)
      } ?: throw invalidAppRunConfigError(sourceLabel, "<root>", "must be a YAML mapping")
    val errors = ClasspathContractSchemaLoader.validate(appRunConfigSchema(), instance)
    val first = errors.sortedBy { it.instanceLocation?.toString().orEmpty() }.firstOrNull() ?: return
    val field = appRunConfigFieldPath(first.instanceLocation?.toString().orEmpty())
    throw invalidAppRunConfigError(sourceLabel, field, first.message.orEmpty())
  }
}

private fun appRunConfigFieldPath(location: String): String =
  when {
    location.isBlank() || location == "/" || location == "\$" -> "<root>"
    location.startsWith("\$.") -> location.removePrefix("\$.").replace('/', '.')
    location.startsWith("\$") ->
      location.removePrefix("\$").trimStart('.').replace('/', '.').ifBlank { "<root>" }
    else -> location.trimStart('/').replace('/', '.').ifBlank { "<root>" }
  }

private fun appRunConfigSchema() =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = AppRunConfigSchemaPaths.CLASSPATH_RESOURCE,
      classLoader = AppRunConfigSchemaValidator::class.java.classLoader,
      classpathResource = AppRunConfigSchemaPaths.CLASSPATH_RESOURCE,
      missingResource = {
        invalidAppRunConfigError(AppRunConfigSchemaPaths.CLASSPATH_RESOURCE, "<root>", "schema resource is missing")
      },
      processingFailure = { failure ->
        invalidAppRunConfigError(
          AppRunConfigSchemaPaths.CLASSPATH_RESOURCE,
          "<root>",
          failure.message.orEmpty(),
          failure,
        )
      },
      loadFailureLogger = {},
      expectedSchemaId = AppRunConfigSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = APP_RUN_CONFIG_CONTRACT_VERSION,
      identityFailure = { reason ->
        invalidAppRunConfigError(AppRunConfigSchemaPaths.CLASSPATH_RESOURCE, "<root>", reason)
      },
    ),
  )
