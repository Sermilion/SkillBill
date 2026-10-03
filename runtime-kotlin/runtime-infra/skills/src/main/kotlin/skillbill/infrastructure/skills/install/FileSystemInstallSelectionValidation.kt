package skillbill.infrastructure.skills.install

import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.error.shellcontent.malformedInstallSelectionRecordError
import java.nio.file.Path

internal fun requireExactKeys(
  path: Path,
  actualKeys: Set<String>,
  expectedKeys: Set<String>,
  objectName: String,
) {
  requireNoUnknownKeys(path, actualKeys, expectedKeys, objectName)
  val missingKeys = expectedKeys - actualKeys
  if (missingKeys.isNotEmpty()) {
    throw malformedInstallSelection(path, "Object '$objectName' is missing keys: ${missingKeys.sorted()}.")
  }
}

internal fun Map<String, Any?>.requireObject(
  path: Path,
  key: String,
): Map<String, Any?> =
  JsonCodec.anyToStringAnyMap(get(key))
    ?: throw malformedInstallSelection(path, "Field '$key' must be an object.")

internal fun Map<String, Any?>.requireString(
  path: Path,
  key: String,
): String =
  get(key) as? String
    ?: throw malformedInstallSelection(path, "Field '$key' must be a string.")

internal fun Map<String, Any?>.requireStringList(
  path: Path,
  key: String,
): List<String> {
  val entries =
    get(key) as? List<*>
      ?: throw malformedInstallSelection(path, "Field '$key' must be an array of strings.")
  return entries.map { entry ->
    entry as? String
      ?: throw malformedInstallSelection(path, "Field '$key' must be an array of strings.")
  }
}

internal fun Map<String, Any?>.requireBoolean(
  path: Path,
  key: String,
): Boolean =
  get(key) as? Boolean
    ?: throw malformedInstallSelection(path, "Field '$key' must be a boolean.")

internal fun Throwable.toMalformedInstallSelection(path: Path): SkillBillRuntimeException =
  (this as? SkillBillRuntimeException)
    ?.takeIf { it.code == InstallFailureCode.MALFORMED_INSTALL_SELECTION_RECORD }
    ?: malformedInstallSelection(path, message.orEmpty(), this)

internal fun malformedInstallSelection(
  path: Path,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  malformedInstallSelectionRecordError(
    path = path.toString(),
    reason = reason.ifBlank { "No reason provided." },
    cause = cause,
  )

private fun requireNoUnknownKeys(
  path: Path,
  actualKeys: Set<String>,
  expectedKeys: Set<String>,
  objectName: String,
) {
  val unknownKeys = actualKeys - expectedKeys
  if (unknownKeys.isNotEmpty()) {
    throw malformedInstallSelection(path, "Object '$objectName' contains unknown keys: ${unknownKeys.sorted()}.")
  }
}
