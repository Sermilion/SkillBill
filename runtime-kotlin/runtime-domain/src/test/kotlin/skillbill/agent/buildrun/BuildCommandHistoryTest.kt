package skillbill.agent.buildrun

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BuildCommandHistoryTest {
  @Test
  fun `logging variants share history while workload options and literal arguments stay distinct`() {
    val baseline = buildCommandHistoryKey("./gradlew :app:check")
    assertEquals(baseline, buildCommandHistoryKey("gradlew --console plain :app:check -q --warning-mode=none"))
    listOf(
      "./gradlew :other:check",
      "./gradlew clean :app:check",
      "./gradlew :app:check --rerun-tasks",
      "./gradlew :app:check -p nested",
      "./gradlew :app:check -- --info",
      "./gradlew :app:check -Pmessage='value --info'",
    ).forEach { assertNotEquals(baseline, buildCommandHistoryKey(it)) }
    assertNotEquals(buildCommandHistoryKey("npm run check"), buildCommandHistoryKey("npm run check -q"))
  }
}
