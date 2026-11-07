package skillbill.infrastructure.launcher.process.cursor

import skillbill.install.model.SupportedAgent

data class AcpProvider(
  val id: String,
  val name: String,
  val acpArguments: List<String>,
  val authMethodId: String?,
  val mcpDeclarationRequired: Boolean,
) {
  val probeArguments: List<String> get() = acpArguments + "--help"

  val seam: String get() = "${id}_acp"

  companion object {
    val Cursor =
      AcpProvider(
        SupportedAgent.CURSOR.wireValue,
        "Cursor",
        listOf("acp"),
        CursorAcpKeys.CURSOR_LOGIN,
        mcpDeclarationRequired = true,
      )

    val Junie =
      AcpProvider(
        SupportedAgent.JUNIE.wireValue,
        "Junie",
        listOf("--acp"),
        authMethodId = null,
        mcpDeclarationRequired = false,
      )
  }
}
