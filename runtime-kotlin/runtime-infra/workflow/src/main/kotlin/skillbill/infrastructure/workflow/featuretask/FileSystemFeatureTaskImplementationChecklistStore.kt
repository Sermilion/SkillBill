package skillbill.infrastructure.workflow.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskImplementationChecklistStore
import skillbill.ports.taskruntime.model.ImplementationChecklistAddress
import skillbill.ports.taskruntime.model.ImplementationChecklistPrepareResult
import skillbill.ports.taskruntime.model.ImplementationChecklistSeed
import skillbill.ports.taskruntime.model.implementationChecklistRelativePath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Inject
class FileSystemFeatureTaskImplementationChecklistStore(
  private val diagnostics: RuntimeDiagnostics,
) : FeatureTaskImplementationChecklistStore {
  override fun prepare(
    repoRoot: Path,
    seed: ImplementationChecklistSeed,
  ): ImplementationChecklistPrepareResult {
    val address = ImplementationChecklistAddress(implementationChecklistRelativePath(seed.workflowId))
    val path = repoRoot.resolve(address.relativePath).normalize()
    if (!path.startsWith(repoRoot.normalize())) {
      diagnostics.warning(
        "implementation checklist degraded: seam=implementation_checklist value_expected=contained " +
          "value_used=escaped",
      )
      return ImplementationChecklistPrepareResult.Degraded(address, "checklist path escaped the repository root")
    }
    return when (val loaded = loadExisting(path, address, seed)) {
      is LoadedChecklist.Ticks ->
        write(path, address, render(seed, loaded.ticks), ImplementationChecklistPrepareResult.Ready(address))
      is LoadedChecklist.Finished -> loaded.result
    }
  }

  private fun loadExisting(
    path: Path,
    address: ImplementationChecklistAddress,
    seed: ImplementationChecklistSeed,
  ): LoadedChecklist {
    if (!Files.exists(path)) return LoadedChecklist.Ticks(emptyMap())
    if (!Files.isRegularFile(path)) {
      return LoadedChecklist.Finished(unwritable(address, IllegalStateException("checklist path is not a file")))
    }
    val text =
      runCatching { Files.readString(path) }.getOrElse { error ->
        return LoadedChecklist.Finished(unwritable(address, error))
      }
    return loadedParsedChecklist(path, address, seed, text)
  }

  private fun loadedParsedChecklist(
    path: Path,
    address: ImplementationChecklistAddress,
    seed: ImplementationChecklistSeed,
    text: String,
  ): LoadedChecklist {
    val parsed = parse(text, seed.workflowId)
    if (parsed != null) return LoadedChecklist.Ticks(retainedTicks(parsed, seed))
    diagnostics.warning(
      "implementation checklist degraded: seam=implementation_checklist value_expected=checklist " +
        "value_used=corrupt",
    )
    return LoadedChecklist.Finished(
      write(path, address, render(seed, emptyMap()), ImplementationChecklistPrepareResult.Degraded(address, "corrupt")),
    )
  }

  private fun write(
    path: Path,
    address: ImplementationChecklistAddress,
    body: String,
    success: ImplementationChecklistPrepareResult,
  ): ImplementationChecklistPrepareResult =
    runCatching {
      Files.createDirectories(path.parent)
      Files.writeString(path, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
      success
    }.getOrElse { error -> unwritable(address, error) }

  private fun unwritable(
    address: ImplementationChecklistAddress,
    error: Throwable,
  ): ImplementationChecklistPrepareResult.Degraded {
    diagnostics.warning(
      "implementation checklist degraded: seam=implementation_checklist value_expected=writable " +
        "value_used=unwritable",
      error,
    )
    return ImplementationChecklistPrepareResult.Degraded(address, error.message ?: error.javaClass.simpleName)
  }

  private fun retainedTicks(
    parsed: ParsedChecklist,
    seed: ImplementationChecklistSeed,
  ): Map<String, Boolean> {
    if (parsed.planDigest != seed.planDigest) {
      diagnostics.warning(
        "implementation checklist degraded: seam=implementation_checklist " +
          "value_expected=current-plan-digest value_used=stale-plan-digest",
      )
      return emptyMap()
    }
    return seed.tasks.mapNotNull { task ->
      val tick = parsed.ticks[task.key] ?: return@mapNotNull null
      if (tick.checked && tick.title == task.title) task.key to true else null
    }.toMap()
  }

  private fun parse(
    text: String,
    workflowId: String,
  ): ParsedChecklist? {
    val lines = text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
    val planDigest = planDigest(lines, workflowId) ?: return null
    val ticks = taskTicks(lines.drop(2)) ?: return null
    return ParsedChecklist(planDigest, ticks)
  }

  private fun planDigest(
    lines: List<String>,
    workflowId: String,
  ): String? {
    if (lines.isEmpty() || lines[0] != "# $workflowId") return null
    if (lines.size < 2 || !lines[1].startsWith("Plan digest: ")) return null
    return lines[1].removePrefix("Plan digest: ").trim().ifEmpty { null }
  }

  private fun taskTicks(lines: List<String>): Map<String, ParsedTick>? {
    val ticks = linkedMapOf<String, ParsedTick>()
    lines.forEach { line ->
      val match = TASK_LINE.matchEntire(line) ?: return null
      ticks[match.groupValues[2]] =
        ParsedTick(
          checked = match.groupValues[1].equals("x", ignoreCase = true),
          title = match.groupValues[3],
        )
    }
    return ticks
  }

  private fun render(
    seed: ImplementationChecklistSeed,
    existingTicks: Map<String, Boolean>,
  ): String {
    val tasks =
      seed.tasks.joinToString("\n") { task ->
        val mark = if (existingTicks[task.key] == true) "x" else " "
        "- [$mark] ${task.key} ${task.title}"
      }
    return buildString {
      appendLine("# ${seed.workflowId}")
      appendLine()
      appendLine("Plan digest: ${seed.planDigest}")
      appendLine()
      if (tasks.isNotBlank()) appendLine(tasks)
    }
  }
}

private sealed interface LoadedChecklist {
  data class Ticks(val ticks: Map<String, Boolean>) : LoadedChecklist

  data class Finished(val result: ImplementationChecklistPrepareResult) : LoadedChecklist
}

private data class ParsedChecklist(
  val planDigest: String,
  val ticks: Map<String, ParsedTick>,
)

private data class ParsedTick(
  val checked: Boolean,
  val title: String,
)

private val TASK_LINE: Regex = Regex("""^- \[([ xX])\] (\S+) (.+)$""")
