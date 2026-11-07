package skillbill.infrastructure.host.jvm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentGradleUserHomeTest {
  @Test
  fun `agents are pinned to the machine's Gradle user home and keep the operator's other options`() {
    val environment =
      AgentGradleUserHome.environment(
        launch = mapOf(AgentGradleUserHome.GRADLE_OPTS to "-Dhttps.proxyHost=proxy"),
        machine = mapOf(AgentGradleUserHome.GRADLE_USER_HOME to "/cache/gradle", AgentGradleUserHome.HOME to "/home/u"),
      )

    assertEquals(
      "-Dhttps.proxyHost=proxy -Dgradle.user.home=/cache/gradle",
      environment[AgentGradleUserHome.GRADLE_OPTS],
    )
  }

  @Test
  fun `without a machine GRADLE_USER_HOME the pin is Gradle's default under a home that may contain spaces`() {
    val environment = AgentGradleUserHome.environment(emptyMap(), mapOf(AgentGradleUserHome.HOME to "/home/a b"))

    assertEquals("-Dgradle.user.home=\"/home/a b/.gradle\"", environment[AgentGradleUserHome.GRADLE_OPTS])
  }

  @Test
  fun `a Gradle user home the operator already pinned in GRADLE_OPTS is left alone`() {
    val launch = mapOf(AgentGradleUserHome.GRADLE_OPTS to "-Dgradle.user.home=/elsewhere")

    assertEquals(
      emptyMap<String, String>(),
      AgentGradleUserHome.environment(launch, mapOf(AgentGradleUserHome.HOME to "/home/u")),
    )
  }
}
