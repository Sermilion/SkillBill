package dev.skillbill.tray.core.data.signal

import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.data.port.CommitSignalSource
import dev.skillbill.tray.core.data.port.SignalTopics
import dev.skillbill.tray.core.data.port.StoreChange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.persistence.DesktopReadSchema
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path

/** Committed-write signals read from the runtime's sidecar file; the store file itself is never watched or polled. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DesktopCommitSignalSource(private val diagnostics: Diagnostics) : CommitSignalSource {
  override val topics: SignalTopics = SignalTopics(
    all = DesktopReadSchema.TOPICS.toSet(),
    goals = DesktopReadSchema.GOALS_TOPIC,
    phases = DesktopReadSchema.PHASES_TOPIC,
    activity = DesktopReadSchema.ACTIVITY_TOPIC,
  )

  override fun changes(databasePath: String, ready: CompletableDeferred<Unit>): Flow<StoreChange> =
    DesktopCommitSignalObserver(Path.of(databasePath), diagnostics) { NativeCommitSignalWatch(it) }.changes(ready)
}
