package skillbill.infrastructure.host.system

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import me.tatarka.inject.annotations.Inject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.comments.CommentType
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.events.CommentEvent
import skillbill.contracts.JsonCodec
import skillbill.contracts.projectstore.LegacyProjectStatePaths
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
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

@Inject
class FileConfigDocumentFiles(
  private val diagnostics: RuntimeDiagnostics,
  private val projectStore: ProjectStorePort,
) : ConfigDocumentFiles {
  override fun <T> withLocks(
    paths: Collection<Path>,
    action: () -> T,
  ): T {
    return FileConfigDocumentLocks.withLocks(paths, action)
  }

  override fun projectConfigLockPaths(repoRoot: Path): List<Path> {
    val target = projectStore.resolve(repoRoot).configPath
    val legacy = repoRoot.resolve(LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY).resolve(REPO_LOCAL_CONFIG_FILE_NAME)
    return listOf(target, legacy)
  }

  override fun projectConfigLocation(repoRoot: Path): ProjectConfigLocation {
    val requestedTarget = projectStore.resolve(repoRoot).configPath
    return withLocks(projectConfigLockPaths(repoRoot)) {
      val target = FileConfigDocumentLocks.lockedPath(requestedTarget)
      val readable =
        try {
          readableRepoLocalConfigPath(diagnostics, repoRoot, target)
        } catch (error: IOException) {
          throw configDocumentIoException(target, "resolve", error)
        } catch (error: SecurityException) {
          throw configDocumentIoException(target, "resolve", error)
        }
      ProjectConfigLocation(
        target,
        readable,
        projectConfigLockPaths(repoRoot).map { FileConfigDocumentLocks.lockedPath(it) },
      )
    }
  }

  override fun read(
    path: Path,
    format: ConfigDocumentFormat,
  ): ConfigDocumentFileRead =
    withLocks(listOf(path)) {
      val actualPath = FileConfigDocumentLocks.lockedPath(path)
      val bytes = readBytesOrAbsent(actualPath, "read") ?: return@withLocks ConfigDocumentFileRead.Absent(actualPath)
      val fingerprint = fingerprint(bytes)
      val text =
        try {
          Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        } catch (_: CharacterCodingException) {
          return@withLocks ConfigDocumentFileRead.Malformed(
            actualPath,
            fingerprint,
            "Configuration is not valid UTF-8.",
          )
        }
      val parsed =
        when (format) {
          ConfigDocumentFormat.JSON -> readJson(text)
          ConfigDocumentFormat.YAML -> readYaml(actualPath, text)
        }
      if (parsed is ParsedTree.Invalid) {
        return@withLocks ConfigDocumentFileRead.Malformed(actualPath, fingerprint, parsed.reason, parsed.keyPath)
      }
      val hasComments =
        if (format == ConfigDocumentFormat.YAML) {
          try {
            yamlHasComments(text)
          } catch (error: YAMLException) {
            return@withLocks ConfigDocumentFileRead.Malformed(
              actualPath,
              fingerprint,
              "Configuration comments could not be inspected: ${error.message.orEmpty()}",
            )
          }
        } else {
          false
        }
      val tree = (parsed as ParsedTree.Valid).entries
      ConfigDocumentFileRead.Present(
        actualPath,
        fingerprint,
        ConfigDocumentTree(tree),
        hasComments,
      )
    }

  private fun readJson(text: String): ParsedTree {
    val parsed =
      JsonCodec.parseObjectOrNull(text)
        ?: return ParsedTree.Invalid("Configuration is not a valid JSON object.")
    JsonCodec.duplicateObjectKeyPath(text)?.let { keyPath ->
      return ParsedTree.Invalid(duplicateKeyReason(keyPath), keyPath)
    }
    return try {
      ParsedTree.Valid(
        JsonCodec.anyToStringAnyMap(JsonCodec.machineConfigJsonElementToValue(parsed))
          ?: return ParsedTree.Invalid("Configuration is not a valid JSON object."),
      )
    } catch (_: NumberFormatException) {
      ParsedTree.Invalid("Configuration contains a number that cannot be represented.")
    }
  }

  private fun readYaml(
    path: Path,
    text: String,
  ): ParsedTree {
    yamlAdmissionFailure(text)?.let { failure ->
      return ParsedTree.Invalid(failure.reason, failure.keyPath)
    }
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

  private fun yamlHasComments(text: String): Boolean {
    val options = LoaderOptions().apply { setProcessComments(true) }
    val events = Yaml(options).parse(StringReader(text)).iterator()
    var found = false
    while (events.hasNext()) {
      val event = events.next()
      if (
        event is CommentEvent &&
        (event.commentType == CommentType.BLOCK || event.commentType == CommentType.IN_LINE)
      ) {
        found = true
      }
    }
    return found
  }

  override fun replace(request: ConfigDocumentReplaceRequest): ConfigDocumentReplaceResult {
    try {
      return withLocks(listOf(request.target, request.baseline)) {
        val target = FileConfigDocumentLocks.lockedPath(request.target)
        val baseline = FileConfigDocumentLocks.lockedPath(request.baseline)
        val bytes = serialize(target, request.tree, request.format)
        val parent =
          target.parent ?: throw configDocumentIoException(
            target,
            "write",
            IOException("Target has no parent directory."),
          )
        Files.createDirectories(parent)
        if (target != baseline && readBytesOrAbsent(target, "replace") != null) {
          return@withLocks ConfigDocumentReplaceResult.ChangedOnDisk(target)
        }
        val currentFingerprint = currentFingerprint(baseline)
        if (currentFingerprint != request.expected) return@withLocks ConfigDocumentReplaceResult.ChangedOnDisk(target)
        val temp = Files.createTempFile(parent, ".${target.fileName}-", ".tmp")
        try {
          Files.write(temp, bytes)
          Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
          Files.deleteIfExists(temp)
        }
        ConfigDocumentReplaceResult.Written(target, fingerprint(bytes))
      }
    } catch (error: IOException) {
      throw configDocumentIoException(request.target, "replace", error)
    } catch (error: SecurityException) {
      throw configDocumentIoException(request.target, "replace", error)
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

  private val yamlMapper: YAMLMapper by lazy {
    YAMLMapper.builder()
      .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build()
  }
}

internal fun duplicateKeyReason(keyPath: String): String =
  "Configuration repeats the key '$keyPath'. Remove the duplicate; saving would keep only one of the values."

fun configDocumentIoException(
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

  data class Invalid(val reason: String, val keyPath: String? = null) : ParsedTree
}
