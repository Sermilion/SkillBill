package skillbill.agent.buildrun

import skillbill.agent.model.BuildMatchContext
import skillbill.agent.model.BuildRepositoryFiles
import skillbill.scaffold.model.BuildToolDeclaration
import skillbill.scaffold.model.BuildToolVersionSource

private val GRADLE_DISTRIBUTION = Regex("""distributionUrl\s*=.*gradle-(\d+)\.(\d+)""")
private val PACKAGE_MANAGER = Regex(""""packageManager"\s*:\s*"([^"@]+)@(\d+)""")
private val GO_DIRECTIVE = Regex("""^go\s+(\d+)\.(\d+)""", RegexOption.MULTILINE)
private val XCODE_VERSION = Regex("""<key>CFBundleShortVersionString</key>\s*<string>(\d+)""")
private const val DEFAULT_XCODE_VERSION_PLIST = "/Applications/Xcode.app/Contents/version.plist"

internal fun buildToolLabel(
  declaration: BuildToolDeclaration,
  context: BuildMatchContext,
  files: BuildRepositoryFiles,
): String {
  val root = context.repositoryRoot
  val version =
    when (declaration.versionSource) {
      null -> null
      BuildToolVersionSource.GRADLE_WRAPPER ->
        files.readText(underDirectory(root, "gradle/wrapper/gradle-wrapper.properties"))
          ?.let(GRADLE_DISTRIBUTION::find)
          ?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
      BuildToolVersionSource.PACKAGE_MANAGER ->
        files.readText(underDirectory(root, "package.json"))
          ?.let(PACKAGE_MANAGER::find)
          ?.takeIf { it.groupValues[1] == declaration.executable }
          ?.groupValues
          ?.get(2)
      BuildToolVersionSource.GO_MOD ->
        files.readText(underDirectory(root, "go.mod"))
          ?.let(GO_DIRECTIVE::find)
          ?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
      BuildToolVersionSource.XCODE_APP -> {
        val plist =
          context.xcodeDeveloperDirectory?.let { underDirectory(it, "../version.plist") }
            ?: DEFAULT_XCODE_VERSION_PLIST
        files.readText(plist)?.let(XCODE_VERSION::find)?.groupValues?.get(1)
      }
    }
  return if (version == null) declaration.tool else "${declaration.tool} $version"
}

internal fun underDirectory(
  directory: String,
  relativePath: String,
): String = "${directory.trimEnd('/', '\\')}/$relativePath"
