package skillbill.di.core

import me.tatarka.inject.annotations.Provides
import skillbill.contracts.time.JvmSystemClock
import skillbill.di.core.scheduler.StandaloneOutputSchedulerProvides
import skillbill.infrastructure.host.JdkBoundedWorkFanOutPort
import skillbill.infrastructure.host.JdkDaemonThreadPort
import skillbill.infrastructure.host.JdkIdentifierGeneratorPort
import skillbill.infrastructure.host.JdkRuntimeDiagnostics
import skillbill.infrastructure.host.JdkRuntimeTimingPort
import skillbill.infrastructure.host.JdkShutdownHookPort
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.DaemonThreadPort
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.time.RuntimeTimingPort
import java.time.Clock
import kotlin.random.Random
import kotlin.time.TimeSource

internal interface RuntimeDiagnosticsProvides : StandaloneOutputSchedulerProvides {
  @Provides
  fun runtimeDiagnostics(
    callbacks: OptionalCallbacks,
    adapter: JdkRuntimeDiagnostics,
  ): RuntimeDiagnostics = callbacks.runtimeDiagnostics ?: adapter

  @Provides
  fun runtimeTimingPort(
    callbacks: OptionalCallbacks,
    adapter: JdkRuntimeTimingPort,
  ): RuntimeTimingPort = callbacks.runtimeTimingPort ?: adapter

  @Provides
  fun shutdownHookPort(adapter: JdkShutdownHookPort): ShutdownHookPort = adapter

  @Provides
  fun daemonThreadPort(adapter: JdkDaemonThreadPort): DaemonThreadPort = adapter

  @Provides
  fun identifierGeneratorPort(adapter: JdkIdentifierGeneratorPort): IdentifierGeneratorPort = adapter

  @Provides
  fun boundedWorkFanOutPort(adapter: JdkBoundedWorkFanOutPort): BoundedWorkFanOutPort = adapter

  @Provides
  fun hostPlatformPort(callbacks: OptionalCallbacks): HostPlatformPort =
    callbacks.hostPlatformPort ?: JdkHostPlatformPort

  @Provides
  fun runtimeClock(): Clock = JvmSystemClock

  @Provides
  fun runtimeRandom(): Random = Random.Default

  @Provides
  fun runtimeTimeSource(): TimeSource = TimeSource.Monotonic
}
