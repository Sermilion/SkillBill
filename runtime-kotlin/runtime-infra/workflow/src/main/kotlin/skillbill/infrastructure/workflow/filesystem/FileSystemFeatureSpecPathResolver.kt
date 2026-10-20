package skillbill.infrastructure.workflow.filesystem

import me.tatarka.inject.annotations.Inject
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemFeatureSpecPathResolver : FeatureSpecPathResolverPort {
  override fun resolve(input: FeatureSpecPathResolveInput): FeatureSpecPathResolveResult {
    input.explicitSpecPath?.takeIf(String::isNotBlank)?.let { explicit ->
      return FeatureSpecPathResolveResult.Explicit(Path.of(explicit).toString())
    }
    val specsRoot = input.repoRoot.toAbsolutePath().normalize().resolve(".feature-specs")
    if (!Files.isDirectory(specsRoot)) {
      return FeatureSpecPathResolveResult.NoMatch(input.issueKey, specsRoot)
    }
    val matches = matchingSpecs(specsRoot, input.issueKey)
    return when (matches.size) {
      0 -> FeatureSpecPathResolveResult.NoMatch(input.issueKey, specsRoot)
      1 -> FeatureSpecPathResolveResult.SingleMatch(matches.single().toString())
      else -> FeatureSpecPathResolveResult.Ambiguous(input.issueKey, matches)
    }
  }

  private fun matchingSpecs(
    specsRoot: Path,
    issueKey: String,
  ): List<Path> {
    if (!Files.isDirectory(specsRoot)) return emptyList()
    return Files.list(specsRoot).use { stream ->
      stream
        .filter { candidate -> Files.isDirectory(candidate) }
        .filter { candidate -> candidate.fileName.toString().startsWith("$issueKey-") }
        .map { candidate -> candidate.resolve("spec.md") }
        .filter { specPath -> Files.isRegularFile(specPath) }
        .toList()
        .sorted()
    }
  }
}
