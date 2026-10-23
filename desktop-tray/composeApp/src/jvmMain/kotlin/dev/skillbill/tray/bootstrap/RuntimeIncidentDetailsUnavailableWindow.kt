package dev.skillbill.tray.bootstrap

import dev.skillbill.tray.core.domain.model.IncidentDetailUnavailable
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

internal object RuntimeIncidentDetailsUnavailableWindow {
  fun show(problem: BootstrapOutcome.Refused): Boolean {
    if (GraphicsEnvironment.isHeadless()) return false
    var retry = false
    val display = Runnable {
      val dialog = JDialog(null as java.awt.Frame?, "Incident details unavailable", true)
      dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
      val content = JPanel(BorderLayout(0, 12))
      content.border = javax.swing.BorderFactory.createEmptyBorder(18, 18, 18, 18)
      content.add(JLabel(IncidentDetailUnavailable.MESSAGE), BorderLayout.NORTH)
      val details = JTextArea(
        "Installed runtime: ${problem.installedIdentity ?: "not found"}\n" +
          "Desktop build: ${problem.desktopIdentity}\n\n" +
          "Install a matched runtime and desktop pair with:\n${problem.reinstallCommand}",
      ).apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }
      content.add(details, BorderLayout.CENTER)
      val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
      buttons.add(JButton("Retry").apply { addActionListener { retry = true; dialog.dispose() } })
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
}
