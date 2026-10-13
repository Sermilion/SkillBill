package dev.skillbill.tray.infrastructure.data

import dev.skillbill.tray.domain.HistoryFilter
import dev.skillbill.tray.domain.Retention
import dev.skillbill.tray.domain.WorkSection
import dev.skillbill.tray.infrastructure.storage.candidateQuery
import dev.skillbill.tray.infrastructure.storage.openWorkRoomDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class LiveDatabaseProbeTest {
  @Test
  fun probe() = runBlocking {
    val path = Path.of("/home/sermilion/.skill-bill/review-metrics.db")
    val db = openWorkRoomDatabase(path)
    try {
      println("latest migration=${db.migrations().latest()}")
      val now = Instant.now()
      for (section in WorkSection.entries) {
        try {
          val query = candidateQuery(section, now.minus(Retention.WEEK.duration).toString(), now.toString(), null, null, "", 10, HistoryFilter.ALL)
          println("$section rows=${db.candidates().candidates(query.roomQuery).size}")
        } catch (failure: Throwable) {
          println("$section failed: ${failure.javaClass.name}: ${failure.message}")
          failure.printStackTrace()
        }
      }
    } finally {
      db.close()
    }
  }
}
