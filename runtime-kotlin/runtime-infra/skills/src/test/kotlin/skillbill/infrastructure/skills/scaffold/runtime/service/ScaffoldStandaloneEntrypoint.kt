package skillbill.infrastructure.skills.scaffold.runtime.service

import skillbill.infrastructure.skills.scaffold.adapters.FileSystemScaffoldRepoValidation
import skillbill.infrastructure.skills.scaffold.adapters.FileSystemScaffoldSourceLoader
import skillbill.infrastructure.skills.scaffold.payload.toRawScaffoldPayload
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.model.command.ScaffoldCommandRequest

fun scaffold(
  payload: Map<String, Any?>,
  dryRun: Boolean = false,
): ScaffoldResult {
  val repoValidation = FileSystemScaffoldRepoValidation()
  val sourceLoader = FileSystemScaffoldSourceLoader()
  val seams =
    ScaffoldAdapterSeams(
      validateScaffold = { plan, repoRoot -> repoValidation.validateScaffold(plan, repoRoot) },
      optionalBaselineLayers = { p, r, np -> repoValidation.optionalBaselineLayers(p, r, np) },
      resolveAddonConsumerSkillDirs = { p, pr, pk -> sourceLoader.resolveAddonConsumerSkillDirs(p, pr, pk) },
      performInstall = { _, _, _ -> ScaffoldInstallOutcome(emptyList(), emptyList()) },
      rollbackInstallTargets = { _, _ -> },
    )
  return scaffoldWithAdapters(payload, dryRun, seams)
}

fun scaffold(
  request: ScaffoldCommandRequest,
  dryRun: Boolean = false,
): ScaffoldResult = scaffold(request.toRawScaffoldPayload(), dryRun)
