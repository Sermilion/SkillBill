package skillbill.infrastructure.host.system

import skillbill.error.apprun.AppRunFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.apprun.AppRunConfigContractValidator
import skillbill.ports.apprun.model.AppRunConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileSystemAppRunConfigTest {
  private val adapter = FileSystemAppRunConfig(AppRunConfigContractValidator { _, _ -> })

  @Test
  fun `missing file defaults to debug and explicit module strips one leading colon`() {
    val missingRoot = Files.createTempDirectory("skillbill-app-run-missing")
    assertEquals(AppRunConfig(null, "debug"), adapter.read(missingRoot))

    val root = Files.createTempDirectory("skillbill-app-run-config")
    Files.createDirectories(root.resolve(".skill-bill"))
    Files.writeString(root.resolve(".skill-bill/app-run.yaml"), "contract_version: '0.1'\nmodule: ':apps:android'\n")
    assertEquals(AppRunConfig("apps:android", "debug"), adapter.read(root))
  }

  @Test
  fun `malformed yaml and unsupported version fail with distinct codes`() {
    val root = Files.createTempDirectory("skillbill-app-run-invalid")
    val path = root.resolve(".skill-bill/app-run.yaml")
    Files.createDirectories(path.parent)
    Files.writeString(path, "contract_version: [broken")
    assertEquals(
      AppRunFailureCode.INVALID_APP_RUN_CONFIG,
      assertFailsWith<SkillBillRuntimeException> { adapter.read(root) }.code,
    )

    Files.writeString(path, "contract_version: '0.2'\n")
    assertEquals(
      AppRunFailureCode.UNSUPPORTED_APP_RUN_CONFIG_VERSION,
      assertFailsWith<SkillBillRuntimeException> { adapter.read(root) }.code,
    )
  }
}
