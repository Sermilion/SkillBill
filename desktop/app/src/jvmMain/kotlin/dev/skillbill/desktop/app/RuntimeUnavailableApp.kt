package dev.skillbill.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.skillbill.desktop.app.shell.ProblemStatePanel
import dev.skillbill.desktop.app.shell.ShellFrame
import dev.skillbill.desktop.app.shell.ShellProblem
import dev.skillbill.desktop.core.designsystem.icon.skillBillIcon
import dev.skillbill.desktop.core.data.runtime.InstalledRuntimeBuildGate
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.RuntimeAvailability
import java.nio.file.Path

/**
 * The refusal window, run inside the app's class loader before the runtime's jars are on it. Retry reruns the gate in place;
 * once the gate reports the runtime available this returns its jar paths, and null when the user closes the window. It reads
 * no preferences and no OS settings, because their libraries arrive with the runtime.
 */
fun runRuntimeUnavailable(runtimeRoot: String, appRecordPath: String): Array<String>? {
  val gate = InstalledRuntimeBuildGate(Path.of(runtimeRoot), Path.of(appRecordPath))
  var runtimeJars: Array<String>? = null
  application(exitProcessOnExit = false) {
    var installation by remember { mutableStateOf(gate.check()) }
    when (val availability = installation.availability) {
      RuntimeAvailability.Available -> LaunchedEffect(installation) {
        runtimeJars = installation.runtimeJars.map(Path::toString).toTypedArray()
        exitApplication()
      }
      is RuntimeAvailability.Unavailable -> Window(onCloseRequest = ::exitApplication, title = "Skill Bill", icon = skillBillIcon()) {
        SkillBillTheme(reducedMotion = false) {
          ShellFrame(sidebar = null, drawerMode = false, drawerOpen = false, onDismissDrawer = {}) {
            // No sidebar here, so the state sits in the content panel inset 8 dp on every side, as the main window's panel.
            val shape = RoundedCornerShape(SkillBillRadius.lg)
            val colors = SkillBillTheme.colors
            Box(Modifier.fillMaxSize().padding(8.dp).clip(shape).background(colors.bgPanel).border(1.dp, colors.borderSubtle, shape).padding(1.dp)) {
              ProblemStatePanel(ShellProblem.RuntimeUnavailable(availability.problem), onRetry = { installation = gate.check() })
            }
          }
        }
      }
    }
  }
  return runtimeJars
}
