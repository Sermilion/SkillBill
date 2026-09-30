package skillbill.engine.featuretask.slot.pullrequest

import skillbill.ports.goalrunner.runner.PullRequestTemplateFiles
import java.nio.file.Path

internal sealed interface PullRequestTemplate {
  data class Found(
    val path: String,
    val content: String,
  ) : PullRequestTemplate

  data object Absent : PullRequestTemplate

  data class Ambiguous(val paths: List<String>) : PullRequestTemplate
}

internal object PullRequestTemplateSearch {
  val SEARCH_ORDER: List<String> =
    listOf(
      ".github/pull_request_template.md",
      ".github/PULL_REQUEST_TEMPLATE.md",
      "pull_request_template.md",
      "PULL_REQUEST_TEMPLATE.md",
      ".github/pull_request_template/*.md",
      ".github/PULL_REQUEST_TEMPLATE/*.md",
      "docs/pull_request_template.md",
    )

  private const val DIRECTORY_SUFFIX = "/*.md"
  private val HEADING = Regex("""^#{1,6}\s.*""")
  private val CHECKLIST_ITEM = Regex("""^\s*[-*+]\s+\[[ xX]]""")

  fun resolve(
    repoRoot: Path,
    files: PullRequestTemplateFiles,
  ): PullRequestTemplate =
    SEARCH_ORDER.firstNotNullOfOrNull { entry -> resolveEntry(repoRoot, entry, files) } ?: PullRequestTemplate.Absent

  private fun resolveEntry(
    repoRoot: Path,
    entry: String,
    files: PullRequestTemplateFiles,
  ): PullRequestTemplate? {
    if (!entry.endsWith(DIRECTORY_SUFFIX)) {
      return files.regularFile(repoRoot.resolve(entry))?.let { real -> found(Hit(entry, real), files) }
    }
    val directory = entry.removeSuffix(DIRECTORY_SUFFIX)
    val hits =
      files.markdownFiles(repoRoot.resolve(directory)).map { real -> Hit("$directory/${real.fileName}", real) }
    return when (hits.size) {
      0 -> null
      1 -> found(hits.single(), files)
      else -> PullRequestTemplate.Ambiguous(hits.map(Hit::relativePath))
    }
  }

  fun withoutChecklists(template: String): String =
    sections(template.lines())
      .flatMap { section ->
        val body = section.drop(1).filterNot(CHECKLIST_ITEM::containsMatchIn)
        val onlyChecklist = body.size < section.size - 1 && body.all(String::isBlank)
        if (onlyChecklist) emptyList() else listOf(section.first()) + body
      }
      .fold(mutableListOf<String>()) { kept, line ->
        if (!(line.isBlank() && kept.lastOrNull()?.isBlank() != false)) kept += line
        kept
      }
      .joinToString("\n")
      .trim()

  private fun sections(lines: List<String>): List<List<String>> =
    lines.fold(mutableListOf(mutableListOf(""))) { sections, line ->
      if (HEADING.matches(line)) sections += mutableListOf(line) else sections.last() += line
      sections
    }

  private fun found(
    hit: Hit,
    files: PullRequestTemplateFiles,
  ): PullRequestTemplate.Found =
    PullRequestTemplate.Found(
      hit.relativePath,
      withoutChecklists(files.readText(hit.real)),
    )

  private data class Hit(
    val searched: String,
    val real: Path,
  ) {
    val relativePath: String
      get() {
        val depth = searched.split('/').size
        val onDisk = real.subpath(real.nameCount - depth, real.nameCount).joinToString("/")
        return if (onDisk.equals(searched, ignoreCase = true)) onDisk else searched
      }
  }
}
