package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatDeliveryRecoveryTest {
  @Test
  fun `shared recovery requires the duplicate warning confirmation and an explicit discard decision`() = runComposeUiTest {
    val delivery = mutableStateOf(ChatDeliveryView("delivery", "original text", listOf(ChatImage(byteArrayOf(1), "image/png")),
      ChatDeliveryStatus.Uncertain, "Acknowledgement missing", "close this chat"))
    val retries = mutableListOf<String>()
    val discards = mutableListOf<String>()
    var keptOpen = false
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatDeliveryRecovery(delivery.value, retries::add, discards::add, { keptOpen = true })
      }
    }
    onNodeWithText("original text").assertExists()
    onNodeWithText("[Image #1]").assertExists()
    onNodeWithText("Keep open").performClick()
    runOnIdle { assertTrue(keptOpen) }
    onNodeWithText("Retry").performClick()
    onNodeWithText(DELIVERY_RETRY_WARNING).assertExists()
    runOnIdle { assertTrue(retries.isEmpty()) }
    onNodeWithText("Cancel retry").performClick()
    runOnIdle { assertTrue(retries.isEmpty()) }
    onNodeWithText("Retry").performClick()
    onNodeWithText("Retry anyway").performClick()
    runOnIdle { assertEquals(listOf("delivery"), retries) }
    onNodeWithText("Discard").performClick()
    runOnIdle { assertTrue(discards.isEmpty()) }
    onNodeWithText("Keep follow-up").performClick()
    runOnIdle { assertTrue(discards.isEmpty()) }
    onNodeWithText("Discard").performClick()
    onNodeWithText("Discard follow-up").performClick()
    runOnIdle { assertEquals(listOf("delivery"), discards) }
  }
}
