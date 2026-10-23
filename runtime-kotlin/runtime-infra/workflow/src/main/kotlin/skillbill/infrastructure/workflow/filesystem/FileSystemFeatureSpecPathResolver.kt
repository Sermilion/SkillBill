package skillbill.infrastructure.workflow.filesystem

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecOrigin
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.SpecPathLocation
import skillbill.ports.projectstore.model.classifySpecPath
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemFeatureSpecPathResolver(
  private val projectStore: ProjectStorePort,
) : FeatureSpecPathResolverPort {
  override fun resolve(input: FeatureSpecPathResolveInput): FeatureSpecPathResolveResult {
    val checkoutRoot = input.repoRoot.toAbsolutePath().normalize()
    val store = projectStore.resolve(checkoutRoot)
    input.explicitSpecPath?.takeIf(String::isNotBlank)?.let { explicit ->
      val location = store.classifySpecPath(checkoutRoot.resolve(explicit).normalize(), checkoutRoot)
      val origin = if (location is SpecPathLocation.Legacy) FeatureSpecOrigin.LEGACY else FeatureSpecOrigin.STORE
      return FeatureSpecPathResolveResult.Explicit(Path.of(explicit).toString(), origin)
    }
    val legacyRoot = checkoutRoot.resolve(LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY)
    return scan(store.specsRoot, input.issueKey, FeatureSpecOrigin.STORE)
      ?: scan(legacyRoot, input.issueKey, FeatureSpecOrigin.LEGACY)
      ?: FeatureSpecPathResolveResult.NoMatch(input.issueKey, store.specsRoot)
  }

  private fun scan(
    specsRoot: Path,
    issueKey: String,
    origin: FeatureSpecOrigin,
  ): FeatureSpecPathResolveResult? {
    val matches = matchingSpecs(specsRoot, issueKey)
    return when (matches.size) {
      0 -> null
      1 -> FeatureSpecPathResolveResult.SingleMatch(matches.single().toString(), origin)
      else -> FeatureSpecPathResolveResult.Ambiguous(issueKey, matches)
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
