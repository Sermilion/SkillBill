package skillbill.ports.config.model

import java.nio.file.Path

@JvmInline
value class ConfigFingerprint(
  val sha256Hex: String,
)

enum class ConfigDocumentFormat {
  JSON,
  YAML,
}

data class ConfigDocumentTree(
  private val entries: Map<String, Any?>,
) {
  fun value(key: String): Any? = entries[key]

  fun contains(key: String): Boolean = entries.containsKey(key)

  fun with(
    key: String,
    value: Any?,
  ): ConfigDocumentTree {
    val updated = LinkedHashMap(entries)
    updated[key] = value
    return ConfigDocumentTree(updated)
  }

  fun without(key: String): ConfigDocumentTree {
    val updated = LinkedHashMap(entries)
    updated.remove(key)
    return ConfigDocumentTree(updated)
  }

  fun orderedEntries(): List<Pair<String, Any?>> = entries.map { it.key to it.value }
}

data class ProjectConfigLocation(
  val target: Path,
  val readable: Path?,
)

sealed interface ConfigDocumentFileRead {
  data class Absent(val path: Path) : ConfigDocumentFileRead

  data class Malformed(val path: Path, val reason: String) : ConfigDocumentFileRead

  data class Present(
    val path: Path,
    val fingerprint: ConfigFingerprint,
    val tree: ConfigDocumentTree,
    val hasComments: Boolean,
  ) : ConfigDocumentFileRead
}

data class ConfigDocumentReplaceRequest(
  val target: Path,
  val baseline: Path,
  val expected: ConfigFingerprint?,
  val format: ConfigDocumentFormat,
  val tree: ConfigDocumentTree,
)

sealed interface ConfigDocumentReplaceResult {
  data class Written(val path: Path, val fingerprint: ConfigFingerprint) : ConfigDocumentReplaceResult

  data class ChangedOnDisk(val path: Path) : ConfigDocumentReplaceResult
}
