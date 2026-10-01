package skillbill.cli.scaffold.wizard

import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliRunInputs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScaffoldPlatformPackWizardPayloadTest {
  @Test
  fun `guided and assisted wizards record an external pack root and registration`() {
    val guided =
      platformPackWizardPayload(
        CliRunState("acme\n\n\n.acme\nexternal\n/tmp/company-packs/acme\nregister\n"),
        inputs(),
        emptyMap(),
      )
    assertEquals("platform-pack", guided["kind"])
    assertEquals("acme", guided["platform"])
    assertEquals("/tmp/company-packs/acme", guided["pack_location_path"])
    assertEquals("register", guided["pack_registration"])
    assertFalse(guided.containsKey("addon_location_path"))

    val assisted =
      assistedPlatformPackWizardPayload(
        CliRunState("go\nexternal\n/tmp/company-packs/go\ncreate\n"),
        inputs(),
        emptyMap(),
      )
    assertEquals("go", assisted["platform"])
    assertEquals("/tmp/company-packs/go", assisted["pack_location_path"])
    assertEquals("create", assisted["pack_registration"])
    assertFalse(assisted.containsKey("addon_location_path"))
  }

  @Test
  fun `blank pack source keeps the in-repo scaffold unregistered`() {
    val payload =
      platformPackWizardPayload(
        CliRunState("acme\n\n\n.acme\n\n"),
        inputs(),
        emptyMap(),
      )
    assertFalse(payload.containsKey("pack_location_path"))
    assertFalse(payload.containsKey("pack_registration"))
  }

  private fun inputs(): CliRunInputs {
    val home = Files.createTempDirectory("skillbill-wizard-home")
    return CliRunInputs(
      databasePath = null,
      environment = emptyMap(),
      userHome = home,
      repositoryRoot = home,
      liveStdout = {},
      liveStderr = {},
    )
  }
}
