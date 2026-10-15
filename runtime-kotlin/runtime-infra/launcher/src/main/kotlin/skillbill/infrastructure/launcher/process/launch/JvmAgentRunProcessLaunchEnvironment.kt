package skillbill.infrastructure.launcher.process.launch

import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.infrastructure.host.jvm.GateJvmResolver
import skillbill.infrastructure.host.jvm.applyTo

internal fun configureLaunchEnvironment(
  builder: ProcessBuilder,
  request: AgentRunProcessRequest,
  gateJvmResolver: GateJvmResolver,
) {
  if (!request.environmentFields.inheritEnvironment) {
    val isolated =
      isolatedLaunchEnvironment(
        builder.environment(),
        request.environmentFields.environment,
        request.environmentFields.environmentPassthroughKeys,
      )
    builder.environment().clear()
    builder.environment().putAll(isolated)
  } else {
    builder.environment().putAll(request.environmentFields.environment)
  }
  val environment = builder.environment()
  gateJvmResolver.resolve(environment).applyTo(environment)
}

internal fun isolatedLaunchEnvironment(
  parentEnvironment: Map<String, String>,
  overrides: Map<String, String>,
  additionalPassthroughKeys: Set<String> = emptySet(),
): Map<String, String> =
  parentEnvironment.filterKeys {
    it in ISOLATED_LAUNCH_PASSTHROUGH_KEYS || it in additionalPassthroughKeys
  } + overrides

internal val ISOLATED_LAUNCH_PASSTHROUGH_KEYS: Set<String> =
  setOf(
    "HOME",
    "PATH",
    "USER",
    "LOGNAME",
    "SHELL",
    "LANG",
    "LC_ALL",
    "TMPDIR",
    "XDG_CONFIG_HOME",
    "XDG_DATA_HOME",
    "XDG_CACHE_HOME",
  ) + AgentHomeEnvironmentKeys.ALL
