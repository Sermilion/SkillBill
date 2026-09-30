package skillbill.infrastructure.skills.install

import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.skills.install.plan.codexAgentsPath
import skillbill.infrastructure.skills.install.plan.codexConfigRoots
import skillbill.infrastructure.skills.install.plan.codexSkillTargets
import skillbill.infrastructure.skills.install.plan.detectAgents
import skillbill.infrastructure.skills.install.runtime.InstallOperations
import skillbill.model.toPath
import skillbill.ports.system.HostPlatformPort
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexConfigRootsTest {
  private fun markedProfile(
    home: Path,
    name: String,
  ): Path {
    val root = home.resolve(name)
    Files.createDirectories(root)
    Files.writeString(root.resolve("config.toml"), "model = \"test\"\n")
    return root
  }

  @Test
  fun `only default root when no named profiles exist`() {
    val home = Files.createTempDirectory("skillbill-codex-roots-default")
    Files.createDirectories(home.resolve(".codex"))

    assertEquals(
      listOf(home.resolve(".codex").toAbsolutePath().normalize()),
      codexConfigRoots(home, environment = emptyMap()),
    )
  }

  @Test
  fun `discovers and orders multiple named profiles after default`() {
    val home = Files.createTempDirectory("skillbill-codex-roots-multi")
    Files.createDirectories(home.resolve(".codex"))
    val openRouter = markedProfile(home, ".codex-or")
    val work = markedProfile(home, ".codex-work")

    val roots = codexConfigRoots(home, environment = emptyMap())

    assertEquals(
      listOf(
        home.resolve(".codex"),
        openRouter,
        work,
      ).map { it.toAbsolutePath().normalize() },
      roots,
    )
  }

  @Test
  fun `excludes files and unmarked profile directories`() {
    val home = Files.createTempDirectory("skillbill-codex-roots-filter")
    Files.createDirectories(home.resolve(".codex"))
    Files.createDirectories(home.resolve(".codex-empty"))
    val marked = markedProfile(home, ".codex-or")

    val roots = codexConfigRoots(home, environment = emptyMap())

    assertEquals(
      listOf(home.resolve(".codex"), marked).map { it.toAbsolutePath().normalize() },
      roots,
    )
  }

  @Test
  fun `unions distinct CODEX_HOME outside home and dedups when already present`() {
    val home = Files.createTempDirectory("skillbill-codex-roots-env")
    Files.createDirectories(home.resolve(".codex"))
    val outside = Files.createTempDirectory("skillbill-codex-roots-outside")
    Files.writeString(outside.resolve("config.toml"), "model = \"test\"\n")

    val roots = codexConfigRoots(home, environment = mapOf("CODEX_HOME" to outside.toString()))

    assertEquals(
      listOf(home.resolve(".codex").toAbsolutePath().normalize(), outside.toAbsolutePath().normalize()),
      roots,
    )
  }

  @Test
  fun `CODEX_HOME pointing at default root does not duplicate it`() {
    val home = Files.createTempDirectory("skillbill-codex-roots-env-default")
    val default = home.resolve(".codex")
    Files.createDirectories(default)

    val roots = codexConfigRoots(home, environment = mapOf("CODEX_HOME" to default.toString()))

    assertEquals(listOf(default.toAbsolutePath().normalize()), roots)
  }

  @Test
  fun `blank or unset CODEX_HOME contributes nothing extra`() {
    val home = Files.createTempDirectory("skillbill-codex-roots-blank")
    Files.createDirectories(home.resolve(".codex"))

    assertEquals(
      listOf(home.resolve(".codex").toAbsolutePath().normalize()),
      codexConfigRoots(home, environment = mapOf("CODEX_HOME" to "   ")),
    )
    assertEquals(
      listOf(home.resolve(".codex").toAbsolutePath().normalize()),
      codexConfigRoots(home, environment = emptyMap()),
    )
  }

  @Test
  fun `detection emits one codex target per resolved root`() {
    val home = Files.createTempDirectory("skillbill-codex-roots-detect")
    Files.createDirectories(home.resolve(".codex"))
    val openRouter = markedProfile(home, ".codex-or")

    val codexTargets = detectAgents(home, environment = emptyMap()).filter { it.name == "codex" }

    assertEquals(
      listOf(
        home.resolve(".codex/skills"),
        openRouter.resolve("skills"),
        home.resolve(".agents/skills"),
      ).map { it.toAbsolutePath().normalize() },
      codexTargets.map { it.path.toPath().toAbsolutePath().normalize() },
    )
  }

  @Test
  fun `agent-path stays single active root for backward compat`() {
    val home = Files.createTempDirectory("skillbill-codex-roots-agentpath")
    Files.createDirectories(home.resolve(".codex"))
    markedProfile(home, ".codex-or")
    val hostPlatform =
      object : HostPlatformPort by JdkHostPlatformPort {
        override fun resolveEnvironment(): Map<String, String> =
          mapOf("CODEX_HOME" to home.resolve(".codex-host").toString())
      }

    assertEquals(
      home.resolve(".codex/skills"),
      InstallOperations.agentPath("codex", home, environment = emptyMap(), hostPlatform = hostPlatform),
    )
    assertEquals(
      home.resolve(".codex/agents"),
      InstallOperations.codexAgentsPath(home, environment = emptyMap(), hostPlatform = hostPlatform),
    )
  }

  @Test
  fun `agent-path honors CODEX_HOME for named profiles`() {
    val home = Files.createTempDirectory("skillbill-codex-roots-active")
    val openRouter = markedProfile(home, ".codex-or")
    val env = mapOf("CODEX_HOME" to openRouter.toString())

    assertEquals(
      openRouter.resolve("skills"),
      InstallOperations.agentPath("codex", home, environment = env),
    )
    assertEquals(
      openRouter.resolve("agents"),
      InstallOperations.codexAgentsPath(home, environment = env),
    )
  }

  @Test
  fun `agents fallback when no codex roots exist`() {
    val home = Files.createTempDirectory("skillbill-codex-roots-agents-fallback")

    assertEquals(
      listOf(home.resolve(".agents/skills").toAbsolutePath().normalize()),
      codexSkillTargets(home, environment = emptyMap()),
    )
    assertTrue(codexConfigRoots(home, environment = emptyMap()).isEmpty())
  }

  @Test
  fun `skill targets always include agents skills alongside codex homes`() {
    val home = Files.createTempDirectory("skillbill-codex-roots-agents-always")
    Files.createDirectories(home.resolve(".codex"))
    val openRouter = markedProfile(home, ".codex-or")

    assertEquals(
      listOf(
        home.resolve(".codex/skills"),
        openRouter.resolve("skills"),
        home.resolve(".agents/skills"),
      ).map { it.toAbsolutePath().normalize() },
      codexSkillTargets(home, environment = emptyMap()),
    )
  }
}
