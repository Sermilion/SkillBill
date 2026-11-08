package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RepositoryBrowserLauncherTest {
  private val uri = RepositoryWebLinks.url("git@github.com:oila-gmbh/ZeroAccount.git", "feat/push notifications")

  @Test
  fun `Linux without Java browsing opens the encoded branch through xdg-open`() = runTest {
    val diagnostics = RecordingDiagnostics()
    val commands = mutableListOf<List<String>>()
    val browser = RepositoryBrowserLauncher(diagnostics, browse = { false }, launch = {
      commands += it
      0
    }, linux = true)

    assertEquals(OpenOutcome.Opened, browser.open(uri))
    assertEquals(listOf(listOf("xdg-open", "https://github.com/oila-gmbh/ZeroAccount/tree/feat%2Fpush%20notifications")), commands)
    assertEquals(listOf("detail.branch_page.browse_unsupported", "detail.branch_page.xdg_open_fallback"), diagnostics.categories)
  }

  @Test
  fun `failed fallback never reports the branch as opened`() = runTest {
    val failures: List<Pair<suspend (List<String>) -> Int, String>> = listOf(
      Pair({ 3 }, "detail.branch_page.xdg_open_failed"),
      Pair({ -1 }, "detail.branch_page.xdg_open_failed"),
      Pair({ throw IOException("missing xdg-open") }, "detail.branch_page.xdg_open_io"),
      Pair({ throw SecurityException("denied") }, "detail.branch_page.xdg_open_denied"),
    )
    failures.forEach { (launch, category) ->
      val diagnostics = RecordingDiagnostics()
      val browser = RepositoryBrowserLauncher(diagnostics, browse = { false }, launch = launch, linux = true)
      assertEquals(OpenOutcome.NotOpened, browser.open(uri))
      assertEquals(category, diagnostics.categories.last())
    }
  }

  @Test
  fun `Java browse failure still tries Linux handler but cancellation propagates`() = runTest {
    val diagnostics = RecordingDiagnostics()
    val browser = RepositoryBrowserLauncher(diagnostics, browse = { throw IOException("desktop failed") }, launch = { 0 }, linux = true)
    assertEquals(OpenOutcome.Opened, browser.open(uri))
    assertEquals(listOf("detail.branch_page.io", "detail.branch_page.xdg_open_fallback"), diagnostics.categories)

    val cancelled = RepositoryBrowserLauncher(diagnostics, browse = { false }, launch = { throw CancellationException() }, linux = true)
    assertFailsWith<CancellationException> { cancelled.open(uri) }
  }

  @Test
  fun `working Java browsing and unsupported non Linux hosts never launch xdg-open`() = runTest {
    for ((supported, linux, expected) in listOf(
      Triple(true, true, OpenOutcome.Opened),
      Triple(true, false, OpenOutcome.Opened),
      Triple(false, false, OpenOutcome.NotOpened),
    )) {
      val browser = RepositoryBrowserLauncher(RecordingDiagnostics(), browse = { supported }, launch = {
        error("Unexpected fallback on this host")
      }, linux = linux)
      assertEquals(expected, browser.open(uri))
    }
  }
}
