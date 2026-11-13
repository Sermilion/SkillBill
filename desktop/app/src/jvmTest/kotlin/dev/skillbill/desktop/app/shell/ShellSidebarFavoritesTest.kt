package dev.skillbill.desktop.app.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.usage.UsageCard
import dev.skillbill.desktop.core.designsystem.usage.UsageUiState
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.settings.FavoriteSpecKey
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import dev.skillbill.desktop.core.ui.shell.ShellPanes
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
            topRoute = WorkRoute(null), sectionSizes = null, favorites = entries, specFavorites = emptyList(), specsOpen = false, terminalOpen = false,
            chatOpen = false, newTaskDraft = false, newTaskOpenChats = 1, contributions = emptyList(), onNavigate = { selected = it },
            onOpenCommandMenu = {}, onToggleSpecs = {}, onToggleTerminal = {}, onToggleChat = {}, onShowShortcuts = {},
            onRemoveFavorite = { removed = it }, onRemoveSpecFavorite = {}, onOpenSpecFavorite = { _, _ -> },
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

  @Test
  fun `resolved spec favorite opens specs instead of detail`() = runComposeUiTest {
    val specKey = FavoriteSpecKey("p1", "SKILL-1")
    val specEntries = listOf(FavoriteSpecSidebarEntry.Resolved(specKey, "Spec title", "SKILL-1"))
    var selected: dev.skillbill.desktop.core.ui.navigation.AppRoute? = null
    var opened: Pair<String, String>? = null
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.width(280.dp).height(420.dp)) {
          ShellSidebar(
            topRoute = WorkRoute(null), sectionSizes = null, favorites = emptyList(), specFavorites = specEntries, specsOpen = false,
            terminalOpen = false, chatOpen = false, newTaskDraft = false, newTaskOpenChats = 1, contributions = emptyList(), onNavigate = { selected = it },
            onOpenCommandMenu = {}, onToggleSpecs = {}, onToggleTerminal = {}, onToggleChat = {}, onShowShortcuts = {},
            onRemoveFavorite = {}, onRemoveSpecFavorite = {}, onOpenSpecFavorite = { projectId, path -> opened = projectId to path },
          )
        }
      }
    }

    onNodeWithContentDescription("Spec title, SKILL-1").performClick()
    runOnIdle {
      assertEquals(null, selected)
      assertEquals("p1" to "SKILL-1", opened)
    }
  }

  @Test
  fun `opening a spec favorite closes the drawer and reveals the selected spec`() = runComposeUiTest {
    val specKey = FavoriteSpecKey("project-1", "SKILL-478")
    val specEntry = FavoriteSpecSidebarEntry.Resolved(specKey, "Drawer regression", "SKILL-478")
    val panes = ShellPanes().apply { drawerOpen = true }
    var opened = false
    var revealed: Pair<String, String>? = null
    var clickedBehindDrawer = false
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.width(480.dp).height(420.dp)) {
          ShellFrame(
            sidebar = {
              ShellSidebar(
                topRoute = WorkRoute(null), sectionSizes = null, favorites = emptyList(), specFavorites = listOf(specEntry), specsOpen = panes.specsOpen,
                terminalOpen = false, chatOpen = false, newTaskDraft = false, newTaskOpenChats = 1, contributions = emptyList(), onNavigate = {},
                onOpenCommandMenu = {}, onToggleSpecs = {}, onToggleTerminal = {}, onToggleChat = {}, onShowShortcuts = {},
                onRemoveFavorite = {}, onRemoveSpecFavorite = {},
                onOpenSpecFavorite = { projectId, path ->
                  openSpecFavorite(panes, { opened = true }, { selectedProject, selectedPath -> revealed = selectedProject to selectedPath }, projectId, path)
                },
              )
            },
            drawerMode = true,
            drawerOpen = panes.drawerOpen,
            onDismissDrawer = { panes.drawerOpen = false },
          ) {
            Box(Modifier.fillMaxSize()) {
              Box(
                Modifier.align(Alignment.BottomEnd).size(80.dp).clickable { clickedBehindDrawer = true }.testTag("specs-pane-action"),
              )
              BasicText("Plans pane")
            }
          }
        }
      }
    }

    onNodeWithContentDescription("Drawer regression, SKILL-478").performClick()
    onNodeWithContentDescription("Drawer regression, SKILL-478").assertDoesNotExist()
    onNodeWithText("Plans pane").assertIsDisplayed()
    onNodeWithTag("specs-pane-action").performClick()
    runOnIdle {
      assertEquals(true, opened)
      assertEquals("project-1" to "SKILL-478", revealed)
      assertEquals(true, panes.specsOpen)
      assertEquals(false, panes.drawerOpen)
      assertEquals(true, clickedBehindDrawer)
    }
  }

  @Test
  fun `new task shows the open chat count instead of the draft dot when more than one chat is open`() = runComposeUiTest {
    var openChats by mutableIntStateOf(2)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.width(280.dp).height(420.dp)) {
          ShellSidebar(
            topRoute = WorkRoute(null), sectionSizes = null, favorites = emptyList(), specFavorites = emptyList(), specsOpen = false,
            terminalOpen = false, chatOpen = false, newTaskDraft = true, newTaskOpenChats = openChats, contributions = emptyList(), onNavigate = {},
            onOpenCommandMenu = {}, onToggleSpecs = {}, onToggleTerminal = {}, onToggleChat = {}, onShowShortcuts = {},
            onRemoveFavorite = {}, onRemoveSpecFavorite = {}, onOpenSpecFavorite = { _, _ -> },
          )
        }
      }
    }

    onNodeWithText("2").assertExists()
    onNodeWithText("1").assertDoesNotExist()

    openChats = 1
    onNodeWithText("2").assertDoesNotExist()
    onNodeWithText("1").assertExists()
  }

  @Test
  fun `usage stays in the fixed footer while board navigation scrolls`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.width(280.dp).height(420.dp)) {
          ShellSidebar(
            topRoute = WorkRoute(null), sectionSizes = null, favorites = emptyList(), specFavorites = emptyList(), specsOpen = false,
            terminalOpen = false, chatOpen = false, newTaskDraft = false, newTaskOpenChats = 1, contributions = emptyList(),
            usage = {
              UsageCard(
                UsageUiState(provider = "Cursor", agentId = "cursor", message = "No usage data"),
                onOpen = {},
              )
            }, onNavigate = {}, onOpenCommandMenu = {}, onToggleSpecs = {}, onToggleTerminal = {},
            onToggleChat = {}, onShowShortcuts = {}, onRemoveFavorite = {}, onRemoveSpecFavorite = {}, onOpenSpecFavorite = { _, _ -> },
          )
        }
      }
    }

    onNodeWithText("Board").performScrollTo()
    onNodeWithText("Board").assertIsDisplayed()
    onNodeWithText("Cursor").assertIsDisplayed()
    onNodeWithText("Settings").assertIsDisplayed()
  }
}
