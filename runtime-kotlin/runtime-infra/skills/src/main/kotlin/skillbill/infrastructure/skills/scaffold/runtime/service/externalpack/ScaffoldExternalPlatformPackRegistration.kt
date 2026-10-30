package skillbill.infrastructure.skills.scaffold.runtime.service.externalpack

import skillbill.error.core.ExternalPlatformPackConfigError
import skillbill.infrastructure.skills.scaffold.runtime.service.PACK_REGISTRATION_CREATE
import skillbill.infrastructure.skills.scaffold.runtime.service.PACK_REGISTRATION_REGISTER
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldPlan
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldRuntimeContext
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldTransaction
import skillbill.install.model.ExternalPlatformPackSource
import skillbill.ports.install.platformpack.model.ExternalPlatformPackRootRequest
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
    runtime.catalogLoader ?: throw ExternalPlatformPackConfigError(
      "External platform pack registration requires a catalog loader.",
    )
  val store =
    runtime.packSourceConfig ?: throw ExternalPlatformPackConfigError(
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
  val source = ExternalPlatformPackSource(normalizedRoot.toFileLocation())
  val registration =
    store.registerExternalPlatformPackSource(
      ExternalPlatformPackSourceRegistrationRequest(
        userHome = runtime.userHome,
        environment = runtime.environment,
        source = source,
      ),
    )
  if (registration.inserted) {
    txn.registeredExternalPackRoot = normalizedRoot
    txn.externalPackConfigHome = runtime.userHome
    txn.externalPackConfigEnvironment = runtime.environment
    txn.packSourceConfig = store
    txn.externalPackPreviousFieldState = registration.previousFieldState
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
          restoreEmptyFieldState = txn.externalPackPreviousFieldState,
        ),
      )
    }.exceptionOrNull() ?: return
  if (failure is CancellationException) throw failure
  errors += "external platform pack registration $root: ${failure.message}"
}
