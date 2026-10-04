package skillbill.infrastructure.skills.externaladdon

import org.yaml.snakeyaml.Yaml
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.externalAddonOverlay
import java.nio.file.Files
import java.nio.file.Path

internal fun manifestStructureError(
  slug: String,
  field: String,
  error: ClassCastException,
): SkillBillRuntimeException {
  val message = "Installed platform.yaml for '$slug' has unexpected structure in '$field': ${error.message}"
  return externalAddonOverlay(message, error)
}

internal fun manifestStructureError(
  slug: String,
  field: String,
  expected: String,
  actual: Any?,
): SkillBillRuntimeException {
  val found = actual?.javaClass?.simpleName ?: "null"
  val message =
    "Installed platform.yaml for '$slug' has unexpected structure in '$field': " +
      "expected $expected but found $found."
  return externalAddonOverlay(message)
}

internal fun readRawManifest(manifestPath: Path): MutableMap<String, Any?> {
  val raw =
    Yaml().load<Any?>(Files.readString(manifestPath)) as? Map<*, *>
      ?: throw externalAddonOverlay("Installed platform manifest '$manifestPath' must be a YAML mapping.")
  val root = linkedMapOf<String, Any?>()
  raw.forEach { (k, v) ->
    root[k as String] =
      when (v) {
        is Map<*, *> -> linkedMapOfFrom(v)
        else -> v
      }
  }
  return root
}

internal fun linkedMapOfFrom(map: Map<*, *>): MutableMap<String, Any?> {
  val out = linkedMapOf<String, Any?>()
  map.forEach { (k, v) ->
    out[k as String] =
      when (v) {
        is Map<*, *> -> linkedMapOfFrom(v)
        is List<*> ->
          v.mapTo(mutableListOf()) { item ->
            when (item) {
              is Map<*, *> -> linkedMapOfFrom(item)
              else -> item
            }
          }
        else -> v
      }
  }
  return out
}
