package skillbill.infrastructure.workflow.featuretask

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import me.tatarka.inject.annotations.Inject
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.infrastructure.workflow.decomposition.DecompositionManifestBundleJournal
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import skillbill.review.spec.GovernedSpecSectionParser.MANDATES_HEADINGS
import skillbill.workflow.decomposition.decodeDecompositionManifestWireMap
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemFeatureTaskRuntimeRunInvariantsSource : FeatureTaskRuntimeRunInvariantsSource {
  override fun read(specPath: Path): FeatureTaskRuntimeRunInvariants {
    val normalizedPath = specPath.toAbsolutePath().normalize()
    require(Files.isRegularFile(normalizedPath) && Files.isReadable(normalizedPath)) {
      "feature-task-runtime spec path '$normalizedPath' must point to a readable spec file."
    }
    val realPath = authorizedRealPath(normalizedPath)
    requireSelectedBundleEntry(normalizedPath)
    val specText = Files.readString(realPath)
    return FeatureTaskRuntimeRunInvariants(
      specReference = normalizedPath.toString(),
      featureSize = parseFeatureSize(specText),
      acceptanceCriteria =
        GovernedSpecSectionParser.parseListSection(specText) {
          it.startsWith(ACCEPTANCE_CRITERIA_PREFIX)
        },
      mandatesAndOverrides = GovernedSpecSectionParser.parseListSection(specText) { it in MANDATES_HEADINGS },
    )
  }

  private fun authorizedRealPath(normalizedPath: Path): Path {
    val realPath = normalizedPath.toRealPath()
    val specsRoot =
      generateSequence(normalizedPath.parent) { it.parent }.firstOrNull {
        it.fileName?.toString() == FEATURE_SPECS_DIRECTORY
      }
    val authorizedRoot = (specsRoot ?: normalizedPath.parent).toRealPath()
    require(realPath.startsWith(authorizedRoot)) {
      "feature-task-runtime spec path '$normalizedPath' resolves to '$realPath' outside '$authorizedRoot'."
    }
    return realPath
  }

  private fun requireSelectedBundleEntry(normalizedPath: Path) {
    val bundleDirectory = normalizedPath.parent
    val manifestPath = bundleDirectory.resolve(MANIFEST_FILE_NAME)
    try {
      DecompositionManifestBundleJournal().failIfPending(bundleDirectory)
      if (!Files.isRegularFile(manifestPath)) return
      val manifest = readManifest(manifestPath)
      if (normalizedPath.fileName.toString() == Path.of(manifest.parentSpecPath).fileName.toString()) return
      require(manifest.subtasks.any { it.specPath.fileNameOrNull() == normalizedPath.fileName }) {
        "feature-task-runtime spec path '$normalizedPath' is not a subtask selected by '$manifestPath'."
      }
    } catch (error: InvalidDecompositionManifestSchemaError) {
      throw IllegalArgumentException(error.message, error)
    }
  }

  private fun readManifest(manifestPath: Path): DecompositionManifest {
    val raw: Any? = YAMLMapper().readValue(Files.readString(manifestPath), Any::class.java)
    return decodeDecompositionManifestWireMap(DecompositionManifestWireMap.fromAny(raw), manifestPath.toString())
  }

  private fun String.fileNameOrNull(): Path? = takeIf(String::isNotBlank)?.let { Path.of(it).fileName }

  private fun parseFeatureSize(specText: String): FeatureTaskRuntimeFeatureSize {
    val rawValue =
      FEATURE_SIZE_LINE.find(specText)?.groupValues?.get(1)
        ?: return FeatureTaskRuntimeFeatureSize.DEFAULT
    return FeatureTaskRuntimeFeatureSize.fromWire(rawValue)
  }

  private companion object {
    val FEATURE_SIZE_LINE = Regex("""(?im)^\s*(?:feature[_ -]size|size)\s*:\s*([^\r\n#]+)(?:\s+#.*)?$""")
    const val FEATURE_SPECS_DIRECTORY = ".feature-specs"
    const val MANIFEST_FILE_NAME = "decomposition-manifest.yaml"
  }
}
