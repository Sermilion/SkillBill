package dev.skillbill.desktop.app

import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.usage.model.UsageDemand
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshResult
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class DevinUsageWiringTest {
  @Test
  fun `production graph reads Devin credentials instead of leaving its tab loading`() = runTest {
    val home = createTempDirectory("devin-graph")
    val component = AppComponent::class.create(HostEnvironment(emptyMap(), home.toString(), ""), DiagnosticsSwitch(false))
    val repository = component.providerUsageRepository
    try {
      val request = UsageRequest(repository.profileLookup(UsageProvider.Devin, null, null))
      repository.acquireDemand(UsageDemand("devin-card", request, visible = true, active = false))
      val refresh = repository.refresh(request)
      assertNotEquals(UsageUnavailableCode.ProviderUnavailable, (refresh as? UsageRefreshResult.Disabled)?.reason?.code)
      assertIs<UsageResult.SignInRequired>(repository.observeDemand("devin-card").first { it is UsageResult.SignInRequired })
    } finally {
      repository.closeUsage()
      component.applicationCoroutineScope.scope.cancel()
      home.toFile().deleteRecursively()
    }
  }
}
