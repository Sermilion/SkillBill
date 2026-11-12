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
  fun `steering stays compact and recovery remains available when a chat action is blocked`() = runComposeUiTest {
    val delivery = mutableStateOf(ChatDeliveryView("delivery", "original text", listOf(ChatImage(byteArrayOf(1), "image/png")),
      ChatDeliveryStatus.Pending, "Acknowledgement missing"))
    val retries = mutableListOf<String>()
    val discards = mutableListOf<String>()
    var keptOpen = false
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatDeliveryRecovery(delivery.value, retries::add, discards::add, {
          keptOpen = true
          delivery.value = delivery.value.copy(blockedAction = null)
        })
      }
    }
    onNodeWithText("Sending steering instructions...").assertExists()
    runOnIdle { delivery.value = delivery.value.copy(status = ChatDeliveryStatus.Uncertain) }
    onNodeWithText("Steering instructions have been sent").assertExists()
    onNodeWithText("Sending steering instructions...").assertDoesNotExist()
    onNodeWithText("Follow-up delivery uncertain").assertDoesNotExist()
    onNodeWithText("Acknowledgement missing").assertDoesNotExist()
    onNodeWithText("original text").assertDoesNotExist()
    onNodeWithText("[Image #1]").assertDoesNotExist()
    onNodeWithText("Retry").assertDoesNotExist()
    onNodeWithText("Discard").assertDoesNotExist()
    runOnIdle { delivery.value = delivery.value.copy(blockedAction = "close this chat") }
    onNodeWithText("original text").assertExists()
    onNodeWithText("[Image #1]").assertExists()
    onNodeWithText("Keep open").performClick()
    runOnIdle { assertTrue(keptOpen) }
    onNodeWithText("Retry").assertDoesNotExist()
    onNodeWithText("Steering instructions have been sent").assertExists()
    runOnIdle { delivery.value = delivery.value.copy(blockedAction = "close this chat") }
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
