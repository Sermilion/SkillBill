package dev.skillbill.desktop.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.dataColor
import dev.skillbill.desktop.core.designsystem.icon.BrandMark
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.designsystem.navigation.SidebarItem
import dev.skillbill.desktop.core.designsystem.usage.UsageCard

internal val LocalTourTargets = staticCompositionLocalOf<TourTargetRects?> { null }

/**
 * Where each tagged element sits inside the staged frame. Positions resolve through the frame's own
 * [LayoutCoordinates], so rects stay in unscaled frame space no matter when a child's position publishes.
 */
@Stable
internal class TourTargetRects {
  private val targetCoords = mutableMapOf<TourTarget, LayoutCoordinates>()
  private var frameCoords: LayoutCoordinates? = null
  val rects: SnapshotStateMap<TourTarget, Rect> = mutableStateMapOf()

  internal fun onFramePositioned(coords: LayoutCoordinates) {
    frameCoords = coords
    targetCoords.keys.toList().forEach(::resolve)
  }

  internal fun onTargetPositioned(target: TourTarget, coords: LayoutCoordinates) {
    targetCoords[target] = coords
    resolve(target)
  }

  internal fun onTargetDisposed(target: TourTarget) {
    targetCoords.remove(target)
    rects.remove(target)
  }

  private fun resolve(target: TourTarget) {
    val frame = frameCoords ?: return
    val coords = targetCoords[target] ?: return
    if (frame.isAttached && coords.isAttached) {
      rects[target] = frame.localBoundingBoxOf(coords, clipBounds = false)
    }
  }
}

/** Tags a composable so a tour primitive can find it; without a frame it is a no-op. */
internal fun Modifier.tourTarget(target: TourTarget): Modifier = composed {
  val registry = LocalTourTargets.current ?: return@composed this
  DisposableEffect(registry, target) { onDispose { registry.onTargetDisposed(target) } }
  onGloballyPositioned { registry.onTargetPositioned(target, it) }
}

/** The staged 1120x680 app window: sidebar built from real [SidebarItem]s, a panel slot, then the overlay layers. */
@Composable
internal fun TourStageFrame(
  chrome: StageChrome,
  overlay: StageOverlay,
  modifier: Modifier = Modifier,
  flightContent: @Composable () -> Unit = {},
  panel: @Composable BoxScope.() -> Unit,
) {
  val colors = SkillBillTheme.colors
  val targets = remember { TourTargetRects() }
  val shape = RoundedCornerShape(14.dp)
  val panelShape = RoundedCornerShape(12.dp)
  Box(
    modifier.fillMaxSize()
      .onGloballyPositioned { targets.onFramePositioned(it) }
      .layeredShadow(SkillBillTheme.shadows.popover, shape)
      .clip(shape)
      .background(colors.bgFrame)
      .border(1.dp, colors.border, shape),
  ) {
    CompositionLocalProvider(LocalTourTargets provides targets) {
      Row(Modifier.fillMaxSize()) {
        TourSidebar(chrome)
        Box(Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, end = 8.dp, bottom = 8.dp)) {
          Box(
            Modifier.fillMaxSize().clip(panelShape).background(colors.bgPanel).border(1.dp, colors.borderSubtle, panelShape),
            content = panel,
          )
        }
      }
      SpotlightLayer(overlay.spotlight)
      TipBubble(overlay.spotlight)
      CardFlightLayer(overlay.flight, content = flightContent)
      KeycapLayer(overlay.keycap)
      DemoCursorLayer(overlay.cursor)
    }
  }
}

@Composable
private fun TourSidebar(chrome: StageChrome) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Column(
    Modifier.width(208.dp).fillMaxHeight().padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 10.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BrandMark(18.dp)
      Spacer(Modifier.width(8.dp))
      BasicText("Sona", style = type.tSmMedium.copy(color = colors.fg))
      Spacer(Modifier.weight(1f))
      BasicText(TourFixture.workspace, Modifier.padding(end = 8.dp), style = type.tXs.copy(color = colors.fgFaint))
    }
    Column(Modifier.tourTarget(TourTarget.NavGroup)) {
      TourFixture.navItems.forEach { entry ->
        SidebarItem(
          entry.label,
          {},
          modifier = Modifier.tourTarget(entry.target),
          icon = entry.icon,
          on = chrome.activeNav == entry.nav,
          attention = if (entry.nav == TourNav.Work) chrome.workAttention else null,
          tooltip = null,
        )
      }
    }
    Box(Modifier.padding(horizontal = 6.dp, vertical = 7.dp).fillMaxWidth().height(1.dp).background(colors.borderSubtle))
    BasicText(
      "Repositories",
      Modifier.padding(horizontal = 8.dp).padding(bottom = 2.dp),
      style = type.tXs.copy(color = colors.fgFaint),
    )
    TourFixture.repositories.forEach { repo ->
      SidebarItem(repo, {}, icon = LucideIcons.folder)
    }
    Spacer(Modifier.weight(1f))
    UsageCard(TourFixture.usage, onOpen = {}, modifier = Modifier.tourTarget(TourTarget.Usage))
  }
}

/** The small bordered chip the mock uses in panel footers and meta rows. */
@Composable
internal fun TourChip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
  Row(
    modifier.height(22.dp).border(1.dp, SkillBillTheme.colors.border, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(5.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}

@Composable
internal fun TourAgentDot(agent: TourAgent, size: Dp = 7.dp, modifier: Modifier = Modifier) {
  Box(modifier.size(size).background(SkillBillTheme.colors.dataColor(agent.dataSlot), CircleShape))
}
