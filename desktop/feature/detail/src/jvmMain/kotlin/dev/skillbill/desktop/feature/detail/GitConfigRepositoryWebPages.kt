package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the origin remote from the checkout's git config instead of running `git`, which the app may not launch, and opens the
 * branch page in the default browser.
 */
// shortcut: ignores `url.<base>.insteadOf` rewrites that `git remote get-url` applies; read them if users report wrong hosts.
@Inject
@ContributesBinding(AppScope::class)
class GitConfigRepositoryWebPages(private val diagnostics: Diagnostics) : RepositoryWebPages {
  override suspend fun openBranch(repositoryRoot: String, branch: String): OpenOutcome = withContext(Dispatchers.IO) {
    try {
      val remote = originRemote(Path.of(repositoryRoot)) ?: return@withContext notOpened("detail.branch_page.no_origin")
      if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        return@withContext notOpened("detail.branch_page.browse_unsupported")
      }
      Desktop.getDesktop().browse(RepositoryWebLinks.url(remote, branch))
      OpenOutcome.Opened
    } catch (_: IOException) {
      notOpened("detail.branch_page.io")
    } catch (_: IllegalArgumentException) {
      notOpened("detail.branch_page.no_web_address")
    } catch (_: IllegalStateException) {
      notOpened("detail.branch_page.no_web_address")
    } catch (_: URISyntaxException) {
      notOpened("detail.branch_page.no_web_address")
    } catch (_: UnsupportedOperationException) {
      notOpened("detail.branch_page.browse_unsupported")
    } catch (_: SecurityException) {
      notOpened("detail.branch_page.denied")
    }
  }

  private fun notOpened(category: String): OpenOutcome {
    diagnostics.record(category)
    return OpenOutcome.NotOpened
  }
}

/** The first `url` under `[remote "origin"]` in the checkout's shared git config. */
internal fun originRemote(root: Path): String? {
  val config = gitCommonDir(root)?.resolve("config")?.takeIf(Files::isRegularFile) ?: return null
  var inOrigin = false
  Files.readAllLines(config).forEach { raw ->
    val line = raw.trim()
    when {
      line.startsWith("[") -> inOrigin = line.replace(" ", "") == "[remote\"origin\"]"
      inOrigin && line.substringBefore('=').trim() == "url" -> return line.substringAfter('=').trim().takeIf(String::isNotEmpty)
    }
  }
  return null
}

/** `<root>/.git`, or for a linked worktree the `gitdir:` target's `commondir`, as `RepositoryNames.kt` resolves it. */
private fun gitCommonDir(root: Path): Path? {
  val marker = root.resolve(".git")
  if (Files.isDirectory(marker)) return marker
  if (!Files.isRegularFile(marker)) return null
  val gitDir = Files.readAllLines(marker).firstOrNull { it.startsWith("gitdir:") }
    ?.removePrefix("gitdir:")?.trim()?.takeIf(String::isNotEmpty)
    ?.let { root.resolve(it).normalize() } ?: return null
  val commonDir = gitDir.resolve("commondir").takeIf(Files::isRegularFile) ?: return gitDir
  return gitDir.resolve(Files.readString(commonDir).trim()).normalize()
}

/** `desktop-tray`'s `RepositoryWebLinks`, kept to the branch page this module opens. */
internal object RepositoryWebLinks {
  fun url(remote: String, branch: String): URI {
    val normalized = remote.trim().let { value ->
      if ("://" in value) value
      else Regex("^(?:[^/@:]+@)?([^/:]+):(.+)$").matchEntire(value)?.let {
        "ssh://${it.groupValues[1]}/${it.groupValues[2]}"
      } ?: error("The origin remote does not have a web address.")
    }
    val uri = URI(normalized)
    require(uri.scheme in setOf("http", "https", "ssh", "git") && !uri.host.isNullOrBlank()) {
      "The origin remote does not have a web address."
    }
    val scheme = if (uri.scheme == "http") "http" else "https"
    val port = if (uri.scheme in setOf("http", "https")) uri.port else -1
    val path = uri.path.orEmpty().trimEnd('/').removeSuffix(".git")
    require(path.isNotEmpty()) { "The origin remote does not identify a repository." }
    val repository = URI(scheme, null, uri.host, port, path, null, null).toASCIIString()
    require(branch.isNotBlank()) { "This item does not have a branch." }
    val route = when {
      uri.host.equals("bitbucket.org", true) -> "src"
      uri.host.equals("gitlab.com", true) || uri.host.startsWith("gitlab.", true) -> "-/tree"
      else -> "tree"
    }
    val ref = URLEncoder.encode(branch, StandardCharsets.UTF_8).replace("+", "%20")
    return URI("$repository/$route/$ref")
  }
}
