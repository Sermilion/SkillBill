package skillbill.config.model

private const val VALIDATION_GATE_KEY: String = "validation_gate"
private const val GRADLE_WRAPPER_KEY: String = "gradle_wrapper"

data class ValidationGateRepoConfig(
  val gradleWrapper: String? = null,
) {
  companion object {
    fun defaults(): ValidationGateRepoConfig = ValidationGateRepoConfig()
  }
}

sealed interface ValidationGateRepoConfigParse {
  data class Valid(val config: ValidationGateRepoConfig) : ValidationGateRepoConfigParse

  data class Invalid(
    val keyPath: String,
    val value: String,
    val reason: String,
  ) : ValidationGateRepoConfigParse
}

fun parseValidationGateRepoConfig(raw: Any?): ValidationGateRepoConfigParse =
  validationGateInvalid(raw) ?: ValidationGateRepoConfigParse.Valid(buildValidationGateConfig(raw))

internal fun parseGradleWrapperPath(raw: String?): String? {
  val trimmed = raw?.trim().orEmpty()
  if (trimmed.isEmpty()) return null
  val withForwardSlashes = trimmed.replace('\\', '/')
  val absolute =
    withForwardSlashes.startsWith("/") || withForwardSlashes.matches(Regex("^[A-Za-z]:/.*"))
  val normalized = withForwardSlashes.removePrefix("./")
  val segments = normalized.split('/').filter { segment -> segment.isNotEmpty() }
  val invalid =
    absolute ||
      normalized.isEmpty() ||
      segments.isEmpty() ||
      segments.any { segment -> segment == "." || segment == ".." }
  return if (invalid) null else segments.joinToString("/")
}

fun applyValidationGateGradleWrapper(
  argv: List<String>,
  gradleWrapper: String?,
): List<String> {
  val wrapper = gradleWrapper?.takeIf { path -> path.isNotBlank() } ?: return argv
  val head = argv.firstOrNull() ?: return argv
  if (head != "./gradlew" && head != "gradlew") return argv
  val projectDir = wrapper.substringBeforeLast('/', missingDelimiterValue = "")
  val rewrittenHead =
    if (projectDir.isNotEmpty()) {
      listOf(wrapper, "-p", projectDir)
    } else {
      listOf(wrapper)
    }
  return rewrittenHead + argv.drop(1)
}

private fun validationGateInvalid(raw: Any?): ValidationGateRepoConfigParse.Invalid? {
  val root = raw as? Map<*, *> ?: return invalidValidationGate(VALIDATION_GATE_KEY, raw, "must be a mapping.")
  val fields = root.entries.associate { (key, value) -> key.toString() to value }
  val unsupported = fields.entries.firstOrNull { (key, _) -> key !in VALIDATION_GATE_FIELDS }
  if (unsupported != null) {
    return invalidValidationGate(
      "$VALIDATION_GATE_KEY.${unsupported.key}",
      unsupported.value,
      "is not a supported validation_gate field.",
    )
  }
  if (!fields.containsKey(GRADLE_WRAPPER_KEY)) return null
  val rawWrapper = fields[GRADLE_WRAPPER_KEY]
  return when {
    rawWrapper !is String ->
      invalidValidationGate(
        "$VALIDATION_GATE_KEY.$GRADLE_WRAPPER_KEY",
        rawWrapper,
        "must be a non-blank repo-relative path string.",
      )

    parseGradleWrapperPath(rawWrapper) == null ->
      invalidValidationGate(
        "$VALIDATION_GATE_KEY.$GRADLE_WRAPPER_KEY",
        rawWrapper,
        "must be a non-blank repo-relative path without '..' segments.",
      )

    else -> null
  }
}

private fun buildValidationGateConfig(raw: Any?): ValidationGateRepoConfig {
  val root = checkNotNull(raw as? Map<*, *>) { "validated validation_gate config must be a mapping." }
  val fields = root.entries.associate { (key, value) -> key.toString() to value }
  if (!fields.containsKey(GRADLE_WRAPPER_KEY)) return ValidationGateRepoConfig.defaults()
  val rawWrapper = checkNotNull(fields[GRADLE_WRAPPER_KEY] as? String) { "validated wrapper must be a string." }
  return ValidationGateRepoConfig(gradleWrapper = parseGradleWrapperPath(rawWrapper))
}

private fun invalidValidationGate(
  keyPath: String,
  value: Any?,
  reason: String,
): ValidationGateRepoConfigParse.Invalid =
  ValidationGateRepoConfigParse.Invalid(
    keyPath = keyPath,
    value = value?.toString() ?: "null",
    reason = reason,
  )

private val VALIDATION_GATE_FIELDS: Set<String> = setOf(GRADLE_WRAPPER_KEY)
