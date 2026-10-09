package skillbill.infrastructure.skills.scaffold.validation.review
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.UNIVERSAL_CODE_REVIEW_AREAS
import java.nio.file.Files
import java.nio.file.Path

internal fun declaredAreaForFile(
  manifest: Map<*, *>,
  relativeFile: String,
): String? {
  val files = manifest["declared_files"] as? Map<*, *> ?: return null
  val areas = files["areas"] as? Map<*, *> ?: return null
  return areas.entries.firstOrNull { (_, path) -> path == relativeFile }?.key as? String
    ?: (APPROVED_CODE_REVIEW_AREAS + UNIVERSAL_CODE_REVIEW_AREAS)
      .sortedByDescending(String::length)
      .firstOrNull { area ->
        relativeFile.substringBeforeLast("/content.md").endsWith("-$area")
      }
}

internal fun declaredContentFiles(
  declaredFiles: Map<*, *>,
  areas: Map<*, *>,
): Set<String> =
  buildSet {
    (declaredFiles["baseline"] as? String)?.let(::add)
    areas.values.filterIsInstance<String>().forEach(::add)
  }

internal fun contentFiles(pack: Path): List<Path> {
  val root = pack.resolve("code-review")
  if (!Files.isDirectory(root)) return emptyList()
  return Files.walk(root).use { paths -> paths.filter { it.fileName.toString() == "content.md" }.toList() }
}

internal fun allContentFiles(pack: Path): List<Path> =
  Files.walk(pack).use { paths ->
    paths.filter { it.fileName.toString() == "content.md" }.toList()
  }

internal fun headings(file: Path): List<String> =
  Files.readAllLines(file)
    .filter { it.startsWith("## ") }
    .map { it.removePrefix("## ") }

internal fun hasInternalParent(
  file: Path,
  parent: String,
): Boolean = Regex("(?m)^internal-for: ${Regex.escape(parent)}\\s*$").containsMatchIn(Files.readString(file))

internal fun containsWrapperOrProviderOutput(content: String): Boolean =
  content.startsWith("---\n") ||
    containsAll(content, "## Descriptor") ||
    Regex("(?m)^(compose:|developer_instructions:|model:)").containsMatchIn(content)

internal fun isSpecialistRubric(content: String): Boolean {
  val title = content.lineSequence().firstOrNull { it.startsWith("# ") }.orEmpty()
  return Regex("(?i)^# .*(rubric|guidelines|checks|rules)").containsMatchIn(title) &&
    content.lineSequence().any { it.startsWith("## ") } &&
    Regex("(?i)\\b(must|never|verify|reject|flag|require)\\b").containsMatchIn(content)
}

internal fun ignoreSection(content: String): String =
  content.substringAfter("## Ignore", "").substringBefore(
    "## Applicability",
  )

internal fun h2Section(
  content: String,
  heading: String,
): String = content.substringAfter("## $heading", "").substringBefore("\n## ")
