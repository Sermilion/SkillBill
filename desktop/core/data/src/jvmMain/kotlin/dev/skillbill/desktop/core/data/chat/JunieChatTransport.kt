package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Runs each turn as one `junie --acp` process; see [FreshAcpChatTransport]. */
@Inject
@SingleIn(AppScope::class)
class JunieChatTransport(
  host: HostEnvironment,
  store: ChatStore,
  diagnostics: Diagnostics,
  liveLauncher: LiveChatProcessLauncher,
  approvalAuthority: ChatApprovalAuthority? = null,
) : FreshAcpChatTransport(host, store, diagnostics, liveLauncher, approvalAuthority, AcpProvider.Junie)
