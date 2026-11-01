package skillbill.engine.featuretask.prepare

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.decomposition.loadManifestOrNull
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.specsource.SpecSourceSpecReader
import java.nio.file.NoSuchFileException
import java.nio.file.Path

@Inject
public class SpecSourceResolver(
  private val fileStore: DecompositionManifestStore,
  private val validator: DecompositionManifestValidator,
  private val projectStore: ProjectStorePort,
) {
  internal fun resolve(
    repoRoot: Path,
    specReference: String,
    isGoalContinuation: Boolean,
  ): SpecSource {
    val specPath = resolvedParentSpecPath(repoRoot, Path.of(specReference), projectStore.resolve(repoRoot))
    val manifestPath = specPath.parent?.resolve(DECOMPOSITION_MANIFEST_FILENAME)
    if (manifestPath != null && (isGoalContinuation || fileStore.isRegularFile(manifestPath))) {
      loadManifestOrNull(manifestPath, validator, fileStore)?.let { return it.specSource }
    }
    return legacySpecSource(specPath)
  }

  private fun legacySpecSource(specPath: Path): SpecSource =
    try {
      if (fileStore.isRegularFile(specPath)) {
        SpecSourceSpecReader.parseSpecSource(fileStore.readText(specPath))
      } else {
        SpecSource.LOCAL
      }
    } catch (_: NoSuchFileException) {
      SpecSource.LOCAL
    }
}
