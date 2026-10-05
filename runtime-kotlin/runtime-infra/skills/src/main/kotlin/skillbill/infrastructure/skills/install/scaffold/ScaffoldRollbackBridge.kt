package skillbill.infrastructure.skills.install.scaffold

import skillbill.infrastructure.skills.install.plan.uninstallTargets
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldTransaction
import java.io.IOException
import java.io.UncheckedIOException

internal fun rollbackScaffoldInstallTargets(
  txn: ScaffoldTransaction,
  errors: MutableList<String>,
) {
  recordRollbackFailure(errors, "install rollback") {
    uninstallTargets(txn.installTargets)
  }
}

private fun recordRollbackFailure(
  errors: MutableList<String>,
  label: String,
  action: () -> Unit,
) {
  try {
    action()
  } catch (error: IOException) {
    errors += "$label: ${error.message}"
  } catch (error: UncheckedIOException) {
    errors += "$label: ${error.message}"
  }
}
