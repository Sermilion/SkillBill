package dev.skillbill.tray.infrastructure

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object RepositoryWebLinks {
  fun url(remote: String, branch: String? = null): URI {
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
    if (branch == null) return URI(repository)
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
