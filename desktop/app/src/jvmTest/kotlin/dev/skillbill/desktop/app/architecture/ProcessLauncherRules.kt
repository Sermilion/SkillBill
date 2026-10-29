package dev.skillbill.desktop.app.architecture

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtUserType
import java.nio.file.Files
import java.nio.file.Path

private data class ProductionSource(val path: String, val text: String)

private data class ProcessReferences(
  val imports: List<String>,
  val qualifiedNames: Set<String>,
  val names: Set<String>,
  val runtimeTypes: Set<String>,
  val typeAliases: Map<String, String>,
)

internal object ProcessLauncherRules {
  private val launcherNames = setOf("Process", "ProcessBuilder", "ProcessHandle", "Runtime", "PtyProcess", "PtyProcessBuilder")
  private val allowedLaunchers = setOf(
    "app/src/jvmMain/kotlin/dev/skillbill/desktop/app/motion/OsReducedMotionReader.kt",
    "feature/terminal/src/jvmMain/kotlin/dev/skillbill/desktop/feature/terminal/PtyShellLauncher.kt",
  )

  fun violations(desktopRoot: Path, settings: String, manifests: List<ModuleManifest>): List<String> {
    val normalizedRoot = desktopRoot.toAbsolutePath().normalize()
    val includedProjects = ModuleGraphRules.includedProjects(settings)
    val manifestedProjects = manifests.map { it.project }.toSet()
    val violations = buildList {
      if (manifests.isEmpty()) add("No architecture manifests were supplied for the process-launcher scan.")
      (includedProjects - manifestedProjects).forEach { add("Declared production module $it is missing from the architecture manifests.") }
      (manifestedProjects - includedProjects).forEach { add("Architecture manifest $it is not declared in settings.gradle.kts.") }
    }.toMutableList()

    val sources = manifests.flatMap { manifest ->
      val roots = manifest.sourceDirectories.filterKeys { it == "commonMain" || it == "jvmMain" }.values.flatten().distinct()
      if (roots.isEmpty()) {
        violations += "${manifest.project} declares no production Kotlin source directories."
      }
      val moduleSources = roots.flatMap { relativeRoot ->
        val root = normalizedRoot.resolve(relativeRoot).normalize()
        if (!root.startsWith(normalizedRoot)) {
          violations += "${manifest.project} declares a production source directory outside the desktop root: $relativeRoot"
          emptyList()
        } else if (!Files.isDirectory(root)) {
          emptyList()
        } else {
          readSources(root, normalizedRoot)
        }
      }.distinctBy(ProductionSource::path)
      if (moduleSources.isEmpty()) violations += "${manifest.project} production source directories contain no Kotlin sources."
      moduleSources
    }.distinctBy(ProductionSource::path)

    if (sources.isEmpty()) return violations + "The governed production source scan is empty."

    val references = scanReferences(sources)
    val aliases = references.values.flatMap { it.typeAliases.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.toSet() }
    val aliasesToLaunchers = aliases.keys.filterTo(mutableSetOf()) { alias -> resolvesToLauncher(alias, aliases) }
    references.forEach { (source, found) ->
      val hits = buildSet {
        found.imports.forEach { fqName ->
          if (fqName in launcherQualifiedNames) add(fqName)
        }
        found.qualifiedNames.filterTo(this) { name ->
          launcherQualifiedNames.any { name == it || name.startsWith("$it.") } ||
            name == "Runtime.getRuntime" || name.startsWith("Runtime.getRuntime.")
        }
        found.runtimeTypes.filterTo(this) { it == "Runtime" }
        found.names.filterTo(this) { it in launcherNames - "Runtime" || it in aliasesToLaunchers }
      }
      if (hits.isNotEmpty() && source.path !in allowedLaunchers) {
        violations += "${source.path} references process launcher APIs ${hits.sorted()}; add an exact approved path only when the launcher is required."
      }
    }
    allowedLaunchers.filter { it !in sources.map(ProductionSource::path) }
      .forEach { violations += "Required process launcher allowlist entry is not in the production scan: $it" }
    return violations
  }

  private val launcherQualifiedNames = setOf(
    "java.lang.Process",
    "java.lang.ProcessBuilder",
    "java.lang.ProcessHandle",
    "java.lang.Runtime",
    "com.pty4j.PtyProcess",
    "com.pty4j.PtyProcessBuilder",
  )

  private fun resolvesToLauncher(name: String, aliases: Map<String, Set<String>>, seen: Set<String> = emptySet()): Boolean =
    if (name in seen) false else name in launcherNames - "Runtime" || name in launcherQualifiedNames ||
      aliases[name].orEmpty().any { resolvesToLauncher(it, aliases, seen + name) }

  private fun readSources(root: Path, desktopRoot: Path): List<ProductionSource> = Files.walk(root).use { paths ->
    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
      .map { path -> ProductionSource(desktopRoot.relativize(path).toString().replace('\\', '/'), Files.readString(path).replace("\r\n", "\n")) }
      .toList()
  }

  @OptIn(K1Deprecation::class, CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
  private fun scanReferences(sources: List<ProductionSource>): Map<ProductionSource, ProcessReferences> {
    val disposable = Disposer.newDisposable()
    return try {
      val environment = KotlinCoreEnvironment.createForProduction(
        disposable,
        CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() },
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
      )
      val factory = KtPsiFactory(environment.project, false)
      sources.associateWith { source ->
        val file = factory.createFile(source.path.substringAfterLast('/'), source.text)
        val imports = file.importDirectives.mapNotNull { directive ->
          directive.importedFqName?.asString()
        }
        val qualified = PsiTreeUtil.findChildrenOfType(file, KtDotQualifiedExpression::class.java).mapNotNull(::dottedName) +
          PsiTreeUtil.findChildrenOfType(file, KtUserType::class.java).mapNotNull(::dottedName)
        val names = PsiTreeUtil.findChildrenOfType(file, KtNameReferenceExpression::class.java).map { it.getReferencedName() }.toSet()
        val runtimeTypes = PsiTreeUtil.findChildrenOfType(file, KtUserType::class.java).mapNotNull(::dottedName).filter { it == "Runtime" }.toSet()
        val aliases = PsiTreeUtil.findChildrenOfType(file, KtTypeAlias::class.java).mapNotNull { alias ->
          val aliasName = alias.name ?: return@mapNotNull null
          val target = alias.getTypeReference()?.typeElement?.let(::dottedName) ?: return@mapNotNull null
          aliasName to target
        }.toMap()
        ProcessReferences(imports, qualified.toSet(), names, runtimeTypes, aliases)
      }
    } finally {
      Disposer.dispose(disposable)
    }
  }

  private fun dottedName(element: PsiElement?): String? = when (element) {
    is KtNameReferenceExpression -> element.getReferencedName()
    is KtCallExpression -> (element.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
    is KtDotQualifiedExpression -> {
      val receiver = dottedName(element.receiverExpression)
      val selector = dottedName(element.selectorExpression)
      if (receiver != null && selector != null) "$receiver.$selector" else receiver
    }
    is KtUserType -> {
      val name = element.referencedName
      val qualifier = element.qualifier?.let(::dottedName)
      if (name == null) null else if (qualifier == null) name else "$qualifier.$name"
    }
    else -> null
  }
}
