package skillbill.engine.featuretask.runloop.planning

import java.io.IOException
import java.nio.file.Path

internal object PlanBundleAuthorization {
  private const val SPECS_DIRECTORY = ".feature-specs"
  private const val MIN_BUNDLE_SEGMENTS = 2

  fun violation(
    repoRoot: Path,
    issueKey: String,
    introducedPaths: List<String>,
    listTree: (Path) -> List<Path>,
  ): String? {
    val bundles = mutableSetOf<String>()
    introducedPaths.forEach { raw ->
      val normalized = Path.of(raw).normalize()
      val segments = normalized.map(Path::toString)
      val insideBundle =
        !normalized.isAbsolute &&
          segments.size >= MIN_BUNDLE_SEGMENTS &&
          segments[0] == SPECS_DIRECTORY &&
          segments[1].startsWith("$issueKey-") &&
          ".." !in segments
      if (!insideBundle) return "Plan changed '$raw' outside the authorized $SPECS_DIRECTORY/$issueKey-<slug>/ bundle."
      bundles += segments[1]
    }
    if (bundles.size > 1) return "Plan authored more than one $SPECS_DIRECTORY/$issueKey-<slug>/ bundle."
    val bundle = bundles.singleOrNull() ?: return null
    return try {
      escapedPath(repoRoot, bundle, introducedPaths, listTree)
        ?.let { "Plan path '$it' resolves outside the authorized bundle." }
    } catch (error: IOException) {
      "Plan bundle could not be verified: ${error.message}"
    }
  }

  private fun escapedPath(
    repoRoot: Path,
    bundle: String,
    introducedPaths: List<String>,
    listTree: (Path) -> List<Path>,
  ): String? {
    val bundleDirectory = repoRoot.resolve(SPECS_DIRECTORY).resolve(bundle)
    val realBundle = repoRoot.toRealPath().resolve(SPECS_DIRECTORY).resolve(bundle)
    val candidates = introducedPaths.map(repoRoot::resolve) + listTree(bundleDirectory)
    return candidates.firstOrNull { candidate -> escapes(candidate, realBundle) }?.let(repoRoot::relativize)?.toString()
  }

  private fun escapes(
    candidate: Path,
    realBundle: Path,
  ): Boolean =
    try {
      !candidate.toRealPath().startsWith(realBundle)
    } catch (_: IOException) {
      true
    }
}
