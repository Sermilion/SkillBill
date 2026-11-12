package skillbill.infrastructure.skills.scaffold.runtime.service

import java.nio.file.Path

internal fun performInstall(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
  adapters: ScaffoldAdapterSeams,
): ScaffoldInstallOutcome = adapters.performInstall(txn, plan, repoRoot)
