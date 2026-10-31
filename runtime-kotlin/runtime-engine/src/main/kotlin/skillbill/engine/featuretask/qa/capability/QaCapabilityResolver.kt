package skillbill.engine.featuretask.qa.capability

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.execution.QaDeclaredTarget
import skillbill.engine.featuretask.validation.routedConcretePacks
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

public sealed interface QaCapabilityResolution {
  public data class Declared(val targets: List<QaDeclaredTarget>) : QaCapabilityResolution {
    init {
      require(targets.isNotEmpty()) { "A declared QA capability needs at least one target." }
    }
  }

  public data class Absent(val routedPackSlug: String?) : QaCapabilityResolution {
    internal val detail: String
      get() = "no installed pack declares qa_capability for ${routedPackSlug ?: "this repository"}"
  }

  public data class Incompatible(val reason: String) : QaCapabilityResolution
}

@Inject
public class QaCapabilityResolver(
  private val gitOperations: WorkflowGitOperations,
  private val installedCatalog: InstalledPlatformPackCatalogPort,
) {
  internal fun resolve(repoRoot: Path): QaCapabilityResolution =
    when (val paths = gitOperations.trackedPaths(repoRoot)) {
      is WorkflowGitNameListResult.Listed -> resolve(paths.names)
      is WorkflowGitNameListResult.Failed ->
        QaCapabilityResolution.Incompatible("QA could not read tracked paths: ${paths.error}")
    }

  internal fun resolve(changedPaths: List<String>): QaCapabilityResolution {
    val manifests =
      try {
        installedCatalog.manifests()
      } catch (e: SkillBillRuntimeException) {
        e.rethrowUnless(e.isShellContentContractFailure())
        return QaCapabilityResolution.Incompatible(
          "Installed platform pack discovery failed: ${e.message ?: e.javaClass.simpleName}.",
        )
      }
    val routed = routedConcretePacks(manifests, changedPaths)
    val targets =
      routed.sortedBy(PlatformManifest::slug).mapNotNull { pack ->
        pack.qaCapability?.let { QaDeclaredTarget(pack.slug, it.platforms, pack.packRoot.resolve(it.guidanceFile)) }
      }
    return if (targets.isEmpty()) {
      QaCapabilityResolution.Absent(routed.singleOrNull()?.slug)
    } else {
      QaCapabilityResolution.Declared(targets)
    }
  }
}
