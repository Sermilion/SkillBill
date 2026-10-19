package skillbill.engine.featuretask.qa.capability

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.validation.dominantPacks
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.model.FileLocation
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.QaCapabilityDeclaration

sealed interface QaCapabilityResolution {
  data class Declared(
    val declaration: QaCapabilityDeclaration,
    val guidancePath: FileLocation,
  ) : QaCapabilityResolution

  data class Absent(val routedPackSlug: String?) : QaCapabilityResolution {
    val detail: String
      get() = "no installed pack declares qa_capability for ${routedPackSlug ?: "this repository"}"
  }

  data class Incompatible(val reason: String) : QaCapabilityResolution
}

@Inject
class QaCapabilityResolver(
  private val installedCatalog: InstalledPlatformPackCatalogPort,
) {
  fun resolve(changedPaths: List<String>): QaCapabilityResolution {
    val manifests =
      try {
        installedCatalog.manifests()
      } catch (e: SkillBillRuntimeException) {
        e.rethrowUnless(e.isShellContentContractFailure())
        return QaCapabilityResolution.Incompatible(
          "Installed platform pack discovery failed: ${e.message ?: e.javaClass.simpleName}.",
        )
      }
    return fromCandidates(dominantPacks(manifests, changedPaths))
  }

  private fun fromCandidates(candidates: List<PlatformManifest>): QaCapabilityResolution {
    val dominant = candidates.singleOrNull()
    val declaration = dominant?.qaCapability
    return when {
      candidates.size > 1 ->
        QaCapabilityResolution.Incompatible(
          "the repository scope is equally owned by ${candidates.map { it.slug }.sorted().joinToString(", ")}.",
        )
      dominant == null -> QaCapabilityResolution.Absent(null)
      declaration == null -> QaCapabilityResolution.Absent(dominant.slug)
      else ->
        QaCapabilityResolution.Declared(
          declaration = declaration,
          guidancePath = dominant.packRoot.resolve(declaration.guidanceFile),
        )
    }
  }
}
