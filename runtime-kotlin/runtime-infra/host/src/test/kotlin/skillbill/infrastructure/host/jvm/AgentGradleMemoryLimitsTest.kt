package skillbill.infrastructure.host.jvm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentGradleMemoryLimitsTest {
  @Test
  fun `an operator's own Gradle heap and options survive while the missing limits are added`() {
    val environment =
      AgentGradleMemoryLimits.environment(
        mapOf(AgentGradleMemoryLimits.GRADLE_OPTS to "-Dhttps.proxyHost=proxy -Dorg.gradle.jvmargs=-Xmx12g"),
      )

    assertEquals(
      "-Dhttps.proxyHost=proxy -Dorg.gradle.jvmargs=-Xmx12g -Dorg.gradle.workers.max=4 " +
        "-Dorg.gradle.project.kotlin.daemon.jvmargs=\"-Xmx3g -Xms256m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8\"",
      environment[AgentGradleMemoryLimits.GRADLE_OPTS],
    )
  }

  @Test
  fun `a nested run that already carries every limit does not stack them again`() {
    val first = AgentGradleMemoryLimits.environment(emptyMap())

    assertEquals(emptyMap<String, String>(), AgentGradleMemoryLimits.environment(first))
  }
}
