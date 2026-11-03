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

public sealed interface PhaseProjectInstructionsResolution {
  public data class Injected(
    public val path: String,
    public val content: String,
    public val sha256: String,
  ) : PhaseProjectInstructionsResolution

  public data class Skipped(
    public val path: String,
    public val reason: String,
  ) : PhaseProjectInstructionsResolution
}

internal object AbsentPhaseInstructionFileSource : PhaseInstructionFileSource {
  override fun markdownFileNames(directory: Path): List<String>? = null

  override fun regularFileSize(file: Path): Long? = null

  override fun readBytes(file: Path): ByteArray = ByteArray(0)
}

public data class PhaseProjectInstructionsOutcome(
  public val injected: List<PhaseProjectInstructionsResolution.Injected> = emptyList(),
  public val skipped: List<PhaseProjectInstructionsResolution.Skipped> = emptyList(),
)

public object PhaseProjectInstructions {
  public const val MAX_BYTES: Int = 16 * 1024
  public const val PHASES_DIR: String = ".skill-bill/phases"
  public const val MARKDOWN_SUFFIX: String = ".md"
  private const val KIB_BYTES = 1024
  private const val PROJECT_PHASES_LABEL = "project store phases directory"
  public val ALIASES: Map<String, String> = PhaseSlot.stepAliases
  private val knownPhaseIds: Set<String> = PhaseSlot.entries.flatMap(PhaseSlot::steps).toSet()

  public fun resolve(
    source: PhaseInstructionFileSource,
    repoRoot: Path,
    phaseId: String,
  ): PhaseProjectInstructionsOutcome {
    val repo = guarded(PHASES_DIR) { resolveFromDirectory(source, repoRoot.resolve(PHASES_DIR), PHASES_DIR, phaseId) }
    val project =
      guarded(PROJECT_PHASES_LABEL) {
        source.projectPhasesDirectory(repoRoot)?.let { directory ->
          resolveFromDirectory(source, directory, directory.toString(), phaseId)
        } ?: PhaseProjectInstructionsOutcome()
      }
    return PhaseProjectInstructionsOutcome(repo.injected + project.injected, repo.skipped + project.skipped)
  }

  private fun guarded(
    label: String,
    resolve: () -> PhaseProjectInstructionsOutcome,
  ): PhaseProjectInstructionsOutcome =
    try {
      resolve()
    } catch (error: IOException) {
      listingFailure(label, error)
    } catch (error: UncheckedIOException) {
      listingFailure(label, error)
    } catch (error: DirectoryIteratorException) {
      listingFailure(label, error)
    } catch (error: SecurityException) {
      listingFailure(label, error)
    }

  private fun resolveFromDirectory(
    source: PhaseInstructionFileSource,
    directory: Path,
    label: String,
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
        .map { skippedEntry(label, it, "unknown phase id '$it'") } +
        aliasStems.filter { it != chosen }
          .map { skippedEntry(label, it, "shadowed by $label/$chosen$MARKDOWN_SUFFIX") }
    return when (val resolution = chosen?.let { readInstructions(source, directory, label, it) }) {
      is PhaseProjectInstructionsResolution.Injected -> PhaseProjectInstructionsOutcome(listOf(resolution), skipped)
      is PhaseProjectInstructionsResolution.Skipped -> PhaseProjectInstructionsOutcome(skipped = skipped + resolution)
      null -> PhaseProjectInstructionsOutcome(skipped = skipped)
    }
  }

  private fun readInstructions(
    source: PhaseInstructionFileSource,
    directory: Path,
    label: String,
    stem: String,
  ): PhaseProjectInstructionsResolution? {
    val displayPath = "$label/$stem$MARKDOWN_SUFFIX"
    val path = directory.resolve("$stem$MARKDOWN_SUFFIX")
    return try {
      val size = source.regularFileSize(path)
      when {
        size == null -> PhaseProjectInstructionsResolution.Skipped(displayPath, "unreadable: not a regular file")
        size > MAX_BYTES ->
          PhaseProjectInstructionsResolution.Skipped(
            displayPath,
            "exceeds ${MAX_BYTES / KIB_BYTES} KiB cap ($size bytes)",
          )
        else -> decodeInstructions(displayPath, source.readBytes(path))
      }
    } catch (error: IOException) {
      PhaseProjectInstructionsResolution.Skipped(displayPath, "unreadable: ${error.describe()}")
    } catch (error: SecurityException) {
      PhaseProjectInstructionsResolution.Skipped(displayPath, "unreadable: ${error.describe()}")
    }
  }

  private fun decodeInstructions(
    displayPath: String,
    bytes: ByteArray,
  ): PhaseProjectInstructionsResolution? {
    val content =
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    return content.takeIf(String::isNotBlank)?.let {
      PhaseProjectInstructionsResolution.Injected(displayPath, it, sha256Hex(bytes))
    }
  }

  public fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun skippedEntry(
    label: String,
    stem: String,
    reason: String,
  ) = PhaseProjectInstructionsResolution.Skipped("$label/$stem$MARKDOWN_SUFFIX", reason)

  private fun listingFailure(
    label: String,
    error: Exception,
  ) = PhaseProjectInstructionsOutcome(
    skipped = listOf(PhaseProjectInstructionsResolution.Skipped(label, "unreadable: ${error.describe()}")),
  )

  public fun Exception.describe(): String = message ?: this::class.simpleName.orEmpty()
}
