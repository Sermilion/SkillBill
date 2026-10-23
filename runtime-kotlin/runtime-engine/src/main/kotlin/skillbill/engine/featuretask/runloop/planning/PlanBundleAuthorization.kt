package skillbill.engine.featuretask.runloop.planning

import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.engine.featuretask.phase.planning.PlanBundleTarget
import java.io.IOException
import java.nio.file.Path
import java.time.Instant

internal class PlanBundleListing(
  val tree: (Path) -> List<Path>,
  val children: (Path) -> List<Path>,
  val lastModified: (Path) -> Instant,
  val authoredSince: Instant? = null,
)

internal object PlanBundleAuthorization {
  private const val MIN_BUNDLE_SEGMENTS = 2

  fun violation(
    issueKey: String,
    target: PlanBundleTarget?,
    introducedPaths: List<String>,
    listing: PlanBundleListing,
  ): String? =
    try {
      checkoutBundleViolation(issueKey, target, introducedPaths)
        ?: target?.let { directoryViolation(issueKey, it, listing) }
    } catch (error: IOException) {
      "Plan bundle could not be verified: ${error.message}"
    }

  private fun checkoutBundleViolation(
    issueKey: String,
    target: PlanBundleTarget?,
    introducedPaths: List<String>,
  ): String? {
    val authorizedName = (target as? PlanBundleTarget.Legacy)?.directory?.fileName?.toString()
    introducedPaths.forEach { raw ->
      val normalized = Path.of(raw).normalize()
      val segments = normalized.map(Path::toString)
      val inBundleRoot =
        !normalized.isAbsolute &&
          segments.size >= MIN_BUNDLE_SEGMENTS &&
          segments[0] == LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY &&
          ".." !in segments
      if (!inBundleRoot || segments[1] == authorizedName) return@forEach
      return if (segments[1].startsWith("$issueKey-")) {
        "Plan authored more than one ${LegacyProjectStatePaths.FEATURE_SPECS_PREFIX}$issueKey-<slug>/ bundle."
      } else {
        "Plan path '$raw' is outside the authorized bundle ${target?.directory ?: "location"}."
      }
    }
    return null
  }

  private fun directoryViolation(
    issueKey: String,
    target: PlanBundleTarget,
    listing: PlanBundleListing,
  ): String? {
    val directory = target.directory.toAbsolutePath().normalize()
    val root = directory.parent
    val children = listing.children(root).map { child -> child.toAbsolutePath().normalize() }
    val authoredSince = listing.authoredSince.takeIf { target !is PlanBundleTarget.Legacy }
    val authoredSibling =
      authoredSince != null &&
        children.any { child ->
          child != directory &&
            child.fileName.toString().startsWith("$issueKey-") &&
            modifiedSince(child, listing, authoredSince)
        }
    if (authoredSibling) return "Plan authored more than one $issueKey-<slug>/ bundle under $root."
    if (directory !in children) return null
    val realBundle = root.toRealPath().resolve(directory.fileName)
    return listing.tree(directory)
      .firstOrNull { candidate -> escapes(candidate, realBundle) }
      ?.let { "Plan path '$it' resolves outside the authorized bundle." }
  }

  private fun modifiedSince(
    bundle: Path,
    listing: PlanBundleListing,
    since: Instant,
  ): Boolean = listing.tree(bundle).plusElement(bundle).any { path -> listing.lastModified(path) >= since }

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
