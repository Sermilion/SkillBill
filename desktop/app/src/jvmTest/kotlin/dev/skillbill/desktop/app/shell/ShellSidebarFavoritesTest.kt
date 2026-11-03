package dev.skillbill.desktop.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ShellSidebarFavoritesTest {
  @Test
  fun `a long favorites list scrolls within its sidebar section and opens the resolved item`() = runComposeUiTest {
    val entries = (0 until 12).map { index ->
      val key = FavoriteWorkKey(DatabaseFileIdentity("/tmp/favorites.db", "db-key"), "favorite-$index")
      if (index == 4) FavoriteSidebarEntry.Missing(key)
      else FavoriteSidebarEntry.Resolved(key, workItem(key.workItemId).copy(title = "Favorite $index"))
    }
    var selected: dev.skillbill.desktop.core.ui.navigation.AppRoute? = null
    var removed: FavoriteWorkKey? = null
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.width(280.dp).height(420.dp)) {
          ShellSidebar(
            topRoute = WorkRoute(null), sectionSizes = null, favorites = entries, specsOpen = false, terminalOpen = false,
            chatOpen = false, newTaskDraft = false, contributions = emptyList(), onNavigate = { selected = it },
            onOpenCommandMenu = {}, onToggleSpecs = {}, onToggleTerminal = {}, onToggleChat = {}, onShowShortcuts = {},
            onRemoveFavorite = { removed = it },
          )
        }
      }
    }

    onNodeWithText("Board").performScrollTo()
    val favorite = onNodeWithContentDescription("Favorite 11, Active").performScrollTo().assertIsDisplayed()
    favorite.performClick()
    favorite.performKeyInput { pressKey(Key.Enter) }
    onNodeWithContentDescription("Remove Missing favorite /tmp/favorites.db · favorite-4").performScrollTo().performClick()

    runOnIdle {
      assertEquals("favorite-11", (selected as dev.skillbill.desktop.core.ui.navigation.DetailRoute).item.id)
      assertEquals("favorite-4", removed?.workItemId)
    }
  }
}
