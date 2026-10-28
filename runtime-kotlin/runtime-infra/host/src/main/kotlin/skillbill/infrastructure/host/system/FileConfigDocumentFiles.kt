package skillbill.infrastructure.host.system

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.ports.config.ConfigDocumentFiles
import skillbill.ports.config.model.ConfigDocumentFileRead
import skillbill.ports.config.model.ConfigDocumentFormat
import skillbill.ports.config.model.ConfigDocumentReplaceRequest
import skillbill.ports.config.model.ConfigDocumentReplaceResult
import skillbill.ports.config.model.ConfigDocumentTree
import skillbill.ports.config.model.ConfigFingerprint
import skillbill.ports.config.model.ProjectConfigLocation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import java.io.IOException
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

@Inject
class FileConfigDocumentFiles(
  private val diagnostics: RuntimeDiagnostics,
  private val projectStore: ProjectStorePort,
) : ConfigDocumentFiles {
  override fun projectConfigLocation(repoRoot: Path): ProjectConfigLocation {
    val target = projectStore.resolve(repoRoot).configPath
    val readable =
      try {
        readableRepoLocalConfigPath(diagnostics, repoRoot, target)
      } catch (error: IOException) {
        throw configDocumentIoException(target, "resolve", error)
      } catch (error: SecurityException) {
        throw configDocumentIoException(target, "resolve", error)
      }
    return ProjectConfigLocation(target, readable)
  }

  override fun read(
    path: Path,
    format: ConfigDocumentFormat,
  ): ConfigDocumentFileRead {
    val bytes = readBytesOrAbsent(path, "read") ?: return ConfigDocumentFileRead.Absent(path)
    val text =
      try {
        Charsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString()
      } catch (_: CharacterCodingException) {
        return ConfigDocumentFileRead.Malformed(path, "Configuration is not valid UTF-8.")
      }
    val parsed =
      when (format) {
        ConfigDocumentFormat.JSON -> readJson(text)
        ConfigDocumentFormat.YAML -> readYaml(path, text)
      }
    if (parsed is ParsedTree.Invalid) return ConfigDocumentFileRead.Malformed(path, parsed.reason)
    val tree = (parsed as ParsedTree.Valid).entries
    return ConfigDocumentFileRead.Present(
      path,
      fingerprint(bytes),
      ConfigDocumentTree(tree),
      format == ConfigDocumentFormat.YAML && text.lineSequence().any { it.trimStart().startsWith('#') },
    )
  }

  private fun readJson(text: String): ParsedTree {
    val parsed =
      JsonCodec.parseObjectOrNull(text)
        ?: return ParsedTree.Invalid("Configuration is not a valid JSON object.")
    return try {
      ParsedTree.Valid(jsonObjectToOrderedMap(parsed))
    } catch (_: NumberFormatException) {
      ParsedTree.Invalid("Configuration contains a number that cannot be represented.")
    }
  }

  private fun readYaml(
    path: Path,
    text: String,
  ): ParsedTree {
    val root =
      try {
        yamlMapper.factory.createParser(text).use { parser ->
          if (parser.nextToken() == null) {
            emptyMap<String, Any?>()
          } else {
            val value = yamlMapper.readValue(parser, Any::class.java)
            if (parser.nextToken() != null) {
              return ParsedTree.Invalid("Configuration must contain a single YAML document.")
            }
            value
          }
        }
      } catch (error: JacksonException) {
        return ParsedTree.Invalid("Configuration is not a valid YAML mapping: ${error.originalMessage}")
      } catch (error: IOException) {
        throw configDocumentIoException(path, "read", error)
      } catch (error: SecurityException) {
        throw configDocumentIoException(path, "read", error)
      }
    return yamlEntries(root)
  }

  private fun yamlEntries(root: Any?): ParsedTree {
    if (root == null) return ParsedTree.Valid(emptyMap())
    if (root !is Map<*, *>) return ParsedTree.Invalid("Configuration must contain a YAML mapping.")
    val entries = LinkedHashMap<String, Any?>()
    for ((key, value) in root) {
      if (key !is String) return ParsedTree.Invalid("Configuration YAML mapping keys must be strings.")
      entries[key] = value
    }
    return ParsedTree.Valid(entries)
  }

  override fun replace(request: ConfigDocumentReplaceRequest): ConfigDocumentReplaceResult {
    val bytes = serialize(request.target, request.tree, request.format)
    val target = request.target.toAbsolutePath().normalize()
    val baseline = request.baseline.toAbsolutePath().normalize()
    val parent =
      target.parent ?: throw configDocumentIoException(target, "write", IOException("Target has no parent directory."))
    val lock = pathLocks.computeIfAbsent(target) { ReentrantLock() }
    try {
      Files.createDirectories(parent)
      lock.lock()
      try {
        val lockPath = target.resolveSibling("${target.fileName}.lock")
        FileChannel.open(lockPath, CREATE, WRITE).use { channel ->
          channel.lock().use {
            if (target != baseline && readBytesOrAbsent(target, "replace") != null) {
              return ConfigDocumentReplaceResult.ChangedOnDisk(request.target)
            }
            val currentFingerprint = currentFingerprint(baseline)
            if (currentFingerprint != request.expected) return ConfigDocumentReplaceResult.ChangedOnDisk(request.target)
            val temp = Files.createTempFile(parent, ".${target.fileName}-", ".tmp")
            try {
              Files.write(temp, bytes)
              Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
              Files.deleteIfExists(temp)
            }
            return ConfigDocumentReplaceResult.Written(request.target, fingerprint(bytes))
          }
        }
      } finally {
        lock.unlock()
      }
    } catch (error: IOException) {
      throw configDocumentIoException(target, "replace", error)
    } catch (error: SecurityException) {
      throw configDocumentIoException(target, "replace", error)
    }
  }

  private fun serialize(
    path: Path,
    tree: ConfigDocumentTree,
    format: ConfigDocumentFormat,
  ): ByteArray {
    val document =
      LinkedHashMap<String, Any?>().apply {
        tree.orderedEntries().forEach { (key, value) ->
          put(key, value)
        }
      }
    return try {
      when (format) {
        ConfigDocumentFormat.JSON -> (JsonCodec.mapToJsonString(document) + "\n").toByteArray(Charsets.UTF_8)
        ConfigDocumentFormat.YAML -> yamlMapper.writeValueAsBytes(document)
      }
    } catch (error: IOException) {
      throw configDocumentIoException(path, "serialize", error)
    } catch (error: SecurityException) {
      throw configDocumentIoException(path, "serialize", error)
    }
  }

  private fun fingerprint(bytes: ByteArray): ConfigFingerprint =
    ConfigFingerprint(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

  private fun currentFingerprint(path: Path): ConfigFingerprint? =
    readBytesOrAbsent(path, "replace")?.let(::fingerprint)

  private fun readBytesOrAbsent(
    path: Path,
    operation: String,
  ): ByteArray? =
    try {
      Files.readAllBytes(path)
    } catch (_: NoSuchFileException) {
      null
    } catch (error: IOException) {
      throw configDocumentIoException(path, operation, error)
    } catch (error: SecurityException) {
      throw configDocumentIoException(path, operation, error)
    }

  private fun jsonObjectToOrderedMap(value: JsonObject): Map<String, Any?> =
    value.entries.associateTo(LinkedHashMap()) { (key, entry) -> key to jsonValuePreservingExponentNumbers(entry) }

  private fun jsonValuePreservingExponentNumbers(value: JsonElement): Any? =
    when (value) {
      JsonNull -> null
      is JsonObject -> jsonObjectToOrderedMap(value)
      is JsonArray -> value.map(::jsonValuePreservingExponentNumbers)
      is JsonPrimitive -> {
        val content = value.content
        if (!value.isString && value.booleanOrNull == null && content.contains('e', ignoreCase = true)) {
          BigDecimal(content)
        } else {
          JsonCodec.jsonElementToValue(value)
        }
      }
    }

  private val yamlMapper: YAMLMapper by lazy {
    YAMLMapper.builder()
      .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .build()
  }

  private companion object {
    val pathLocks = ConcurrentHashMap<Path, ReentrantLock>()
  }
}

internal fun configDocumentIoException(
  path: Path,
  operation: String,
  cause: Throwable,
) = SkillBillRuntimeException(
  InstallFailureCode.REPO_LOCAL_CONFIG_FAILURE,
  "Config document at '$path' cannot be $operation.",
  cause,
)

private sealed interface ParsedTree {
  data class Valid(val entries: Map<String, Any?>) : ParsedTree

  data class Invalid(val reason: String) : ParsedTree
}
