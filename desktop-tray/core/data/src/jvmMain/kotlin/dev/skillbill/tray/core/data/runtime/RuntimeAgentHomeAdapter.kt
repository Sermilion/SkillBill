package dev.skillbill.tray.core.data.runtime

import dev.skillbill.tray.core.common.HostEnvironment
import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.port.RuntimeAgentHomeGateway
import me.tatarka.inject.annotations.Inject
import skillbill.di.core.RuntimeComponent
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path

/** The Claude config roots the installer also targets, so the settings list matches where skills are installed. */
@Inject
@SingleIn(DatabaseSessionScope::class)
@ContributesBinding(DatabaseSessionScope::class)
class RuntimeAgentHomeAdapter(private val runtime: RuntimeComponent, private val host: HostEnvironment) : RuntimeAgentHomeGateway {
  override fun claudeConfigDirectories(): List<String> =
    runtime.installAgentService.claudeRoots(Path.of(host.userHome), host.variables).map(Path::toString)
}
