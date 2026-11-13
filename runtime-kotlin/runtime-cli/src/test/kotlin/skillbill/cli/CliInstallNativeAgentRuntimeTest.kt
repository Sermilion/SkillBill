package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSource
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CliInstallNativeAgentRuntimeTest {
  @Test
  fun `native subagent commands link and unlink authored agent files`() {
    val fixture = installFixture()

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertGeneratedAgentLinked(fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}"), fixture.codexToml)

    assertEquals(0, runInstall(fixture, "unlink-codex-agents").exitCode)
    assertFalse(Files.exists(fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")))
  }

  @Test
  fun `cursor native subagent commands link and unlink authored agent files`() {
    val fixture = installFixture()
    val target = fixture.home.resolve(".cursor/agents/${fixture.cursorMd.fileName}")

    assertEquals(0, runInstall(fixture, "link-cursor-agents").exitCode)
    assertGeneratedAgentLinked(target, fixture.cursorMd)

    assertEquals(0, runInstall(fixture, "unlink-cursor-agents").exitCode)
    assertFalse(Files.exists(target))
  }

  @Test
  fun `cursor agents path prints the cursor agents directory`() {
    val fixture = installFixture()

    val result =
      CliRuntime.run(
        listOf("install", "cursor-agents-path"),
        installCliContext(fixture.home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, fixture.home.resolve(".cursor/agents").toString())
  }

  @Test
  fun `cursor native subagent link is idempotent across repeated applies`() {
    val fixture = installFixture()
    val target = fixture.home.resolve(".cursor/agents/${fixture.cursorMd.fileName}")

    assertEquals(0, runInstall(fixture, "link-cursor-agents").exitCode)
    val first = target.toRealPath()
    assertEquals(0, runInstall(fixture, "link-cursor-agents").exitCode)

    assertGeneratedAgentLinked(target, fixture.cursorMd)
    assertEquals(first, target.toRealPath())
  }

  @Test
  fun `cursor native subagent link preserves user-authored target files`() {
    val fixture = installFixture()
    val target = fixture.home.resolve(".cursor/agents/${fixture.cursorMd.fileName}")
    Files.createDirectories(target.parent)
    Files.writeString(target, "user cursor file\n")

    assertEquals(0, runInstall(fixture, "link-cursor-agents").exitCode)

    assertFalse(Files.isSymbolicLink(target))
    assertEquals("user cursor file\n", Files.readString(target))
  }

  @Test
  fun `cursor native subagent link replaces stale install cache symlinks`() {
    val fixture = installFixture()
    val target = fixture.home.resolve(".cursor/agents/${fixture.cursorMd.fileName}")
    val oldCache = fixture.home.resolve(".skill-bill/native-agents/old-cache-key")
    val stale = oldCache.resolve("cursor-agents/${fixture.cursorMd.fileName}")
    Files.createDirectories(stale.parent)
    Files.writeString(stale, "stale cache artifact\n")
    Files.createDirectories(target.parent)
    Files.createSymbolicLink(target, stale)

    assertEquals(0, runInstall(fixture, "link-cursor-agents").exitCode)

    assertGeneratedAgentLinked(target, fixture.cursorMd)
    assertFalse(target.toRealPath().startsWith(oldCache))
  }

  @Test
  fun `claude native subagent commands link and unlink authored agent files`() {
    val fixture = installFixture()
    val sourcePath =
      fixture.codexToml.parent.parent
        .resolve("native-agents/${fixture.codexToml.fileName.toString().removeSuffix(".toml")}.md")
    val source = parseNativeAgentSource(sourcePath)
    val target = fixture.home.resolve(".claude/agents/${source.name}.md")

    assertEquals(0, runInstall(fixture, "link-claude-agents").exitCode)

    assertGeneratedAgentLinked(target, expected = null)
    assertEquals(NativeAgentProvider.Claude.render(source), Files.readString(target))

    assertEquals(0, runInstall(fixture, "unlink-claude-agents").exitCode)
    assertFalse(Files.exists(target))
  }

  @Test
  fun `native subagent commands only link selected platforms`() {
    val fixture = installFixture()

    assertEquals(0, runInstall(fixture, "link-codex-agents", "--platform", "kmp").exitCode)
    assertEquals(0, runInstall(fixture, "link-cursor-agents", "--platform", "kmp").exitCode)

    assertGeneratedAgentLinked(
      fixture.home.resolve(".codex/agents/${fixture.baseCodexToml.fileName}"),
      fixture.baseCodexToml,
    )
    assertGeneratedAgentLinked(
      fixture.home.resolve(".cursor/agents/${fixture.baseCursorMd.fileName}"),
      fixture.baseCursorMd,
    )
    assertGeneratedAgentLinked(
      fixture.home.resolve(".codex/agents/${fixture.kmpCodexToml.fileName}"),
      fixture.kmpCodexToml,
    )
    assertGeneratedAgentLinked(
      fixture.home.resolve(".cursor/agents/${fixture.kmpCursorMd.fileName}"),
      fixture.kmpCursorMd,
    )
    assertFalse(Files.exists(fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")))
    assertFalse(Files.exists(fixture.home.resolve(".cursor/agents/${fixture.cursorMd.fileName}")))
  }

  @Test
  fun `native subagent cleanup can remove stale cursor links from unselected platforms`() {
    val fixture = installFixture()

    assertEquals(0, runInstall(fixture, "link-cursor-agents").exitCode)
    assertGeneratedAgentLinked(
      fixture.home.resolve(".cursor/agents/${fixture.baseCursorMd.fileName}"),
      fixture.baseCursorMd,
    )
    assertGeneratedAgentLinked(fixture.home.resolve(".cursor/agents/${fixture.cursorMd.fileName}"), fixture.cursorMd)
    assertGeneratedAgentLinked(
      fixture.home.resolve(".cursor/agents/${fixture.kmpCursorMd.fileName}"),
      fixture.kmpCursorMd,
    )

    assertEquals(0, runInstall(fixture, "unlink-cursor-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-cursor-agents", "--platform", "kmp").exitCode)

    assertGeneratedAgentLinked(
      fixture.home.resolve(".cursor/agents/${fixture.baseCursorMd.fileName}"),
      fixture.baseCursorMd,
    )
    assertFalse(Files.exists(fixture.home.resolve(".cursor/agents/${fixture.cursorMd.fileName}")))
    assertGeneratedAgentLinked(
      fixture.home.resolve(".cursor/agents/${fixture.kmpCursorMd.fileName}"),
      fixture.kmpCursorMd,
    )
  }
}
