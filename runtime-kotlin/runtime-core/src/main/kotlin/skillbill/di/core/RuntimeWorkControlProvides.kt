package skillbill.di.core

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.launcher.work.DetachedWorkRunLauncher
import skillbill.ports.work.WorkRunLauncher

internal interface RuntimeWorkControlProvides {
  @Provides
  fun workRunLauncher(
    callbacks: OptionalCallbacks,
    adapter: DetachedWorkRunLauncher,
  ): WorkRunLauncher = callbacks.workRunLauncher ?: adapter
}
