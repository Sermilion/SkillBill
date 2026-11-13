package skillbill.infrastructure.skills.install.nativeagent.link

import org.junit.jupiter.api.io.TempDir
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventoryEntry
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class InstallNativeAgentOperationsLinkTest {
  @Test
  fun `missing installed agent repair guidance uses public sona command`(
    @TempDir directory: Path,
  ) {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        verifyInstalledNativeAgent(
          NativeAgentLinkInventoryEntry(
            logicalName = "worker",
            provider = "codex",
            installedPath = directory.resolve("missing-agent.md"),
            cacheTargetPath = directory.resolve("cached-agent.md"),
            contentDigest = "digest",
            sourceRoot = directory,
          ),
        )
      }

    assertContains(error.message.orEmpty(), "Repair with: sona install apply")
  }
}
