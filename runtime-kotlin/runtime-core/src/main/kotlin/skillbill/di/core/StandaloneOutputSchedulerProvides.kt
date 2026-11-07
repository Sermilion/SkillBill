package skillbill.di.core

import me.tatarka.inject.annotations.Provides
import skillbill.application.runtime.RuntimeSingleton
import skillbill.infrastructure.host.JdkStandaloneOutputCaptureScheduler
import skillbill.ports.idestatus.StandaloneOutputCaptureScheduler

internal interface StandaloneOutputSchedulerProvides {
  @Provides @RuntimeSingleton
  fun standaloneOutputCaptureScheduler(
    adapter: JdkStandaloneOutputCaptureScheduler,
  ): StandaloneOutputCaptureScheduler? = adapter
}
