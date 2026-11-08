package dev.skillbill.desktop.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.skillbill.desktop.core.domain.usage.StoredUsageReading
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class DataStoreLastUsageStoreTest {
  @TempDir
  lateinit var directory: File

  private val observedAt = Instant.fromEpochMilliseconds(1_700_000_000_123)
  private val fiveHour = UsageMeter.create(
    "claude:five_hour", "5 hour", UsageMeterScope.General, observedAt, Instant.fromEpochSeconds(1_700_010_000),
    UsagePeriod.Rolling.create(5 * 3600)!!, UsageValue.PercentageUsed.create(7.5)!!,
  )!!
  private val opusWeekly = UsageMeter.create(
    "claude:seven_day_opus", "Weekly", UsageMeterScope.Model("Opus"), observedAt, null,
    UsagePeriod.Unknown, UsageValue.PercentageUsed.create(81.0)!!,
  )!!

  @Test
  fun `the last agent and a reading survive a new store on the same file`() = runTest {
    val file = File(directory, "usage.preferences_pb")
    val reading = StoredUsageReading(RequestPermission.Allowed, true, observedAt, listOf(fiveHour, opusWeekly))
    // One DataStore per file at a time: the first one's scope ends before the file opens again, as on an app restart.
    val firstRun = Job(backgroundScope.coroutineContext[Job])
    val first = DataStoreLastUsageStore(
      PreferenceDataStoreFactory.create(scope = CoroutineScope(backgroundScope.coroutineContext + firstRun)) { file },
      RecordingDiagnostics(),
    )
    first.setLastAgent("claude")
    first.setLastReading(UsageProvider.Claude, "/home/operator/.claude", reading)
    firstRun.cancelAndJoin()

    val reopened = DataStoreLastUsageStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file }, RecordingDiagnostics())

    assertEquals("claude", reopened.lastAgent())
    assertEquals(reading, reopened.lastReading(UsageProvider.Claude, "/home/operator/.claude"))
    assertNull(reopened.lastReading(UsageProvider.Claude, "/home/operator/.claude-work"))
    assertNull(reopened.lastReading(UsageProvider.Claude, null))
  }

  @Test
  fun `an older reading never replaces a newer one for the same profile`() = runTest {
    val store = DataStoreLastUsageStore(
      PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "order.preferences_pb") },
      RecordingDiagnostics(),
    )
    val newer = StoredUsageReading(RequestPermission.Allowed, false, observedAt, listOf(fiveHour))
    val older = StoredUsageReading(RequestPermission.Rejected, false, Instant.fromEpochMilliseconds(1_000), listOf(opusWeekly))
    val otherProfile = StoredUsageReading(RequestPermission.Allowed, false, Instant.fromEpochMilliseconds(1_000), listOf(opusWeekly))

    store.setLastReading(UsageProvider.Claude, null, newer)
    store.setLastReading(UsageProvider.Claude, null, older)
    store.setLastReading(UsageProvider.Claude, "/other", otherProfile)

    assertEquals(newer, store.lastReading(UsageProvider.Claude, null))
    assertEquals(otherProfile, store.lastReading(UsageProvider.Claude, "/other"))
  }

  @Test
  fun `unreadable stored readings are skipped`() = runTest {
    val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "bad.preferences_pb") }
    data.edit { it[AppPreferenceKeys.USAGE_LAST_READINGS] = setOf("v1;garbage", "v2;6;claude") }

    assertNull(DataStoreLastUsageStore(data, RecordingDiagnostics()).lastReading(UsageProvider.Claude, null))
  }

  @Test
  fun `the encoding drops meters it cannot restore and rejects trailing data`() {
    val billing = UsageMeter.create(
      "cursor:billing", "Billing", UsageMeterScope.General, observedAt, null,
      UsagePeriod.BillingBoundaries(null, null), UsageValue.PercentageUsed.create(10.0)!!,
    )!!
    val entry = StoredUsageEntry(
      UsageProvider.Claude, "", StoredUsageReading(RequestPermission.Unknown, false, observedAt, listOf(fiveHour, billing)),
    )
    val encoded = UsageReadingEncoding.encode(entry)

    assertEquals(entry.copy(reading = entry.reading.copy(meters = listOf(fiveHour))), UsageReadingEncoding.decode(encoded))
    assertNull(UsageReadingEncoding.decode(encoded + "1;x"))
  }
}
