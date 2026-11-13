package skillbill.infrastructure.skills.install.plan

import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.resolveEnvironmentMap
import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.ports.install.mcp.model.InstallMcpProfileEnvironment
import skillbill.ports.system.HostPlatformPort
import java.nio.file.Path

internal fun resolveInstallHome(
  home: Path?,
  hostPlatform: HostPlatformPort = JdkHostPlatformPort,
): Path = resolveUserHome(home, hostPlatform)

internal fun resolveInstallEnvironment(
  environment: Map<String, String>,
  hostPlatform: HostPlatformPort = JdkHostPlatformPort,
): Map<String, String> {
  val resolved = resolveEnvironmentMap(environment, hostPlatform)
  return if (InstallMcpProfileEnvironment.PWD_ENV in resolved) {
    resolved
  } else {
    resolved + (InstallMcpProfileEnvironment.PWD_ENV to hostPlatform.resolveWorkingDirectory().toString())
  }
}
