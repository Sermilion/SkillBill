package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.ports.featuretask.PhaseInstructionFileSource
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.DirectoryIteratorException
import java.nio.file.Path
import java.security.MessageDigest

sealed interface PhaseProjectInstructionsResolution {
  data class Injected(
    val relativePath: String,
    val content: String,
    val sha256: String,
  ) : PhaseProjectInstructionsResolution

  data class Skipped(
    val relativePath: String,
    val reason: String,
  ) : PhaseProjectInstructionsResolution
}

internal object AbsentPhaseInstructionFileSource : PhaseInstructionFileSource {
  override fun markdownFileNames(directory: Path): List<String>? = null

  override fun regularFileSize(file: Path): Long? = null

  override fun readBytes(file: Path): ByteArray = ByteArray(0)
}

data class PhaseProjectInstructionsOutcome(
  val injected: PhaseProjectInstructionsResolution.Injected? = null,
  val skipped: List<PhaseProjectInstructionsResolution.Skipped> = emptyList(),
)

object PhaseProjectInstructions {
  const val MAX_BYTES: Int = 16 * 1024
  const val PHASES_DIR: String = ".skill-bill/phases"
  private const val MARKDOWN_SUFFIX = ".md"
  private const val KIB_BYTES = 1024
  private val ALIASES: Map<String, String> = PhaseSlot.stepAliases
  private val knownPhaseIds: Set<String> = PhaseSlot.entries.flatMap(PhaseSlot::steps).toSet()

  fun resolve(
    source: PhaseInstructionFileSource,
    repoRoot: Path,
    phaseId: String,
  ): PhaseProjectInstructionsOutcome =
    try {
      resolveFromDirectory(source, repoRoot.resolve(PHASES_DIR), phaseId)
    } catch (error: IOException) {
      listingFailure(error)
    } catch (error: UncheckedIOException) {
      listingFailure(error)
    } catch (error: DirectoryIteratorException) {
      listingFailure(error)
    } catch (error: SecurityException) {
      listingFailure(error)
    }

  private fun resolveFromDirectory(
    source: PhaseInstructionFileSource,
    directory: Path,
    phaseId: String,
  ): PhaseProjectInstructionsOutcome {
    val stems =
      source.markdownFileNames(directory)
        ?.map { it.removeSuffix(MARKDOWN_SUFFIX) }
        ?.sorted()
        ?: return PhaseProjectInstructionsOutcome()
    val aliasStems = stems.filter { ALIASES[it] == phaseId }
    val chosen = if (phaseId in stems) phaseId else aliasStems.firstOrNull()
    val skipped =
      stems.filter { it !in knownPhaseIds && it !in ALIASES }
        .map { skippedEntry(it, "unknown phase id '$it'") } +
        aliasStems.filter { it != chosen }
          .map { skippedEntry(it, "shadowed by $PHASES_DIR/$chosen$MARKDOWN_SUFFIX") }
    return when (val resolution = chosen?.let { readInstructions(source, directory, it) }) {
      is PhaseProjectInstructionsResolution.Injected -> PhaseProjectInstructionsOutcome(resolution, skipped)
      is PhaseProjectInstructionsResolution.Skipped -> PhaseProjectInstructionsOutcome(null, skipped + resolution)
      null -> PhaseProjectInstructionsOutcome(null, skipped)
    }
  }

  private fun readInstructions(
    source: PhaseInstructionFileSource,
    directory: Path,
    stem: String,
  ): PhaseProjectInstructionsResolution? {
    val relativePath = "$PHASES_DIR/$stem$MARKDOWN_SUFFIX"
    val path = directory.resolve("$stem$MARKDOWN_SUFFIX")
    return try {
      val size = source.regularFileSize(path)
      when {
        size == null -> PhaseProjectInstructionsResolution.Skipped(relativePath, "unreadable: not a regular file")
        size > MAX_BYTES ->
          PhaseProjectInstructionsResolution.Skipped(
            relativePath,
            "exceeds ${MAX_BYTES / KIB_BYTES} KiB cap ($size bytes)",
          )
        else -> decodeInstructions(relativePath, source.readBytes(path))
      }
    } catch (error: IOException) {
      PhaseProjectInstructionsResolution.Skipped(relativePath, "unreadable: ${error.describe()}")
    } catch (error: SecurityException) {
      PhaseProjectInstructionsResolution.Skipped(relativePath, "unreadable: ${error.describe()}")
    }
  }

  private fun decodeInstructions(
    relativePath: String,
    bytes: ByteArray,
  ): PhaseProjectInstructionsResolution? {
    val content =
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    return content.takeIf(String::isNotBlank)?.let {
      PhaseProjectInstructionsResolution.Injected(relativePath, it, sha256Hex(bytes))
    }
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun skippedEntry(
    stem: String,
    reason: String,
  ) = PhaseProjectInstructionsResolution.Skipped("$PHASES_DIR/$stem$MARKDOWN_SUFFIX", reason)

  private fun listingFailure(error: Exception) =
    PhaseProjectInstructionsOutcome(
      skipped = listOf(PhaseProjectInstructionsResolution.Skipped(PHASES_DIR, "unreadable: ${error.describe()}")),
    )

  private fun Exception.describe(): String = message ?: this::class.simpleName.orEmpty()
}
