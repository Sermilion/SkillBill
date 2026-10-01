package skillbill.cli.scaffold.wizard

internal fun assistedPlatformProfile(input: String): AssistedPlatformProfile {
  val key = languageLookupKey(input)
  return assistedPlatformProfiles()[key] ?: fallbackAssistedPlatformProfile(input)
}

internal fun assistedPlatformProfiles(): Map<String, AssistedPlatformProfile> =
  buildMap {
    putProfile("go", "Go", listOf(".go", "go.mod", "go.sum"), "go", "golang")
    putProfile(
      "python",
      "Python",
      listOf("pyproject.toml", "requirements.txt", "setup.py", "poetry.lock", ".py"),
      "py",
      "python",
    )
    putProfile(
      "javascript",
      "JavaScript",
      listOf("package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml", ".js", ".jsx"),
      "js",
      "javascript",
      "node",
      "nodejs",
    )
    putProfile("typescript", "TypeScript", listOf("tsconfig.json", "package.json", ".ts", ".tsx"), "ts", "typescript")
    putProfile("rust", "Rust", listOf("Cargo.toml", "Cargo.lock", ".rs"), "rs", "rust")
    putProfile("ruby", "Ruby", listOf("Gemfile", "Gemfile.lock", ".rb"), "rb", "ruby")
    putProfile("csharp", "C#", listOf(".csproj", ".sln", ".cs"), "csharp", "c#", "dotnet")
    putProfile("cpp", "C++", listOf("CMakeLists.txt", ".cpp", ".hpp", ".cc", ".h"), "cpp", "c++")
    putProfile("c", "C", listOf("Makefile", ".c", ".h"), "c")
    putProfile("swift", "Swift", listOf("Package.swift", ".swift"), "swift")
    putProfile("scala", "Scala", listOf("build.sbt", ".scala"), "scala")
    putProfile("clojure", "Clojure", listOf("deps.edn", "project.clj", ".clj"), "clojure")
    putProfile("elixir", "Elixir", listOf("mix.exs", "mix.lock", ".ex", ".exs"), "elixir")
    putProfile("erlang", "Erlang", listOf("rebar.config", ".erl", ".hrl"), "erlang")
    putProfile("dart", "Dart", listOf("pubspec.yaml", ".dart"), "dart")
    putProfile("lua", "Lua", listOf(".lua"), "lua")
    putProfile("haskell", "Haskell", listOf("stack.yaml", "cabal.project", ".hs"), "haskell")
  }

internal fun MutableMap<String, AssistedPlatformProfile>.putProfile(
  slug: String,
  displayName: String,
  strongSignals: List<String>,
  vararg aliases: String,
) {
  val profile = AssistedPlatformProfile(slug = slug, displayName = displayName, strongSignals = strongSignals)
  aliases.forEach { alias -> put(languageLookupKey(alias), profile) }
}

internal fun fallbackAssistedPlatformProfile(input: String): AssistedPlatformProfile {
  val slug = platformSlugFromInput(input)
  val displayName = displayNameFromInput(input, slug)
  return AssistedPlatformProfile(
    slug = slug,
    displayName = displayName,
    strongSignals = listOf(".$slug", "$slug/"),
  )
}

internal fun languageLookupKey(value: String): String =
  value.trim().lowercase().filter { character -> character.isLetterOrDigit() || character == '#' || character == '+' }

internal fun platformSlugFromInput(value: String): String =
  value.trim()
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
    .ifBlank { "platform" }

internal fun displayNameFromInput(
  input: String,
  slug: String,
): String =
  input.trim().takeIf { it.isNotBlank() } ?: slug.split("-").joinToString(" ") { part ->
    part.replaceFirstChar { character -> character.uppercase() }
  }
