package skillbill.infrastructure.skills.scaffold.runtime.service.externalpack

import skillbill.error.core.externalPlatformPackConfig
import skillbill.infrastructure.skills.scaffold.runtime.service.PACK_REGISTRATION_CREATE
import skillbill.infrastructure.skills.scaffold.runtime.service.PACK_REGISTRATION_REGISTER
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldPlan
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldRuntimeContext
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldTransaction
import skillbill.install.model.ExternalPlatformPackSource
import skillbill.model.toPath
import skillbill.ports.install.platformpack.model.ExternalPlatformPackRootRequest
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceConfigRequest
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceRegistrationRequest
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceUnregisterRequest
import skillbill.ports.install.platformpack.model.PlatformPackCatalogRequest
import skillbill.ports.repository.toFileLocation
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

internal fun registerPlannedExternalPlatformPack(
  plan: ScaffoldPlan,
  txn: ScaffoldTransaction,
  repoRoot: Path,
  runtime: ScaffoldRuntimeContext,
) {
  val root = plan.externalPackRoot ?: return
  val mode = plan.externalPackRegistrationMode ?: return
  if (mode != PACK_REGISTRATION_CREATE && mode != PACK_REGISTRATION_REGISTER) {
    return
  }
  val loader =
    runtime.catalogLoader ?: throw externalPlatformPackConfig(
      "External platform pack registration requires a catalog loader.",
    )
  val store =
    runtime.packSourceConfig ?: throw externalPlatformPackConfig(
      "External platform pack registration requires a source config store.",
    )
  val normalizedRoot = root.toAbsolutePath().normalize()
  loader.assertRegistrableExternalPack(
    ExternalPlatformPackRootRequest(
      packRoot = normalizedRoot,
      catalog =
        PlatformPackCatalogRequest(
          repoRoot = repoRoot,
          userHome = runtime.userHome,
          environment = runtime.environment,
        ),
    ),
  )
  val before =
    store.readExternalPlatformPackSources(
      ExternalPlatformPackSourceConfigRequest(userHome = runtime.userHome, environment = runtime.environment),
    ).sources
  val source = ExternalPlatformPackSource(normalizedRoot.toFileLocation())
  store.registerExternalPlatformPackSource(
    ExternalPlatformPackSourceRegistrationRequest(
      userHome = runtime.userHome,
      environment = runtime.environment,
      source = source,
    ),
  )
  val alreadyPresent =
    before.any { existing ->
      existing.path.toPath().toAbsolutePath().normalize() == normalizedRoot
    }
  if (!alreadyPresent) {
    txn.registeredExternalPackRoot = normalizedRoot
    txn.externalPackConfigHome = runtime.userHome
    txn.externalPackConfigEnvironment = runtime.environment
    txn.packSourceConfig = store
  }
}

internal fun rollbackRegisteredExternalPlatformPack(
  txn: ScaffoldTransaction,
  errors: MutableList<String>,
) {
  val root = txn.registeredExternalPackRoot ?: return
  val home = txn.externalPackConfigHome ?: return
  val store = txn.packSourceConfig ?: return
  val failure =
    runCatching {
      store.unregisterExternalPlatformPackSource(
        ExternalPlatformPackSourceUnregisterRequest(
          userHome = home,
          environment = txn.externalPackConfigEnvironment,
          source = ExternalPlatformPackSource(root.toFileLocation()),
        ),
      )
    }.exceptionOrNull() ?: return
  if (failure is CancellationException) throw failure
  errors += "external platform pack registration $root: ${failure.message}"
}
