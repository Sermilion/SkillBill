package skillbill.application.scaffold

import skillbill.application.install.ExternalAddonOverlayService
import skillbill.application.scaffold.model.ScaffoldInvocationArgs
import skillbill.application.scaffold.model.ScaffoldInvocationOutcome
import skillbill.error.core.failureCodeLabel
import skillbill.install.model.ExternalAddonSource
import skillbill.ports.repository.toFileLocation
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val SCAFFOLD_SESSION_SUFFIX_LENGTH = 4

private fun generateScaffoldSessionId(clock: Clock): String {
  val date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
  val suffix = UUID.randomUUID().toString().take(SCAFFOLD_SESSION_SUFFIX_LENGTH)
  return "nss-$date-$suffix"
}

fun runScaffoldInvocation(
  scaffoldGateway: ScaffoldGateway,
  args: ScaffoldInvocationArgs,
): ScaffoldInvocationOutcome {
  val sessionId = generateScaffoldSessionId(args.clock)
  val decoded =
    when {
      args.payload != null -> decodeScaffoldCommandRequest(args.payload)
      args.payloadText != null -> decodeScaffoldCommandRequest(args.payloadText)
      else -> error("Scaffold invocation requires payload text or a JSON object.")
    }
  val request = decoded.withResolvedRepoRoot(args.invocationRepositoryRoot)
  val scaffoldResult = scaffoldGateway.scaffold(request, dryRun = args.dryRun)
  val registrationFailure =
    if (args.registerExternalSources) {
      registerExternalAddonSourceAfterSuccess(
        request = request,
        dryRun = args.dryRun,
        userHome = args.userHome,
        environment = args.environment,
        externalAddonOverlayService = args.externalAddonOverlayService,
      )
    } else {
      null
    }
  return ScaffoldInvocationOutcome(
    sessionId = sessionId,
    scaffoldResult = scaffoldResult,
    registrationFailure = registrationFailure,
  )
}

private fun ScaffoldCommandRequest.withResolvedRepoRoot(invocationRepositoryRoot: Path): ScaffoldCommandRequest {
  val resolved =
    repoRoot?.takeIf(String::isNotBlank) ?: invocationRepositoryRoot.toString()
  return when (this) {
    is ScaffoldCommandRequest.HorizontalSkill -> copy(repoRoot = resolved)
    is ScaffoldCommandRequest.PlatformPack -> copy(repoRoot = resolved)
    is ScaffoldCommandRequest.AddOn -> copy(repoRoot = resolved)
    is ScaffoldCommandRequest.AgentAddon -> copy(repoRoot = resolved)
    is ScaffoldCommandRequest.PlatformOverride -> copy(repoRoot = resolved)
    is ScaffoldCommandRequest.CodeReviewArea -> copy(repoRoot = resolved)
  }
}

private fun registerExternalAddonSourceAfterSuccess(
  request: ScaffoldCommandRequest,
  dryRun: Boolean,
  userHome: Path,
  environment: Map<String, String>,
  externalAddonOverlayService: ExternalAddonOverlayService?,
): String? {
  if (externalAddonOverlayService == null || dryRun) return null
  val addOn = request as? ScaffoldCommandRequest.AddOn ?: return null
  val sourcePath = addOn.addonLocationPath?.takeIf(String::isNotBlank) ?: return null
  return runCatching {
    externalAddonOverlayService.registerSource(
      home = userHome,
      source = ExternalAddonSource(Path.of(sourcePath).toFileLocation(), addOn.platform),
      environment = environment,
    )
  }.exceptionOrNull()?.let { error ->
    error.message?.takeIf(String::isNotBlank)
      ?: error.failureCodeLabel()
      ?: error::class.simpleName
      ?: "registration failed"
  }
}
