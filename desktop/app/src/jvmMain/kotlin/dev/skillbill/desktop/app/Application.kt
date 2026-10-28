package dev.skillbill.desktop.app

import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment

/** The app entry the launcher starts inside the class loader that holds the installed runtime's jars. */
fun main() {
  val diagnostics = DiagnosticsSwitch(
    System.getenv("SKILL_BILL_DESKTOP_DIAGNOSTICS") == "1" || System.getProperty("skillbill.desktop.diagnostics") == "true",
  )
  val host = HostEnvironment(System.getenv(), System.getProperty("user.home"), System.getProperty("user.dir"))
  val component = AppComponent::class.create(host, diagnostics)
  component.databaseSessionManager.start()
  showSkillBillWindow(
    component.workBoardRepository,
    component.workDetailRepository,
    component.workControlRepository,
    component.specRepository,
  )
}
