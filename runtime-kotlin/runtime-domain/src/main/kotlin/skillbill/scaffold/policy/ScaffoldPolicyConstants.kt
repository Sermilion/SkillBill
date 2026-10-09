package skillbill.scaffold.policy

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.retiredScaffoldKindError
import skillbill.scaffold.model.SkillKind
import skillbill.scaffold.policy.model.PlatformPackPreset

const val SCAFFOLD_PAYLOAD_VERSION: String = "1.0"

val SKILL_KIND_HORIZONTAL: String get() = SkillKind.HORIZONTAL.wireValue
val SKILL_KIND_PLATFORM_OVERRIDE_PILOTED: String get() = SkillKind.PLATFORM_OVERRIDE_PILOTED.wireValue
val SKILL_KIND_PLATFORM_PACK: String get() = SkillKind.PLATFORM_PACK.wireValue
val SKILL_KIND_CODE_REVIEW_AREA: String get() = SkillKind.CODE_REVIEW_AREA.wireValue
val SKILL_KIND_ADD_ON: String get() = SkillKind.ADD_ON.wireValue
val SKILL_KIND_AGENT_ADDON: String get() = SkillKind.AGENT_ADDON.wireValue

internal val SUPPORTED_SKILL_KINDS: Set<String> =
  SkillKind.entries.mapTo(linkedSetOf(), SkillKind::wireValue)

val ACTIVE_CREATION_SKILL_KINDS: Set<String> =
  setOf(SKILL_KIND_HORIZONTAL, SKILL_KIND_PLATFORM_PACK, SKILL_KIND_ADD_ON, SKILL_KIND_AGENT_ADDON)

val RETIRED_PLATFORM_OVERRIDE_KIND_ALIASES: Set<String> =
  setOf("platform-override", SKILL_KIND_PLATFORM_OVERRIDE_PILOTED, "override")

val RETIRED_CODE_REVIEW_AREA_KIND_ALIASES: Set<String> =
  setOf(SKILL_KIND_CODE_REVIEW_AREA, "area", "specialist")

internal val RETIRED_PARTIAL_SCAFFOLD_KIND_ALIASES: Set<String> =
  RETIRED_PLATFORM_OVERRIDE_KIND_ALIASES + RETIRED_CODE_REVIEW_AREA_KIND_ALIASES

val ORCHESTRATOR_KINDS_FOR_SUBAGENTS: Set<String> =
  setOf(SKILL_KIND_HORIZONTAL, SKILL_KIND_PLATFORM_OVERRIDE_PILOTED, SKILL_KIND_PLATFORM_PACK)

val SUBAGENT_NAME_PATTERN: Regex = Regex("^[a-z][a-z0-9-]*$")

val UNIVERSAL_CODE_REVIEW_AREAS: Set<String> = setOf("code-quality")

val APPROVED_CODE_REVIEW_AREAS: Set<String> =
  setOf(
    "architecture",
    "performance",
    "platform-correctness",
    "security",
    "testing",
    "api-contracts",
    "persistence",
    "reliability",
    "ui",
    "ux-accessibility",
  )

val PLATFORM_PACK_PRESET_DESCRIPTORS: Map<String, PlatformPackPreset> =
  mapOf(
    "java" to
      PlatformPackPreset(
        displayName = "Java",
        strongSignals = listOf("pom.xml", "build.gradle", "src/main/java"),
        tieBreakers =
          listOf(
            "Prefer Java when Maven metadata or first-party Java source markers dominate generic JVM signals.",
            "Do not prefer Java when it appears only as generated bindings, build tooling, or an adjacent JVM layer.",
            "Exclude generated sources, build outputs, and vendored dependencies from dominance scoring.",
          ),
      ),
    "python" to
      PlatformPackPreset(
        displayName = "Python",
        strongSignals =
          listOf(
            "pyproject.toml",
            "requirements.txt",
            "setup.py",
            "setup.cfg",
            "Pipfile",
            "poetry.lock",
            "uv.lock",
            "tox.ini",
            "pytest.ini",
            ".py",
            "*.py",
          ),
        tieBreakers =
          listOf(
            "Prefer Python when pyproject metadata, dependency lockfiles, or first-party .py source files " +
              "dominate the changed product surface.",
            "Do not prefer Python for mixed repositories where Python appears only as tooling, one-off " +
              "scripts, generated clients, or CI glue around another dominant stack.",
            "Exclude virtual environments, site-packages, build and dist outputs, generated protobuf or " +
              "OpenAPI clients, and vendored dependency trees from dominance scoring.",
          ),
      ),
    "php" to
      PlatformPackPreset(
        displayName = "PHP",
        strongSignals =
          listOf(
            "composer.json",
            "composer.lock",
            ".php",
            "*.php",
            "phpunit.xml",
            "phpunit.xml.dist",
            "pest.php",
            "phpstan.neon",
            "phpstan.neon.dist",
            "psalm.xml",
            "psalm.xml.dist",
          ),
        tieBreakers =
          listOf(
            "Prefer PHP when Composer metadata or first-party .php source files dominate mixed backend signals.",
            "Do not prefer PHP for mixed repositories where PHP appears only as generated code, vendor code, " +
              "deployment scripts, or tooling around another dominant stack.",
            "Exclude vendor directories, generated clients, cache/build outputs, and compiled artifacts from " +
              "dominance scoring.",
          ),
      ),
    "go" to
      PlatformPackPreset(
        displayName = "Go",
        strongSignals =
          listOf(
            "go.mod",
            "go.sum",
            "go.work",
            "go.work.sum",
            ".go",
            "*.go",
            "Gopkg.toml",
            "Gopkg.lock",
            "golangci.yml",
            ".golangci.yml",
            "staticcheck.conf",
          ),
        tieBreakers =
          listOf(
            "Prefer Go when module/workspace metadata, Go source files, or Go test files dominate the " +
              "changed product surface.",
            "Do not prefer Go for mixed repositories where Go appears only as generated clients, tooling, " +
              "one-off scripts, or vendored dependencies around another dominant stack.",
            "Exclude vendor directories, generated protobuf/OpenAPI clients, build outputs, and generated " +
              "mocks from dominance scoring unless the generator or generated contract is intentionally changed.",
          ),
      ),
    "rust" to
      PlatformPackPreset(
        displayName = "Rust",
        strongSignals =
          listOf(
            "Cargo.toml",
            "Cargo.lock",
            ".rs",
            "*.rs",
            "build.rs",
            "rust-toolchain.toml",
            "rustfmt.toml",
            "clippy.toml",
            "deny.toml",
            ".cargo/config.toml",
          ),
        tieBreakers =
          listOf(
            "Prefer Rust when Cargo workspace or package metadata and first-party Rust source files dominate the " +
              "changed product surface.",
            "Do not prefer Rust where it appears only as FFI bindings, wasm build tooling or artifacts, generated " +
              "code, or vendored crates around another dominant stack.",
            "Exclude target/ build output and vendored dependency trees from dominance scoring.",
          ),
      ),
    "typescript" to
      PlatformPackPreset(
        displayName = "TypeScript",
        strongSignals =
          listOf(
            "tsconfig.json",
            "tsconfig.*.json",
            ".ts",
            "*.ts",
            ".tsx",
            "*.tsx",
            ".mts",
            "*.mts",
            ".cts",
            "*.cts",
          ),
        tieBreakers =
          listOf(
            "Prefer TypeScript only when tsconfig metadata or first-party TypeScript source files dominate the " +
              "changed product surface. Treat package.json, package-lock.json, yarn.lock, pnpm-lock.yaml, bun.lockb, " +
              "biome.json, ESLint configuration, and Prettier configuration as contextual metadata only; " +
              "individually or combined, they are not TypeScript evidence without TypeScript ownership.",
            "Do not prefer TypeScript where it appears only as generated API clients, ambient declaration files, " +
              "or build tooling around another dominant stack.",
            "Exclude node_modules, dist, build and coverage output, generated clients, and generated declaration " +
              "files from dominance scoring.",
          ),
      ),
  )

val PLATFORM_PACK_PRESETS: Map<String, String> =
  PLATFORM_PACK_PRESET_DESCRIPTORS.mapValues { (_, preset) -> preset.displayName }

internal fun isRetiredPartialScaffoldKindAlias(kind: String): Boolean =
  kind.trim().lowercase() in RETIRED_PARTIAL_SCAFFOLD_KIND_ALIASES

private fun retiredPartialScaffoldKindError(kind: String): SkillBillRuntimeException =
  retiredScaffoldKindError(
    "Scaffold kind '$kind' is retired for new partial scaffold creation. " +
      "Create a full platform pack with kind '$SKILL_KIND_PLATFORM_PACK', or edit/remove existing " +
      "platform-pack content through normal authoring and removal commands instead of creating " +
      "partial scaffold pieces.",
  )

fun rejectRetiredPartialScaffoldKind(kind: String): Nothing = throw retiredPartialScaffoldKindError(kind)
