package dev.skillbill.desktop.app

import dev.skillbill.desktop.app.motion.OsReducedMotionReader
import dev.skillbill.desktop.app.motion.ReducedMotionRead
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
  val reducedMotion = when (val read = OsReducedMotionReader().read()) {
    is ReducedMotionRead.Known -> read.reduced
    is ReducedMotionRead.Unavailable -> {
      val source = read.source.replace(Regex("\\s+"), " ").take(120)
      val reason = read.reason.replace(Regex("\\s+"), " ").take(120)
      System.getLogger("dev.skillbill.desktop").log(System.Logger.Level.WARNING, "desktop:reduced-motion-unavailable source=$source reason=$reason")
      false
    }
  }
  showSkillBillWindow(
    component.workBoardRepository,
    component.workDetailRepository,
    component.workControlRepository,
    component.specRepository,
    reducedMotion,
  )
}
