package skillbill.engine.specbrowser

import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestValidationResult
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class SpecManifestCache(
  private val store: DecompositionManifestStore,
  private val validator: DecompositionManifestValidator,
) {
  private val entries = ConcurrentHashMap<Path, CachedManifest>()

  fun read(manifestPath: Path): DecompositionManifest? =
    try {
      val modified = store.lastModified(manifestPath)
      val cached = entries[manifestPath]
      if (cached != null && cached.modified == modified) {
        cached.manifest
      } else {
        parse(manifestPath).also { entries[manifestPath] = CachedManifest(modified, it) }
      }
    } catch (_: IOException) {
      null
    } catch (_: UncheckedIOException) {
      null
    } catch (_: SkillBillRuntimeException) {
      null
    }

  fun modifiedAt(manifestPath: Path): Instant? = entries[manifestPath]?.modified

  fun retainUnder(
    root: Path,
    keep: Set<Path>,
  ) {
    entries.keys.removeIf { it.startsWith(root) && it !in keep }
  }

  private fun parse(manifestPath: Path): DecompositionManifest? =
    when (val result = validator.validateYamlTextResult(store.readText(manifestPath), manifestPath.toString())) {
      is DecompositionManifestValidationResult.AcceptedUnchanged -> result.manifest
      is DecompositionManifestValidationResult.AcceptedAfterRepair -> result.manifest
      is DecompositionManifestValidationResult.Rejected -> null
    }

  private data class CachedManifest(
    val modified: Instant,
    val manifest: DecompositionManifest?,
  )
}
