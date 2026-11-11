package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.usage.UsageObservationSink
import dev.skillbill.desktop.core.domain.usage.UsageRepository
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@ContributesTo(AppScope::class)
interface UsageBindings {
  @Provides
  fun claudeUsageReader(collector: ClaudeUsageCollector): ClaudeUsageReader = collector

  @Provides
  fun usageScheduler(): UsageScheduler = CoroutineUsageScheduler

  @Provides
  fun usageJitter(): UsageJitter = RandomUsageJitter

  @Provides
  @SingleIn(AppScope::class)
  fun usageRepository(repository: ProviderUsageRepository): UsageRepository = repository

  @Provides
  @SingleIn(AppScope::class)
  fun usageObservationSink(repository: ProviderUsageRepository): UsageObservationSink = repository
}
