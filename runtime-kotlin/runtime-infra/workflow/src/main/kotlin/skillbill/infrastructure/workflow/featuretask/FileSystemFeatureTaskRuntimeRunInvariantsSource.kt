package skillbill.infrastructure.workflow.featuretask

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import me.tatarka.inject.annotations.Inject
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.infrastructure.workflow.decomposition.DecompositionManifestBundleJournal
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import skillbill.review.spec.GovernedSpecSectionParser.MANDATES_HEADINGS
import skillbill.workflow.decomposition.decodeDecompositionManifestWireMap
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import skillbill.workflow.decomposition.model.isDecompositionManifestSchemaFailure
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Inject
class FileSystemFeatureTaskRuntimeRunInvariantsSource : FeatureTaskRuntimeRunInvariantsSource {
  override fun read(specPath: Path): FeatureTaskRuntimeRunInvariantsRead {
    val normalizedPath = specPath.toAbsolutePath().normalize()
    if (!Files.isRegularFile(normalizedPath) || !Files.isReadable(normalizedPath)) {
      return rejected("feature-task-runtime spec path '$normalizedPath' must point to a readable spec file.")
    }
    val realPath = authorizedRealPath(normalizedPath) ?: return rejected(authorizationReason(normalizedPath))
    requireSelectedBundleEntry(normalizedPath)?.let { return rejected(it) }
    return readSpecInvariants(normalizedPath, realPath)
  }

  private fun readSpecInvariants(
    normalizedPath: Path,
    realPath: Path,
  ): FeatureTaskRuntimeRunInvariantsRead {
    val specText = Files.readString(realPath)
    val featureSize = parseFeatureSize(specText)
    val acceptanceCriteria =
      GovernedSpecSectionParser.parseListSection(specText) { it.startsWith(ACCEPTANCE_CRITERIA_PREFIX) }
    val invariantsViolation = FeatureTaskRuntimeRunInvariants.violation(normalizedPath.toString(), acceptanceCriteria)
    if (invariantsViolation != null) return rejected(invariantsViolation)
    return FeatureTaskRuntimeRunInvariantsRead.Read(
      FeatureTaskRuntimeRunInvariants(
        specReference = normalizedPath.toString(),
        featureSize = featureSize,
        acceptanceCriteria = acceptanceCriteria,
        mandatesAndOverrides = GovernedSpecSectionParser.parseListSection(specText) { it in MANDATES_HEADINGS },
      ),
    )
  }

  private fun authorizedRealPath(normalizedPath: Path): Path? {
    val realPath = normalizedPath.toRealPath()
    val specsRoot =
      generateSequence(normalizedPath.parent) { it.parent }.firstOrNull {
        it.fileName?.toString() == FEATURE_SPECS_DIRECTORY
      }
    val authorizedRoot = (specsRoot ?: normalizedPath.parent).toRealPath()
    return realPath.takeIf { it.startsWith(authorizedRoot) }
  }

  private fun requireSelectedBundleEntry(normalizedPath: Path): String? {
    val bundleDirectory = normalizedPath.parent
    val manifestPath = bundleDirectory.resolve(MANIFEST_FILE_NAME)
    return try {
      DecompositionManifestBundleJournal().failIfPending(bundleDirectory)
      if (Files.isRegularFile(manifestPath)) {
        selectedBundleEntryViolation(normalizedPath, readManifest(manifestPath), manifestPath)
      } else {
        null
      }
    } catch (error: InvalidPathException) {
      error.message.orEmpty()
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isDecompositionManifestSchemaFailure())
      error.message.orEmpty()
    }
  }

  private fun selectedBundleEntryViolation(
    normalizedPath: Path,
    manifest: DecompositionManifest,
    manifestPath: Path,
  ): String? {
    if (normalizedPath.fileName.toString() == Path.of(manifest.parentSpecPath).fileName.toString()) return null
    return if (manifest.subtasks.any { it.specPath.fileNameOrNull() == normalizedPath.fileName }) {
      null
    } else {
      "feature-task-runtime spec path '$normalizedPath' is not a subtask selected by '$manifestPath'."
    }
  }

  private fun authorizationReason(normalizedPath: Path): String {
    val realPath = normalizedPath.toRealPath()
    val specsRoot =
      generateSequence(normalizedPath.parent) { it.parent }.firstOrNull {
        it.fileName?.toString() == FEATURE_SPECS_DIRECTORY
      }
    val authorizedRoot = (specsRoot ?: normalizedPath.parent).toRealPath()
    return "feature-task-runtime spec path '$normalizedPath' resolves to '$realPath' outside '$authorizedRoot'."
  }

  private fun rejected(reason: String) = FeatureTaskRuntimeRunInvariantsRead.Rejected(reason)

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
