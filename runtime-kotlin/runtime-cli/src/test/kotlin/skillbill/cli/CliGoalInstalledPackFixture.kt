package skillbill.cli

import skillbill.contracts.JsonCodec
import skillbill.contracts.nativeagent.NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION
import java.nio.file.Files
import java.nio.file.Path

internal fun installGoalBuildPack(home: Path) {
  val cache = home.resolve(".skill-bill/installed-skills/native-agents-0000000000000000")
  val pack = cache.resolve("review-catalog/platform-packs/kotlin")
  Files.createDirectories(pack)
  Files.writeString(
    pack.resolve("platform.yaml"),
    """
    platform: kotlin
    contract_version: "1.8"
    routing_signals:
      strong: ["*.kt"]
      path: ["*.kt"]
    declared_code_review_areas: []
    validation_gate:
      full_gate_command: [./gradlew, check]
      cache_bypassing_full_gate_command: [./gradlew, check, --no-build-cache]
      collect_all_full_gate_command: [./gradlew, check, --continue]
      cache_bypassing_collect_all_full_gate_command: [./gradlew, check, --continue, --no-build-cache]
      build_command: [./gradlew, compileKotlin]
      cache_bypassing_build_command: [./gradlew, compileKotlin, --no-build-cache]
      findings:
        format: junit_xml
        artifact_globs: ["build/test-results/**/*.xml"]
        compiler_diagnostics: {format: gradle_kotlin_compiler_stdout}
    """.trimIndent(),
  )
  Files.writeString(
    home.resolve(".skill-bill/native-agent-link-inventory.json"),
    JsonCodec.valueToJsonString(
      mapOf(
        "contract_version" to NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION,
        "entries" to
          listOf(
            mapOf(
              "logical_name" to "bill-kotlin",
              "provider" to "codex",
              "installed_path" to home.resolve(".agents/agents/bill-kotlin.toml").toString(),
              "cache_target_path" to cache.resolve("codex-agents/bill-kotlin.toml").toString(),
              "content_digest" to "0".repeat(64),
              "source_root" to home.toString(),
            ),
          ),
      ),
    ),
  )
}
