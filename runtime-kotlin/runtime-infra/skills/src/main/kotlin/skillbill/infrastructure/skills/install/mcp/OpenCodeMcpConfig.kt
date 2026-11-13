package skillbill.infrastructure.skills.install.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.contracts.persistence.RuntimeMcpEnvironmentKeys
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.atomicWriteString
import skillbill.infrastructure.skills.install.mcp.OpenCodeConfigFiles.invalid
import skillbill.infrastructure.skills.install.mcp.OpenCodeConfigFiles.mapper
import skillbill.infrastructure.skills.install.mcp.OpenCodeConfigFiles.observeSource
import skillbill.infrastructure.skills.install.mcp.OpenCodeConfigFiles.parse
import skillbill.infrastructure.skills.install.mcp.OpenCodeConfigFiles.publish
import skillbill.infrastructure.skills.install.mcp.OpenCodeConfigFiles.read
import skillbill.infrastructure.skills.install.opencode.OpenCodeRoots
import skillbill.infrastructure.skills.install.opencode.opencodeConfigRoots
import skillbill.install.model.McpMutationResult
import skillbill.install.model.McpRegistrationOutcome
import skillbill.ports.install.mcp.model.InstallMcpProfileEnvironment
import skillbill.ports.repository.toFileLocation
import skillbill.ports.system.HostPlatformPort
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal class OpenCodeMcpConfig(
  private val home: Path,
  private val environment: InstallMcpProfileEnvironment,
  private val roots: OpenCodeRoots = opencodeConfigRoots(home, environment.asEnvironmentMap()),
  private val host: HostPlatformPort = JdkHostPlatformPort,
  private val publishFile: (Path, String) -> Unit = { target, content ->
    atomicWriteString(target, content, strictAtomic = true)
  },
) {
  private object Keys {
    const val MANAGED_NAME = McpConfigKeys.SONA_SERVER
    val PRODUCT_NAMES = listOf(McpConfigKeys.SKILL_BILL_SERVER, McpConfigKeys.SONA_SERVER)
    const val MCP = "mcp"
    const val TYPE = "type"
    const val LOCAL = "local"
    const val COMMAND = "command"
    const val ENVIRONMENT = "environment"
  }

  private val coverageMessage =
    "Applied within inspected local and host-managed sources; remote account configuration was not acquired."

  fun register(
    path: Path,
    command: String,
  ): McpRegistrationOutcome {
    refusal(path, roots, host, environment)?.let { return it }
    val existed = observeSource(path) != null
    val original = read(path)
    val root = parse(original, path) as? ObjectNode ?: invalid(path)
    val mcp = root.get(Keys.MCP)
    if (mcp != null && !mcp.isObject) invalid(path)
    val mcpNode = (mcp as? ObjectNode) ?: mapper.createObjectNode()
    val normalizedCommand = normalizeCommand(command, roots.workingDirectory ?: home)
    if (hasUnanchoredRelativeDatabasePath(environment, roots, path)) {
      return refused(
        path,
        "relative runtime database binding cannot be anchored without the captured caller working directory",
      )
    }
    val normalizedEnvironment = normalizedEnvironment(home, environment, roots)
    val desired = managedEntry(normalizedCommand, normalizedEnvironment)
    val upToDate = mcpNode.get(Keys.MANAGED_NAME) == desired && !mcpNode.has(McpConfigKeys.SKILL_BILL_SERVER)
    return if (upToDate) applied(path, changed = false) else writeEntry(path, original, existed, desired)
  }

  private fun writeEntry(
    path: Path,
    original: String,
    existed: Boolean,
    desired: ObjectNode,
  ): McpRegistrationOutcome {
    val withoutProductEntries = removeProductEntries(original, path)
    val rootSpan = objectSpan(withoutProductEntries, path, emptyList())
    val mcpSpan = OpenCodeJsoncEditor.objectAt(withoutProductEntries, listOf(Keys.MCP))
    val patched =
      if (mcpSpan == null) {
        OpenCodeJsoncEditor.insertProperty(
          withoutProductEntries,
          rootSpan,
          Keys.MCP,
          "{\"${Keys.MANAGED_NAME}\": ${mapper.writeValueAsString(desired)}}",
        )
      } else {
        OpenCodeJsoncEditor.insertProperty(
          withoutProductEntries,
          mcpSpan,
          Keys.MANAGED_NAME,
          mapper.writeValueAsString(desired),
        )
      }
    verifyRegistrationPostcondition(original, patched, path, desired)
    if (!publish(path, existed, original, patched, publishFile)) {
      return refused(path, "configuration changed while registration was being prepared")
    }
    return applied(path, changed = true)
  }

  fun unregister(path: Path): McpRegistrationOutcome {
    refusal(path, roots, host, environment)?.let { return it }
    val existed = observeSource(path) != null
    val original = read(path)
    val root = parse(original, path) as? ObjectNode ?: invalid(path)
    val mcpValue = root.get(Keys.MCP)
    if (mcpValue != null && !mcpValue.isObject) invalid(path)
    if (!hasProductEntry(root)) return applied(path, changed = false)
    val patched = removeProductEntries(original, path)
    verifyRemovalPostcondition(original, patched, path)
    if (!publish(path, existed, original, patched, publishFile)) {
      return refused(path, "configuration changed while removal was being prepared")
    }
    return applied(path, changed = true)
  }

  private fun removeProductEntries(
    source: String,
    path: Path,
  ): String =
    Keys.PRODUCT_NAMES.fold(source) { current, name ->
      val mcpSpan = OpenCodeJsoncEditor.objectAt(current, listOf(Keys.MCP)) ?: return@fold current
      val property = mcpSpan.properties.singleOrNull { it.key == name } ?: return@fold current
      OpenCodeJsoncEditor.removeProperty(current, mcpSpan, property).also { parse(it, path) }
    }

  private fun hasProductEntry(root: ObjectNode): Boolean {
    val servers = root.get(Keys.MCP) as? ObjectNode ?: return false
    return Keys.PRODUCT_NAMES.any(servers::has)
  }

  private fun applied(
    path: Path,
    changed: Boolean,
  ): McpRegistrationOutcome.Applied =
    McpRegistrationOutcome.Applied(
      McpMutationResult("opencode", path.toFileLocation(), changed = changed, coverage = coverageMessage),
    )

  private fun managedEntry(
    command: String,
    context: Map<String, String>,
  ): ObjectNode =
    mapper.createObjectNode().apply {
      put(Keys.TYPE, Keys.LOCAL)
      putArray(Keys.COMMAND).add(command)
      putObject(Keys.ENVIRONMENT).apply {
        put(RuntimeMcpEnvironmentKeys.RUNTIME_MCP_BIN, command)
        context.forEach { (key, value) -> put(key, value) }
      }
    }

  private fun refusal(
    target: Path,
    roots: OpenCodeRoots,
    host: HostPlatformPort,
    environment: InstallMcpProfileEnvironment,
  ): McpRegistrationOutcome.ProfilesFailed? {
    if (roots.projectConfigEnabled && roots.workingDirectory == null) {
      return refused(target, "project configuration sources cannot be inspected without the caller working directory")
    }
    val macLogin =
      if (host.osName.contains("mac", ignoreCase = true)) {
        host.resolveUserName()?.takeIf(String::isNotBlank)
      } else {
        null
      }
    if (host.osName.contains("mac", ignoreCase = true) && macLogin == null) {
      return refused(
        "macOS managed preferences",
        "current login identity is unavailable, so user managed policy cannot be inspected",
      )
    }
    localSourceRefusal(target, roots)?.let { return it }
    return inlineSourceRefusal(roots) ?: managedSourceRefusal(environment, host, macLogin)
  }

  private fun localSourceRefusal(
    target: Path,
    roots: OpenCodeRoots,
  ): McpRegistrationOutcome.ProfilesFailed? {
    roots.knownConfigSources.filter { normalized(it) != normalized(target) }.forEach { source ->
      if (observeSource(source) == null) return@forEach
      val root = parse(read(source), source) as? ObjectNode ?: invalid(source)
      if (hasUnresolvedManagedSubtree(root)) {
        return refused(source, "a Sona MCP entry in another configuration source may shadow the selected target")
      }
    }
    return null
  }

  private fun inlineSourceRefusal(roots: OpenCodeRoots): McpRegistrationOutcome.ProfilesFailed? {
    if (roots.inlineContent?.isNotBlank() == true) {
      val inlineSource = Path.of("OPENCODE_CONFIG_CONTENT")
      val root = parse(roots.inlineContent, inlineSource) as? ObjectNode ?: invalid(inlineSource)
      if (hasUnresolvedManagedSubtree(root)) {
        return refused(
          "OPENCODE_CONFIG_CONTENT",
          "inline configuration defines a Sona MCP entry and cannot be safely persisted",
        )
      }
    }
    return null
  }

  private fun managedSourceRefusal(
    environment: InstallMcpProfileEnvironment,
    host: HostPlatformPort,
    macLogin: String?,
  ): McpRegistrationOutcome.ProfilesFailed? {
    hostManagedSources(environment, host, macLogin).forEach { source ->
      if (observeSource(source) != null) {
        if (source.fileName.toString().endsWith(".plist")) {
          return refused(
            source.toString(),
            "managed preferences are present but this adapter cannot safely decode them",
          )
        }
        val root = parse(read(source), source) as? ObjectNode ?: invalid(source)
        if (hasUnresolvedManagedSubtree(root)) {
          return refused(source, "a host-managed Sona MCP entry may override the selected local target")
        }
      }
    }
    return null
  }

  private fun hostManagedSources(
    profile: InstallMcpProfileEnvironment,
    host: HostPlatformPort,
    macLogin: String?,
  ): List<Path> =
    when {
      host.osName.contains("win", ignoreCase = true) -> {
        val programData = profile.programData?.takeIf(String::isNotBlank) ?: "C:\\ProgramData"
        val root = Path.of(programData).resolve("opencode")
        listOf(root.resolve("opencode.json"), root.resolve("opencode.jsonc"))
      }
      host.osName.contains("mac", ignoreCase = true) -> {
        val managedPreferences = Path.of("/Library/Managed Preferences")
        val userPreferences =
          managedPreferences.resolve(macLogin ?: return emptyList()).resolve("ai.opencode.managed.plist")
        listOf(
          userPreferences,
          managedPreferences.resolve("ai.opencode.managed.plist"),
          Path.of("/Library/Application Support/opencode/opencode.json"),
          Path.of("/Library/Application Support/opencode/opencode.jsonc"),
        )
      }
      else -> listOf(Path.of("/etc/opencode/opencode.json"), Path.of("/etc/opencode/opencode.jsonc"))
    }

  private fun normalizedEnvironment(
    home: Path,
    environment: InstallMcpProfileEnvironment,
    roots: OpenCodeRoots,
  ): Map<String, String> {
    val value = environment.databasePath ?: return emptyMap()
    val expanded =
      when {
        value == "~" -> home.toString()
        value.startsWith("~/") || value.startsWith("~\\") -> home.resolve(value.drop(2)).toString()
        else -> value
      }
    val database =
      try {
        Path.of(expanded)
      } catch (error: InvalidPathException) {
        invalid(Path.of("OpenCode runtime environment"), error)
      }
    val absolute =
      if (database.isAbsolute) {
        database
      } else {
        roots.workingDirectory?.resolve(database) ?: invalid(Path.of("OpenCode runtime environment"))
      }
    return mapOf(RuntimeDatabaseEnvironmentKeys.DATABASE_PATH to absolute.toAbsolutePath().normalize().toString())
  }

  private fun hasUnanchoredRelativeDatabasePath(
    environment: InstallMcpProfileEnvironment,
    roots: OpenCodeRoots,
    path: Path,
  ): Boolean {
    val value = environment.databasePath ?: return false
    if (value == "~" || value.startsWith("~/") || value.startsWith("~\\")) return false
    val database =
      try {
        Path.of(value)
      } catch (error: InvalidPathException) {
        invalid(path, error)
      }
    return !database.isAbsolute && roots.workingDirectory == null
  }

  private fun normalizeCommand(
    command: String,
    base: Path,
  ): String {
    val candidate = Path.of(command)
    return (if (candidate.isAbsolute) candidate else base.resolve(candidate)).toAbsolutePath().normalize().toString()
  }

  private fun hasUnresolvedManagedSubtree(root: ObjectNode): Boolean {
    val servers = root.get(Keys.MCP) ?: return false
    return !servers.isObject || Keys.PRODUCT_NAMES.any(servers::has)
  }

  private fun objectSpan(
    source: String,
    path: Path,
    propertyPath: List<String>,
  ): OpenCodeJsoncEditor.ObjectSpan = OpenCodeJsoncEditor.objectAt(source, propertyPath) ?: invalid(path)

  private fun verifyRegistrationPostcondition(
    before: String,
    after: String,
    path: Path,
    desired: JsonNode,
  ) {
    val beforeTree = parse(before, path).deepCopy<JsonNode>() as ObjectNode
    val afterTree = parse(after, path).deepCopy<JsonNode>() as ObjectNode
    val afterMcp = afterTree.path(Keys.MCP) as? ObjectNode ?: invalid(path)
    if (afterMcp.get(Keys.MANAGED_NAME) != desired || afterMcp.has(McpConfigKeys.SKILL_BILL_SERVER)) invalid(path)
    afterMcp.remove(Keys.PRODUCT_NAMES)
    if (afterMcp.isEmpty) afterTree.remove(Keys.MCP)
    val beforeMcp = beforeTree.path(Keys.MCP) as? ObjectNode
    beforeMcp?.remove(Keys.PRODUCT_NAMES)
    if (beforeMcp?.isEmpty == true) beforeTree.remove(Keys.MCP)
    if (beforeTree != afterTree) invalid(path)
  }

  private fun verifyRemovalPostcondition(
    before: String,
    after: String,
    path: Path,
  ) {
    val beforeTree = parse(before, path).deepCopy<JsonNode>() as ObjectNode
    val afterTree = parse(after, path).deepCopy<JsonNode>() as ObjectNode
    val beforeMcp = beforeTree.path(Keys.MCP) as? ObjectNode ?: invalid(path)
    if (Keys.PRODUCT_NAMES.none(beforeMcp::has)) invalid(path)
    beforeMcp.remove(Keys.PRODUCT_NAMES)
    if (beforeMcp.isEmpty) beforeTree.remove(Keys.MCP)
    val afterMcp = afterTree.path(Keys.MCP) as? ObjectNode
    if (afterMcp != null && Keys.PRODUCT_NAMES.any(afterMcp::has)) invalid(path)
    if (afterMcp?.isEmpty == true) afterTree.remove(Keys.MCP)
    if (beforeTree != afterTree || before == after) invalid(path)
  }

  private fun normalized(path: Path): Path = path.toAbsolutePath().normalize()

  private fun refused(
    source: String,
    reason: String,
  ): McpRegistrationOutcome.ProfilesFailed =
    McpRegistrationOutcome.ProfilesFailed(
      "OpenCode config refusal at '$source': $reason. Original bytes were preserved; " +
        "resolve the named source and retry.",
      succeeded = emptyList(),
    )

  private fun refused(
    path: Path,
    reason: String,
  ): McpRegistrationOutcome.ProfilesFailed =
    McpRegistrationOutcome.ProfilesFailed(
      "OpenCode config refusal at '$path': $reason. Original bytes were preserved; resolve the named source and retry.",
      succeeded = emptyList(),
    )
}

internal fun openCodeTargetPath(roots: OpenCodeRoots): Path {
  val explicit = roots.explicitConfigFile
  if (explicit != null) return explicit
  val jsonc = roots.configDirectory.resolve("opencode.jsonc")
  return if (OpenCodeConfigFiles.observeSource(
      jsonc,
    ) != null
  ) {
    jsonc
  } else {
    roots.configDirectory.resolve("opencode.json")
  }
}
