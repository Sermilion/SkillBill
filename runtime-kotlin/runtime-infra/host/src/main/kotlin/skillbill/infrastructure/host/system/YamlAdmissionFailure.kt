package skillbill.infrastructure.host.system

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.CommentEvent
import org.yaml.snakeyaml.events.DocumentEndEvent
import org.yaml.snakeyaml.events.DocumentStartEvent
import org.yaml.snakeyaml.events.Event
import org.yaml.snakeyaml.events.MappingEndEvent
import org.yaml.snakeyaml.events.MappingStartEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.events.SequenceEndEvent
import org.yaml.snakeyaml.events.SequenceStartEvent
import org.yaml.snakeyaml.events.StreamEndEvent
import org.yaml.snakeyaml.events.StreamStartEvent
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.resolver.Resolver
import java.io.StringReader

internal data class YamlAdmissionFailure(
  val keyPath: String?,
  val reason: String,
)

internal fun yamlAdmissionFailure(text: String): YamlAdmissionFailure? =
  try {
    YamlConfigAdmission(text).inspect()
  } catch (error: YAMLException) {
    YamlAdmissionFailure(null, "Configuration is not valid YAML: ${error.message.orEmpty()}")
  }

private class YamlConfigAdmission(text: String) {
  private val resolver = Resolver()
  private val events =
    Yaml(LoaderOptions().apply { setProcessComments(true) })
      .parse(StringReader(text))
      .iterator()

  fun inspect(): YamlAdmissionFailure? {
    if (nextEvent() !is StreamStartEvent) {
      return YamlAdmissionFailure(null, "Configuration is not a valid YAML stream.")
    }
    val first = nextEvent()
    if (first is StreamEndEvent) return null
    if (first !is DocumentStartEvent) {
      return YamlAdmissionFailure(null, "Configuration must contain a single YAML document.")
    }
    return inspectDocument(first)
  }

  private fun inspectDocument(first: DocumentStartEvent): YamlAdmissionFailure? {
    val root = nextEvent()
    return when {
      root is DocumentEndEvent -> inspectEmptyDocument(first, root)
      root is AliasEvent -> failure(null, root, "YAML aliases cannot be preserved when this document is rewritten.")
      root is ScalarEvent && isNullScalar(root) -> inspectNullDocument(first, root)
      root !is MappingStartEvent -> failure(null, root, "Configuration root must be a YAML mapping.")
      else -> inspectMappingDocument(root)
    }
  }

  private fun inspectEmptyDocument(
    first: DocumentStartEvent,
    root: DocumentEndEvent,
  ): YamlAdmissionFailure? {
    val streamEnd = nextEvent()
    return if (!first.explicit && !root.explicit && streamEnd is StreamEndEvent) {
      null
    } else {
      failure(null, root, "An empty YAML document has a null root and cannot be rewritten as a mapping.")
    }
  }

  private fun inspectNullDocument(
    first: DocumentStartEvent,
    root: ScalarEvent,
  ): YamlAdmissionFailure? {
    val implicitEmpty = root.value.isEmpty() && root.tag == null && !root.implicit.bothFalse()
    if (implicitEmpty && !first.explicit) {
      val documentEnd = nextEvent()
      val streamEnd = nextEvent()
      if (documentEnd is DocumentEndEvent && !documentEnd.explicit && streamEnd is StreamEndEvent) return null
    }
    return failure(null, root, "A YAML null root cannot be rewritten as an empty mapping.")
  }

  private fun inspectMappingDocument(root: MappingStartEvent): YamlAdmissionFailure? {
    inspectNode(root, "")?.let { return it }
    val documentEnd = nextEvent()
    if (documentEnd !is DocumentEndEvent) {
      return failure(null, documentEnd, "Configuration must contain a single YAML document.")
    }
    val streamEnd = nextEvent()
    return if (streamEnd is StreamEndEvent) {
      null
    } else {
      failure(null, streamEnd, "Configuration must contain a single YAML document.")
    }
  }

  private fun inspectNode(
    event: Event?,
    path: String,
  ): YamlAdmissionFailure? =
    when (event) {
      null -> failure(path, null, "Configuration ended before the YAML value was complete.")
      is AliasEvent -> failure(path, event, "YAML aliases cannot be preserved when this document is rewritten.")
      is ScalarEvent -> explicitTagFailure(event, path)
      is MappingStartEvent -> inspectMapping(event, path)
      is SequenceStartEvent -> inspectSequence(event, path)
      else -> failure(path, event, "Configuration contains an unsupported YAML value.")
    }

  private fun inspectMapping(
    start: MappingStartEvent,
    path: String,
  ): YamlAdmissionFailure? {
    explicitTagFailure(start, path)?.let { return it }
    val keys = HashSet<String>()
    while (true) {
      val keyEvent = nextEvent()
      if (keyEvent is MappingEndEvent) return null
      inspectMappingKey(keyEvent, path, keys)?.let { return it }
      val keyPath = joinPath(path, (keyEvent as ScalarEvent).value)
      inspectNode(nextEvent(), keyPath)?.let { return it }
    }
  }

  private fun inspectMappingKey(
    event: Event?,
    path: String,
    keys: MutableSet<String>,
  ): YamlAdmissionFailure? =
    when (event) {
      is AliasEvent -> failure(path, event, "YAML aliases cannot be preserved when this document is rewritten.")
      !is ScalarEvent -> failure(path, event, "Configuration YAML mapping keys must be strings.")
      else -> inspectScalarKey(event, path, keys)
    }

  private fun inspectScalarKey(
    event: ScalarEvent,
    path: String,
    keys: MutableSet<String>,
  ): YamlAdmissionFailure? {
    val keyPath = joinPath(path, event.value)
    val tag = resolvedScalarTag(event)
    val tagFailure = explicitTagFailure(event, keyPath)
    return when {
      tag == Tag.MERGE.value ->
        failure(
          keyPath,
          event,
          "YAML merge keys cannot be preserved when this document is rewritten.",
        )
      tagFailure != null -> tagFailure
      tag != Tag.STR.value -> failure(keyPath, event, "Configuration YAML mapping keys must be strings.")
      !keys.add(event.value) -> failure(keyPath, event, duplicateKeyReason(keyPath))
      else -> null
    }
  }

  private fun inspectSequence(
    start: SequenceStartEvent,
    path: String,
  ): YamlAdmissionFailure? {
    explicitTagFailure(start, path)?.let { return it }
    var index = 0
    while (true) {
      val value = nextEvent()
      if (value is SequenceEndEvent) return null
      inspectNode(value, "$path[$index]")?.let { return it }
      index++
    }
  }

  private fun explicitTagFailure(
    event: Event,
    path: String,
  ): YamlAdmissionFailure? {
    val tag: String?
    val explicit: Boolean
    when (event) {
      is ScalarEvent -> {
        tag = event.tag
        explicit = event.implicit.bothFalse()
      }
      is CollectionStartEvent -> {
        tag = event.tag
        explicit = !event.implicit
      }
      else -> return null
    }
    if (!explicit || tag == null || tag in rewriteSafeTags) return null
    return failure(path, event, "Explicit YAML tag '$tag' cannot be preserved when this document is rewritten.")
  }

  private fun resolvedScalarTag(event: ScalarEvent): String =
    event.tag ?: resolver.resolve(
      NodeId.scalar,
      event.value,
      event.implicit.canOmitTagInPlainScalar(),
    ).value

  private fun isNullScalar(event: ScalarEvent): Boolean = resolvedScalarTag(event) == Tag.NULL.value

  private fun nextEvent(): Event? {
    while (events.hasNext()) {
      val event = events.next()
      if (event !is CommentEvent) return event
    }
    return null
  }

  private fun failure(
    keyPath: String?,
    event: Event?,
    reason: String,
  ): YamlAdmissionFailure {
    val path = keyPath?.takeIf(String::isNotEmpty)
    val mark = event?.startMark
    return YamlAdmissionFailure(path, "$reason${mark?.let(::location).orEmpty()}")
  }

  private fun location(mark: Mark): String = " at line ${mark.line + 1}, column ${mark.column + 1}."

  private fun joinPath(
    parent: String,
    key: String,
  ): String = if (parent.isEmpty()) key else "$parent.$key"

  private companion object {
    val rewriteSafeTags =
      setOf(
        Tag.STR.value,
        Tag.INT.value,
        Tag.FLOAT.value,
        Tag.BOOL.value,
        Tag.NULL.value,
        Tag.MAP.value,
        Tag.SEQ.value,
      )
  }
}
