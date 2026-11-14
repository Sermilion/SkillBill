package skillbill.infrastructure.launcher.process.cursor

import skillbill.install.model.SupportedAgent

data class AcpProvider(
  val id: String,
  val name: String,
  val acpArguments: List<String>,
  val authMethodId: String?,
  val lacksTeamDashboardMcp: Boolean,
  val exactModels: Boolean = false,
  val loginCommand: String? = null,
  val defaultMode: String? = null,
  val autoApprovePermissions: Boolean = false,
  val launchEnvironment: Map<String, String> = emptyMap(),
  val supportsSteering: Boolean = false,
) {
  val seam: String get() = "${id}_acp"

  fun launchArguments(model: String?): List<String> =
    acpArguments + if (exactModels && model != null) listOf("--model", model) else emptyList()

  companion object {
    private const val OPENCODE_PERMISSION = "OPENCODE_PERMISSION"

    val Cursor =
      AcpProvider(
        SupportedAgent.CURSOR.wireValue,
        "Cursor",
        listOf("--force", "--trust", "--approve-mcps", "--sandbox", "disabled", "acp"),
        CursorAcpKeys.CURSOR_LOGIN,
        lacksTeamDashboardMcp = true,
      )

    val OpenCode =
      AcpProvider(
        SupportedAgent.OPENCODE.wireValue,
        "OpenCode",
        listOf("acp"),
        authMethodId = null,
        lacksTeamDashboardMcp = false,
        loginCommand = "opencode auth login",
        autoApprovePermissions = true,
        launchEnvironment = mapOf(OPENCODE_PERMISSION to """{"*":"allow"}"""),
      )

    val Devin =
      AcpProvider(
        SupportedAgent.DEVIN.wireValue,
        "Devin",
        listOf("acp"),
        authMethodId = null,
        lacksTeamDashboardMcp = false,
        exactModels = true,
        loginCommand = "devin auth login",
        defaultMode = "bypass",
        supportsSteering = true,
      )
  }
}
