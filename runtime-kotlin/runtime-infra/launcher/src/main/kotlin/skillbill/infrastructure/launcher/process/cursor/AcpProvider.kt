package skillbill.infrastructure.launcher.process.cursor

import skillbill.install.model.SupportedAgent

data class AcpProvider(
  val id: String,
  val name: String,
  val acpArguments: List<String>,
  val authMethodId: String?,
  val lacksTeamDashboardMcp: Boolean,
) {
  val seam: String get() = "${id}_acp"

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

    val OpenCode =
      AcpProvider(
        SupportedAgent.OPENCODE.wireValue,
        "OpenCode",
        listOf("acp"),
        authMethodId = null,
        lacksTeamDashboardMcp = false,
      )
  }
}
