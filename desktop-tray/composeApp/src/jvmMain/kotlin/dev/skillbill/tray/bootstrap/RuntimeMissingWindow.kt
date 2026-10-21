package dev.skillbill.tray.bootstrap

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GraphicsEnvironment
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

internal object RuntimeMissingWindow {
  fun show(problem: BootstrapOutcome.Refused): Boolean {
    if (GraphicsEnvironment.isHeadless()) return false
    var retry = false
    val display = Runnable {
      val dialog = JDialog(null as java.awt.Frame?, "Skill Bill desktop cannot start", true)
      dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
      val content = JPanel(BorderLayout(0, 12))
      content.border = javax.swing.BorderFactory.createEmptyBorder(18, 18, 18, 18)
      content.add(JLabel(reasonText(problem)), BorderLayout.NORTH)
      val details = JTextArea(
        "Installed runtime: ${problem.installedIdentity ?: "not found"}\n" +
          "Desktop build: ${problem.desktopIdentity}\n\n" +
          "Install a matched runtime and desktop pair with:\n${problem.reinstallCommand}",
      ).apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }
      content.add(details, BorderLayout.CENTER)
      val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
      buttons.add(JButton("Retry").apply {
        addActionListener { retry = true; dialog.dispose() }
      })
      buttons.add(JButton("Quit").apply { addActionListener { dialog.dispose() } })
      content.add(buttons, BorderLayout.SOUTH)
      dialog.contentPane = content
      dialog.minimumSize = Dimension(440, 225)
      dialog.pack()
      dialog.setLocationRelativeTo(null)
      dialog.isVisible = true
    }
    if (SwingUtilities.isEventDispatchThread()) display.run() else SwingUtilities.invokeAndWait(display)
    return retry
  }

  private fun reasonText(problem: BootstrapOutcome.Refused): String = when (problem.reason) {
    dev.skillbill.tray.core.domain.model.RuntimeBuildReason.NOT_INSTALLED -> "Skill Bill runtime not installed."
    dev.skillbill.tray.core.domain.model.RuntimeBuildReason.MISSING -> "The installed runtime has no build identity record."
    dev.skillbill.tray.core.domain.model.RuntimeBuildReason.UNREADABLE -> "The runtime or desktop build identity could not be read."
    dev.skillbill.tray.core.domain.model.RuntimeBuildReason.MISMATCH -> "The installed runtime belongs to a different desktop build."
  }
}
