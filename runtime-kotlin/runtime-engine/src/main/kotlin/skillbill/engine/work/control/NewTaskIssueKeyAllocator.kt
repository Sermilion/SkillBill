package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.work.control.model.NewTaskKeyAllocation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import java.io.IOException
import java.math.BigInteger
import java.nio.file.NoSuchFileException
import java.nio.file.Path

@Inject
public class NewTaskIssueKeyAllocator(
  private val projects: ProjectStorePort,
  private val files: DecompositionManifestStore,
  private val diagnostics: RuntimeDiagnostics,
) {
  public fun allocate(
    repoRoot: Path,
    prefix: String?,
    occupiedKeys: Set<String>,
  ): NewTaskKeyAllocation {
    val bundleKeys = inventory(repoRoot)
    val selected =
      prefix?.trim()?.uppercase()?.takeIf(PREFIX::matches)
        ?: return NewTaskKeyAllocation.NeedsPrefix(prefixesOf(bundleKeys))
    val keys = bundleKeys + occupiedKeys.mapNotNull(::issueKey)
    return NewTaskKeyAllocation.Allocated(nextKey(keys, selected))
  }

  public fun prefixes(repoRoot: Path): List<String> = prefixesOf(inventory(repoRoot))

  internal fun suggestions(
    repoRoot: Path,
    occupiedKeys: Set<String>,
  ): List<String> {
    val bundleKeys = inventory(repoRoot)
    val keys = bundleKeys + occupiedKeys.mapNotNull(::issueKey)
    return prefixesOf(bundleKeys).map { prefix -> nextKey(keys, prefix) }
  }

  private fun nextKey(
    keys: Set<String>,
    prefix: String,
  ): String {
    val highest = keys.mapNotNull { numberFor(it, prefix) }.maxOrNull() ?: BigInteger.ZERO
    return "$prefix-${highest + BigInteger.ONE}"
  }

  private fun prefixesOf(keys: Set<String>): List<String> =
    keys.map(::prefixOf).filterNot { it == LOCAL_PREFIX }.toSet().sorted()

  public fun holds(
    repoRoot: Path,
    key: String,
  ): Boolean = inventory(repoRoot).any { it == key.uppercase() }

  private fun inventory(repoRoot: Path): Set<String> {
    val store = projects.resolve(repoRoot)
    val roots =
      listOf(
        store.specsRoot,
        repoRoot.resolve(LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY),
        repoRoot.resolve(LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY)
          .resolve(LegacyProjectStatePaths.FEATURE_SPECS_ARCHIVE_DIRECTORY),
      )
    return buildSet {
      roots.forEach { root ->
        val children =
          try {
            files.listDirectChildDirectories(root)
          } catch (failure: IOException) {
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "seam=new-task.allocate outcome=unreadable path=$root expected=directory_listing actual=omitted " +
                "cause=${failure.javaClass.simpleName}",
              failure,
            )
            emptyList()
          }
        children.forEach { bundle ->
          addIfKey(issueAndFeature(bundle.fileName.toString()).first)
          readIdentity(bundle.resolve("spec.md"), SPEC_HEADING, "spec_heading")?.let { addIfKey(it) }
          readIdentity(bundle.resolve("decomposition-manifest.yaml"), MANIFEST_KEY, "manifest_issue_key")
            ?.let { addIfKey(it) }
        }
      }
    }
  }

  private fun readIdentity(
    path: Path,
    pattern: Regex,
    identity: String,
  ): String? =
    try {
      pattern.find(files.readTextWithoutRecovery(path))?.groupValues?.getOrNull(1)
    } catch (_: NoSuchFileException) {
      null
    } catch (failure: IOException) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=new-task.allocate outcome=unreadable path=$path expected=$identity actual=omitted " +
          "cause=${failure.javaClass.simpleName}",
        failure,
      )
      null
    }

  private fun MutableSet<String>.addIfKey(value: String) {
    issueKey(value)?.let(::add)
  }

  private fun issueKey(value: String): String? = value.uppercase().takeIf(ISSUE_KEY::matches)

  private fun prefixOf(key: String): String = key.substringBeforeLast('-')

  private fun numberFor(
    key: String,
    prefix: String,
  ): BigInteger? {
    if (prefixOf(key) != prefix) return null
    return key.substringAfterLast('-').substringBefore('.').toBigIntegerOrNull()
  }

  private companion object {
    val ISSUE_KEY = Regex("(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")
    val PREFIX = Regex("[A-Z0-9]+")
    const val LOCAL_PREFIX = "LOCAL"
    val SPEC_HEADING = Regex("(?m)^#\\s+($TRACKER_STYLE_ISSUE_KEY_PATTERN)(?:\\b|$)", RegexOption.IGNORE_CASE)
    val MANIFEST_KEY =
      Regex(
        "(?m)^\\s*${DecompositionManifestPayloadKeys.ISSUE_KEY}:\\s*[\"']?" +
          "($TRACKER_STYLE_ISSUE_KEY_PATTERN)[\"']?(?:\\s+#.*)?\\s*$",
        RegexOption.IGNORE_CASE,
      )
  }
}
