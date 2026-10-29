package skillbill.infrastructure.host
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.model.EnvironmentContext
import java.nio.file.Path

internal fun EnvironmentContext.withProcessDefaults(): EnvironmentContext {
  val withUserHome =
    if (userHome == EnvironmentContext.UnspecifiedUserHome) {
      copy(userHome = resolveUserHome(null).toAbsolutePath().normalize())
    } else {
      copy(userHome = userHome.toAbsolutePath().normalize())
    }
  return if (withUserHome.environment === EnvironmentContext.UnspecifiedEnvironment) {
    withUserHome.copy(environment = JdkHostPlatformPort.resolveEnvironment())
  } else {
    withUserHome
  }
}

fun EnvironmentContext.skillBillStateDir(): Path =
  withProcessDefaults().let { resolveTelemetryStateDir(it.environment, it.userHome) }
