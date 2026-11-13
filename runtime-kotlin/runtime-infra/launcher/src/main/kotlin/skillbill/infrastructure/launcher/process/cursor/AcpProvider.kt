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
) {
  val seam: String get() = "${id}_acp"

  /** ACP argv; exact-model providers also carry the selected model UID as `--model`. */
  fun launchArguments(model: String?): List<String> =
    acpArguments + if (exactModels && model != null) listOf("--model", model) else emptyList()

  companion object {
    val Cursor =
      AcpProvider(
        SupportedAgent.CURSOR.wireValue,
        "Cursor",
        listOf("--force", "--trust", "--approve-mcps", "--sandbox", "disabled", "acp"),
        CursorAcpKeys.CURSOR_LOGIN,
        lacksTeamDashboardMcp = true,
      )

    val Junie =
      AcpProvider(
        SupportedAgent.JUNIE.wireValue,
        "Junie",
        listOf("--acp"),
        authMethodId = null,
        lacksTeamDashboardMcp = false,
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
      )
  }
}
