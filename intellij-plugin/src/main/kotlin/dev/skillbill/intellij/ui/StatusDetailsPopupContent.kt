package dev.skillbill.intellij.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.skillbill.intellij.presentation.GoalControlDescriptor
import dev.skillbill.intellij.presentation.GoalControlKind
import dev.skillbill.intellij.presentation.SkillBillStatusBarPresentation
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants
import java.awt.Font.BOLD


object StatusDetailsPopupContent {
    
    fun statusLines(presentation: SkillBillStatusBarPresentation.MappedPresentation): List<Pair<String, String>> {
        val details = presentation.details
        return buildList {
            add("State" to details.lifecycleState)
            details.issueKey?.let { add("Issue" to it) }
            details.workflowId?.let { add("Workflow" to it) }
            details.scopeText?.let { add("Scope" to it) }
            details.phaseId?.let { add("Phase" to it) }
            details.stepLabel?.let { add("Step" to it) }
            details.modelText?.let { add("Model" to it) }
            val slotLabel = details.selectedSlotLabel
            val slotText = details.selectedSlotText
            if (slotLabel != null && slotText != null) {
                add(slotLabel to slotText)
            }
            details.progressText?.let { add("Progress" to it) }
            add("Goal ${details.elapsedNoun}" to details.goalElapsedText)
            add("Subtask ${details.elapsedNoun}" to details.subtaskElapsedText)
            details.agentActivityText?.let { add("Agent activity" to it) }
            details.currentActivity?.let { add("Current activity" to it) }
            details.lastUpdateText?.let { add("Last update" to it) }
            details.pauseReasonText?.let { reason ->
                val label = if (details.lifecycleState == "blocked") "Blocked reason" else "Pause reason"
                add(label to reason)
            }
            details.pauseActionText?.let { add("" to it) }
            details.problemSummary?.let { add("" to it) }
            details.staleNote?.let { add("" to it) }
        }
    }

    fun build(
        presentation: SkillBillStatusBarPresentation.MappedPresentation,
        onActivate: (GoalControlDescriptor) -> Unit,
    ): Built {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12)
            isOpaque = false
        }
        panel.add(statusBlock(presentation), BorderLayout.CENTER)

        if (presentation.controls.isEmpty()) {
            return Built(panel = panel, buttons = emptyMap(), separator = null, actionRow = null, messageLabel = null)
        }

        val separator = JSeparator(SwingConstants.HORIZONTAL).apply {
            border = JBUI.Borders.empty(8, 0, 4, 0)
            foreground = JBColor.border()
        }

        val messageLabel = plainLabel("").apply {
            isVisible = false
            foreground = UIUtil.getErrorForeground()
            border = JBUI.Borders.emptyTop(4)
        }
        val buttons = presentation.controls.associate { descriptor ->
            descriptor.kind to JButton(descriptor.text).apply {
                isEnabled = descriptor.enabled


                isFocusable = true
                accessibleContext.accessibleName = descriptor.accessibleName
                addActionListener { onActivate(descriptor) }
            }
        }
        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false

            presentation.controls.forEach { descriptor -> buttons[descriptor.kind]?.let { add(it) } }
        }

        val footer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(separator)
            add(actionRow)
            add(messageLabel)
        }
        panel.add(footer, BorderLayout.SOUTH)
        return Built(
            panel = panel,
            buttons = buttons,
            separator = separator,
            actionRow = actionRow,
            messageLabel = messageLabel,
        )
    }

    private fun statusBlock(presentation: SkillBillStatusBarPresentation.MappedPresentation): JPanel {
        val block = JPanel(GridBagLayout()).apply { isOpaque = false }
        val title = plainLabel("Skill Bill details").apply {
            font = font.deriveFont(BOLD)
            border = JBUI.Borders.emptyBottom(4)
        }
        block.add(
            title,
            GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                gridwidth = 2
                anchor = GridBagConstraints.LINE_START
            },
        )
        statusLines(presentation).forEachIndexed { index, (label, value) ->
            val row = index + 1
            if (label.isEmpty()) {
                block.add(
                    plainLabel(value).apply { foreground = UIUtil.getContextHelpForeground() },
                    GridBagConstraints().apply {
                        gridx = 0
                        gridy = row
                        gridwidth = 2
                        anchor = GridBagConstraints.LINE_START
                        insets = Insets(1, 0, 1, 0)
                    },
                )
                return@forEachIndexed
            }
            block.add(
                plainLabel("$label:").apply { foreground = UIUtil.getContextHelpForeground() },
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    anchor = GridBagConstraints.LINE_START
                    insets = Insets(1, 0, 1, JBUI.scale(8))
                },
            )
            block.add(
                plainLabel(value),
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = row
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.LINE_START
                    insets = Insets(1, 0, 1, 0)
                },
            )
        }
        return block
    }

    
    private fun plainLabel(text: String): JLabel = JLabel(text).apply {


        putClientProperty("html.disable", true)
    }

    
    class Built(
        val panel: JPanel,
        val buttons: Map<GoalControlKind, JButton>,
        val separator: Component?,
        val actionRow: Component?,
        private val messageLabel: JLabel?,
    ) {
        
        fun showMessage(summary: String) {
            messageLabel?.apply {
                text = summary
                isVisible = true
                revalidate()
                repaint()
            }
        }

        fun messageText(): String? = messageLabel?.takeIf { it.isVisible }?.text

        fun buttonFor(kind: GoalControlKind): JButton? = buttons[kind]
    }
}
