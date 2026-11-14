package skillbill.infrastructure.skills.install.mcp

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.error.core.SkillBillRuntimeException
import skillbill.install.model.McpRegistrationFailureCode
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal object OpenCodeConfigFiles {
  private const val MAX_CONFIG_CHARS = 1_048_576
  private const val READ_BUFFER_CHARS = 8192

  val mapper =
    ObjectMapper(
      JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .build(),
    )

  fun observeSource(path: Path): Boolean? {
    val entry = inspect(path) ?: return null
    val attributes =
      if (entry.isSymbolicLink) {
        io(path, McpRegistrationFailureCode.OPENCODE_CONFIG_INVALID, "inspect config symlink") {
          Files.readAttributes(path, BasicFileAttributes::class.java)
        }
      } else {
        entry
      }
    val readable =
      io(path, McpRegistrationFailureCode.OPENCODE_CONFIG_INVALID, "inspect config readability") {
        Files.isReadable(path)
      }
    if (!attributes.isRegularFile || !readable) invalid(path)
    return true
  }

  private fun inspect(path: Path): BasicFileAttributes? =
    io(path, McpRegistrationFailureCode.OPENCODE_CONFIG_INVALID, "inspect config source") {
      try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
      } catch (_: NoSuchFileException) {
        null
      }
    }

  fun read(path: Path): String =
    if (observeSource(path) != null) {
      io(path, McpRegistrationFailureCode.OPENCODE_CONFIG_INVALID, "read config") {
        Files.newBufferedReader(path).use { reader ->
          val content = StringBuilder()
          val buffer = CharArray(READ_BUFFER_CHARS)
          var count = reader.read(buffer)
          while (count != -1) {
            if (content.length + count > MAX_CONFIG_CHARS) invalid(path)
            content.append(buffer, 0, count)
            count = reader.read(buffer)
          }
          content.toString()
        }
      }
    } else {
      "{}\n"
    }

  fun publish(
    path: Path,
    existed: Boolean,
    original: String,
    replacement: String,
    publishFile: (Path, String) -> Unit,
  ): Boolean {
    val unchanged = if (existed) observeSource(path) != null && read(path) == original else observeSource(path) == null
    if (!unchanged) return false
    val wasLink = Files.isSymbolicLink(path)
    val destination = destination(path, wasLink)
    if (!sameDestination(path, wasLink, destination) || (existed && read(path) != original)) return false
    io(path, McpRegistrationFailureCode.OPENCODE_CONFIG_WRITE_FAILED, "publish config") {
      publishFile(destination, replacement)
    }
    return true
  }

  private fun destination(
    path: Path,
    wasLink: Boolean,
  ): Path =
    if (wasLink) {
      io(path, McpRegistrationFailureCode.OPENCODE_CONFIG_WRITE_FAILED, "resolve config symlink") {
        path.toRealPath().also { if (!Files.isRegularFile(it)) invalid(path) }
      }
    } else {
      path
    }

  private fun sameDestination(
    path: Path,
    wasLink: Boolean,
    destination: Path,
  ): Boolean =
    io(path, McpRegistrationFailureCode.OPENCODE_CONFIG_WRITE_FAILED, "recheck config destination") {
      Files.isSymbolicLink(path) == wasLink && (!wasLink || path.toRealPath() == destination)
    }

  fun parse(
    source: String,
    path: Path,
  ): JsonNode {
    if (source.isBlank() || source.length > MAX_CONFIG_CHARS) invalid(path)
    return io(path, McpRegistrationFailureCode.OPENCODE_CONFIG_INVALID, "parse config") {
      mapper.factory.createParser(source).use { parser ->
        val parsed = mapper.readTree<JsonNode>(parser) ?: invalid(path)
        if (parser.nextToken() != null || !parsed.isObject) invalid(path)
        parsed
      }
    }
  }

  fun invalid(
    path: Path,
    cause: Throwable? = null,
  ): Nothing =
    throw SkillBillRuntimeException(
      McpRegistrationFailureCode.OPENCODE_CONFIG_INVALID,
      "Invalid OpenCode config object at '$path'; original bytes were preserved. Correct the configuration and retry.",
      cause,
    )

  private inline fun <T> io(
    path: Path,
    code: McpRegistrationFailureCode,
    operation: String,
    action: () -> T,
  ): T =
    try {
      action()
    } catch (error: IOException) {
      propagateInterruption(error)
      throw SkillBillRuntimeException(
        code,
        "Could not $operation at '$path'; original configuration remains available for recovery. " +
          "Correct access and retry.",
        error,
      )
    } catch (error: SecurityException) {
      throw SkillBillRuntimeException(
        code,
        "Could not $operation at '$path'; original configuration remains available for recovery. " +
          "Correct access and retry.",
        error,
      )
    }

  private fun propagateInterruption(error: IOException) {
    if (error is InterruptedIOException || error is ClosedByInterruptException) throw error
  }
}
