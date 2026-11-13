package skillbill.infrastructure.workflow.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.host.jvm.pathContainedIn
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.taskruntime.FeatureTaskRuntimePreplanEvidenceStore
import skillbill.ports.taskruntime.model.PreplanEvidenceReference
import skillbill.ports.taskruntime.model.PreplanEvidenceWriteRequest
import skillbill.ports.taskruntime.model.PreplanEvidenceWriteResult
import skillbill.workflow.taskruntime.artifact.FeatureTaskRuntimeRunEvidenceAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Inject
class FileSystemFeatureTaskRuntimePreplanEvidenceStore(
  private val diagnostics: RuntimeDiagnostics,
  private val projectStore: ProjectStorePort,
) : FeatureTaskRuntimePreplanEvidenceStore {
  override fun write(request: PreplanEvidenceWriteRequest): PreplanEvidenceWriteResult {
    val storeRoot = projectStore.resolve(request.repoRoot).root.toAbsolutePath().normalize()
    val path = storeRoot.resolve(evidenceRelativePath(request.workflowId)).normalize()
    if (!path.startsWith(storeRoot)) return degraded("escaped", "evidence path escaped the project store root")
    val excerpts = request.references.distinct().map { reference -> excerpt(request.repoRoot, reference) }
    val read = excerpts.filterIsInstance<Excerpt.Read>()
    val skipped = excerpts.filterIsInstance<Excerpt.Skipped>().map(Excerpt.Skipped::reference)
    skipped.forEach { reference ->
      diagnostics.info("preplan evidence skipped reference ${reference.label}: not a readable checkout range")
    }
    return catchingNonCancellation {
      Files.createDirectories(path.parent)
      val staged = Files.createTempFile(path.parent, path.fileName.toString(), STAGING_SUFFIX)
      Files.writeString(staged, render(request, read))
      Files.move(staged, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      PreplanEvidenceWriteResult.Written(path, read.map(Excerpt.Read::reference), skipped)
    }.getOrElse { error -> degraded("unwritable", error.message ?: error.javaClass.simpleName) }
  }

  private fun excerpt(
    repoRoot: Path,
    reference: PreplanEvidenceReference,
  ): Excerpt {
    val root = repoRoot.toAbsolutePath().normalize()
    val file = root.resolve(reference.path).normalize()
    if (!pathContainedIn(file, root) || !Files.isRegularFile(file)) return Excerpt.Skipped(reference)
    val lines = catchingNonCancellation { Files.readAllLines(file) }.getOrNull() ?: return Excerpt.Skipped(reference)
    val start = reference.startLine ?: 1
    if (lines.isEmpty() || start > lines.size) return Excerpt.Skipped(reference)
    val end = minOf(reference.endLine ?: lines.size, lines.size)
    val relative = root.relativize(file).toString()
    return Excerpt.Read(
      PreplanEvidenceReference(relative, start, end),
      lines.subList(start - 1, end),
    )
  }

  private fun render(
    request: PreplanEvidenceWriteRequest,
    excerpts: List<Excerpt.Read>,
  ): String =
    buildString {
      appendLine("# Preplan evidence")
      appendLine()
      appendLine("workflow: ${request.workflowId}")
      appendLine("checkout_head: ${request.checkoutHead ?: "unknown"}")
      appendLine()
      appendLine(
        "The accepted preplan digest, then the checkout source it cites, copied verbatim with each line's own " +
          "number. Read this file before the source; open a source file only for a fact this file lacks.",
      )
      appendLine()
      appendLine("## Index")
      appendLine()
      if (excerpts.isEmpty()) appendLine("(no source excerpts)")
      excerpts.forEach { excerpt -> appendLine("- ${excerpt.reference.label}") }
      appendLine()
      appendLine("## Preplan digest")
      appendLine()
      appendLine(request.digest.trimEnd())
      appendLine()
      appendLine("## Source excerpts")
      excerpts.forEach { excerpt -> appendExcerpt(excerpt) }
    }

  private fun StringBuilder.appendExcerpt(excerpt: Excerpt.Read) {
    val first = excerpt.reference.startLine ?: 1
    val width = (first + excerpt.lines.size - 1).toString().length
    val fence = fenceFor(excerpt.lines)
    appendLine()
    appendLine("### ${excerpt.reference.label}")
    appendLine()
    appendLine(fence)
    excerpt.lines.forEachIndexed { index, line ->
      appendLine("${(first + index).toString().padStart(width)}  $line")
    }
    appendLine(fence)
  }

  private fun fenceFor(lines: List<String>): String {
    val longestRun = lines.maxOfOrNull { line -> BACKTICK_RUN.findAll(line).maxOfOrNull { it.value.length } ?: 0 } ?: 0
    return "`".repeat(maxOf(MIN_FENCE, longestRun + 1))
  }

  private fun degraded(
    valueUsed: String,
    reason: String,
  ): PreplanEvidenceWriteResult.Degraded {
    diagnostics.warning(
      "preplan evidence degraded: seam=preplan_evidence value_expected=written value_used=$valueUsed " +
        "cause=$reason; plan keeps the pasted digest",
    )
    return PreplanEvidenceWriteResult.Degraded(reason)
  }

  private fun evidenceRelativePath(workflowId: String): String =
    "${FeatureTaskRuntimeRunEvidenceAddress.workflowStoreRoot(workflowId)}/$EVIDENCE_FILE"

  private sealed interface Excerpt {
    data class Read(
      val reference: PreplanEvidenceReference,
      val lines: List<String>,
    ) : Excerpt

    data class Skipped(
      val reference: PreplanEvidenceReference,
    ) : Excerpt
  }

  private companion object {
    const val EVIDENCE_FILE: String = "preplan-evidence.md"
    const val STAGING_SUFFIX: String = ".staging"
    const val MIN_FENCE: Int = 3
    val BACKTICK_RUN: Regex = Regex("`+")
  }
}
