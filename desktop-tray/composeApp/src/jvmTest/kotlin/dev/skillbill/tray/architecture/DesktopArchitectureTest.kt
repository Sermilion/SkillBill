package dev.skillbill.tray.architecture

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopArchitectureTest {
  @Test
  fun `domain and presentation stay outside storage and runtime adapters`() {
    val sources = desktopSources()
    val presentation = sources.filter { "/presentation/" in it.path || "/ui/" in it.path || it.project == ":core:designsystem" }
    val domain = sources.filter { it.project == ModuleGraphRules.DOMAIN }
    val application = sources.filter { "/application/" in it.path }

    assertTrue(domain.isNotEmpty())
    assertTrue(presentation.isNotEmpty())
    assertTrue(application.isNotEmpty())
    assertTrue(domain.all { !referencesAny(it.text, forbiddenStorageImports) })
    assertTrue(presentation.all { !referencesAny(it.text, forbiddenStorageImports) })
    assertTrue(application.all { !referencesAny(it.text, forbiddenApplicationImports) })
    assertTrue(presentation.all { source -> forbiddenPresentationTokens.none { it in source.text } })
    assertTrue((domain + application).all { source -> forbiddenCoreTokens.none { it in source.text } })
  }

  @Test
  fun `work data avoids process adapters and explicit run actions use the runtime launch port`() {
    val workDataSources = desktopSources()
    val sources = workDataSources + runtimeSources()
    assertApprovedNativeDisplayQuery(sources)
    assertTrue(workDataSources.isNotEmpty(), "The governed production scan set is empty.")
    workDataSources.forEach { source ->
      if (source.path !in approvedNativeHostProcessFiles) {
        assertFalse(referencesProcessAdapter(source.text), source.path)
      }
    }
    val launchConsumers = sources.filter { "import skillbill.ports.work.WorkRunLauncher" in it.text }
    assertTrue(launchConsumers.map { it.path.substringAfter("/src/main/kotlin/") }.toSet() == setOf(
      "skillbill/engine/work/control/WorkRunActions.kt",
      "skillbill/engine/work/control/WorkCancellation.kt",
      "skillbill/infrastructure/launcher/work/DetachedWorkRunLauncher.kt",
      "skillbill/di/core/RuntimeContext.kt",
      "skillbill/di/core/RuntimeWorkControlProvides.kt",
    ), "Run launch ownership changed: ${launchConsumers.map { it.path }}")
    assertNoReachableWorkDataProcessAdapter(sources)
  }

  private fun assertNoReachableWorkDataProcessAdapter(sources: List<Source>) {
    val functions = parsedSymbols(sources)
    assertTrue(functions.isNotEmpty(), "The governed function scan set is empty.")
    listOf("control", "detail", "existingSnapshot").forEach { name ->
      assertTrue(functions.any { it.path.endsWith("/RoomBoardSource.kt") && it.name == name },
        "RoomBoardSource.$name is missing from the parsed graph.")
    }
    governedFunctionRoots.forEach { root ->
      assertTrue(functions.any { it.path.contains(root) }, "The governed function root is missing: $root")
    }
    val byName = functions.groupBy(FunctionBody::name)
    val desktopPaths = sources.filter { it.project != null }.map(Source::path).toSet()
    val roots = functions.filter { function ->
      (function.path in desktopPaths &&
        (function.path.contains("/composition/") || function.path.contains("/application/") ||
          function.path.contains("/infrastructure/data/") || function.name in controlEntryPoints))
        || (function.path.endsWith("/skillbill/engine/work/IdeStatusService.kt") && function.name == "board")
    }
    assertTrue(roots.isNotEmpty(), "No composition, application, data, or control entry points were parsed.")
    val sourcePackages = functions.map(FunctionBody::packageName).toSet()
    val visited = mutableSetOf<FunctionBody>()
    val pending = ArrayDeque(roots.map { it to listOf(it.path + ":" + it.name) })
    while (pending.isNotEmpty()) {
      val (function, trace) = pending.removeFirst()
      if (!visited.add(function)) continue
      if (function.name in setOf("start", "stop") && "WorkRunLauncher" in function.memberOwners) {
        assertTrue(function.path.endsWith("/skillbill/ports/work/WorkRunLauncher.kt") ||
          function.path.endsWith("/skillbill/infrastructure/launcher/work/DetachedWorkRunLauncher.kt"), trace.joinToString(" -> "))
        continue
      }
      assertFalse(function.calls.any { it in forbiddenWorkDataProcessAdapters }, function.path + ":" + function.name)
      assertFalse(function.launchesCommand && !isApprovedNativeDisplayQuery(function), trace.joinToString(" -> "))
      function.calls.forEach { name ->
        val declarations = byName[name]
        val imported = function.imports[name]
        val importedPackage = imported?.substringBeforeLast('.')
        val projectOwned = (name in function.calledFunctions || declarations.orEmpty().any {
          !it.functionDeclaration && it.packageName == importedPackage && it.memberOwners.isEmpty() && it.localOwner == null
        } || name.firstOrNull()?.isUpperCase() == true) &&
          (importedPackage in sourcePackages || declarations.orEmpty().any {
          it.packageName in function.wildcardPackages && it.localOwner == null && it.memberOwners.isEmpty()
        } ||
          imported?.startsWith("skillbill.") == true || imported?.startsWith("dev.skillbill.") == true)
        val importedTarget = function.imports[name]
        val visiblePackages = buildSet {
          add(function.packageName)
          importedTarget?.substringBeforeLast('.')?.let(::add)
          addAll(function.wildcardPackages)
        }
        val candidates = declarations?.filter { target ->
          val explicitlyImported = imported == "${target.packageName}.${target.name}" ||
            target.memberOwners.any { imported == "${target.packageName}.$it.${target.name}" }
          (!target.functionDeclaration || name in function.calledFunctions) &&
            (explicitlyImported || target.packageName in visiblePackages || target.memberOwners.any { it in function.visibleNames }) &&
            (explicitlyImported || target.memberOwners.isEmpty() || target.path == function.path ||
              target.memberOwners.any { it in function.visibleNames }) &&
            (target.localOwner == null || target.path == function.path && target.localOwner == function.contextOwner)
        } ?: if (projectOwned) {
          error("Unresolved governed edge at ${function.path}:${function.name}: $name, import=$imported")
        } else emptyList()
        if (candidates.isEmpty() && projectOwned) error("Unresolved governed edge at ${function.path}:${function.name}: $name, import=$imported")
        candidates.forEach { target -> pending.addLast(target to trace + (target.path + ":" + target.name)) }
      }
    }
    listOf(
      "/skillbill/engine/work/IdeStatusService.kt" to "board",
      "/skillbill/engine/work/IdeStatusService.kt" to "projectBoardSelection",
      "/skillbill/engine/work/IdeStatusService.kt" to "detail",
      "/skillbill/engine/work/control/WorkControlService.kt" to "request",
    ).forEach { (path, name) ->
      assertTrue(visited.any { it.path.endsWith(path) && it.name == name }, "Unresolved governed adapter edge: $path:$name")
    }
  }

  private fun assertApprovedNativeDisplayQuery(sources: List<Source>) {
    val placement = sources.single { it.path == "$DESKTOP_PACKAGE_ROOT/infrastructure/TrayPopoverPlacement.kt" }
    assertTrue(Regex("""\bProcessBuilder\s*\(""").findAll(placement.text).count() == 1)
    assertTrue(Regex("""ProcessBuilder\("kscreen-doctor", "-j"\)""").findAll(placement.text).count() == 1)
    assertFalse("Runtime.getRuntime().exec" in placement.text)
  }

  private fun isApprovedNativeDisplayQuery(function: FunctionBody): Boolean =
    function.path.endsWith("/infrastructure/TrayPopoverPlacement.kt") &&
      function.name in setOf("queryKdeDisplayLayout", "process")

  @OptIn(K1Deprecation::class, CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
  private fun parsedSymbols(sources: List<Source>): List<FunctionBody> {
    val disposable = Disposer.newDisposable()
    return try {
      val environment = KotlinCoreEnvironment.createForProduction(
        disposable,
        CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() },
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
      )
      val factory = KtPsiFactory(environment.project, false)
      val files = sources.associateWith { source -> factory.createFile(source.path.substringAfterLast('/'), source.text) }
      val governedTypes = files.values.flatMap { PsiTreeUtil.findChildrenOfType(it, KtClassOrObject::class.java) }
        .mapNotNull { it.name }.toSet()
      val propertyTypes = files.values.flatMap { PsiTreeUtil.findChildrenOfType(it, KtProperty::class.java) }
        .mapNotNull { property -> property.typeReference?.text?.let {
          property.name to it.substringBefore('<').removeSuffix("?").substringAfterLast('.')
        } }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
      val returnTypes = files.values.flatMap { PsiTreeUtil.findChildrenOfType(it, KtNamedFunction::class.java) }
        .mapNotNull { function -> function.typeReference?.text?.let {
          function.name to it.substringBefore('<').removeSuffix("?").substringAfterLast('.')
        } }.groupBy({ it.first }, { it.second }).mapNotNull { (name, types) ->
          types.distinct().singleOrNull()?.let { name to it }
        }.toMap()
      val namesByPackage = files.values.groupBy { it.packageFqName.asString() }.mapValues { (_, packageFiles) ->
        packageFiles.flatMap { PsiTreeUtil.findChildrenOfType(it, KtNamedDeclaration::class.java) }
          .filter { it is KtNamedFunction || it is KtClassOrObject || it is KtProperty }
          .mapNotNull { it.name }.toSet()
      }
      files.flatMap { (source, file) ->
        val aliases = file.importDirectives.mapNotNull { directive ->
          val fqName = directive.importedFqName?.asString() ?: return@mapNotNull null
          (directive.aliasName ?: fqName.substringAfterLast('.')) to fqName.substringAfterLast('.')
        }.toMap()
        val visibleNames = namesByPackage[file.packageFqName.asString()].orEmpty() + aliases.keys +
          file.importDirectives.filter { it.isAllUnder }.flatMap {
            namesByPackage[it.importedFqName?.asString()].orEmpty()
          }
        (PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java) +
          PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java) +
          PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java)).map { declaration ->
          val owner = declaration as? KtClassOrObject
            ?: PsiTreeUtil.getParentOfType(declaration, KtClassOrObject::class.java, true)
          val typedReceivers = owner?.declarations.orEmpty().filterIsInstance<KtProperty>().mapNotNull { property ->
            val type = property.typeReference?.text ?: (property.initializer as? KtCallExpression)?.calleeExpression?.text
            type?.let { property.name to it.substringBefore('<').removeSuffix("?").substringAfterLast('.') }
          }.toMap() + ((declaration as? KtNamedFunction)?.valueParameters.orEmpty() +
            owner?.primaryConstructorParameters.orEmpty()).mapNotNull { parameter ->
              parameter.typeReference?.text?.let { parameter.name to it.substringBefore('<').removeSuffix("?").substringAfterLast('.') }
            }.toMap()
          val actualCalls = ownedChildren(declaration, KtCallExpression::class.java).filter { call ->
            val qualified = call.parent as? KtDotQualifiedExpression
            val receiver = qualified?.receiverExpression
            val receiverType = typedReceivers[receiver?.text]
              ?: (receiver as? KtCallExpression)?.calleeExpression?.text?.let {
                returnTypes[it] ?: it.takeIf { name -> name.firstOrNull()?.isUpperCase() == true }
              }
            (receiverType == null || receiverType in governedTypes) &&
              (call.calleeExpression?.text in visibleNames || qualified?.selectorExpression === call)
          }
          FunctionBody(
            name = declaration.name.orEmpty(),
            path = source.path,
            packageName = file.packageFqName.asString(),
            imports = file.importDirectives.filterNot { it.isAllUnder }.mapNotNull { directive ->
              directive.importedFqName?.asString()?.let { it.substringAfterLast('.') to it }
            }.toMap(),
            wildcardPackages = file.importDirectives.filter { it.isAllUnder }
              .mapNotNull { it.importedFqName?.asString() }.toSet(),
            localOwner = PsiTreeUtil.getParentOfType(declaration, KtNamedFunction::class.java, true)?.name,
            contextOwner = (declaration as? KtNamedFunction)?.name
              ?: PsiTreeUtil.getParentOfType(declaration, KtNamedFunction::class.java, true)?.name,
            functionDeclaration = declaration is KtNamedFunction,
            memberOwners = buildSet {
              owner?.name?.let(::add)
              owner?.superTypeListEntries.orEmpty().flatMap {
                Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(it.text).map(MatchResult::value).toList()
              }.forEach(::add)
            },
            visibleNames = visibleNames + ownedChildren(declaration, KtNameReferenceExpression::class.java)
              .flatMap { propertyTypes[it.getReferencedName()].orEmpty() },
            calledFunctions = actualCalls.mapNotNull { it.calleeExpression?.text?.substringAfterLast('.') }
              .map { aliases[it] ?: it }.toSet() +
              ownedChildren(declaration, KtCallableReferenceExpression::class.java)
                .map { it.callableReference.getReferencedName() }.map { aliases[it] ?: it },
            launchesCommand = ownedChildren(declaration, KtCallExpression::class.java).any { call ->
              val expression = PsiTreeUtil.getParentOfType(call, org.jetbrains.kotlin.psi.KtDotQualifiedExpression::class.java)
                ?.text ?: call.text
              Regex("\\bProcessBuilder\\b[^;]*\\.start\\s*\\(").containsMatchIn(expression) ||
                Regex("\\bRuntime\\.getRuntime\\(\\)\\.exec\\s*\\(").containsMatchIn(expression)
            },
            calls = buildSet {
              actualCalls.mapNotNull { it.calleeExpression?.text?.substringAfterLast('.') }
                .mapTo(this) { aliases[it] ?: it }
              ownedChildren(declaration, KtNameReferenceExpression::class.java)
                .filter { reference ->
                  reference.getReferencedName() in visibleNames ||
                    (reference.parent as? KtDotQualifiedExpression)?.selectorExpression === reference ||
                    (reference.parent.parent as? KtDotQualifiedExpression)?.selectorExpression === reference.parent
                }
                .mapTo(this) { aliases[it.getReferencedName()] ?: it.getReferencedName() }
              ownedChildren(declaration, KtTypeReference::class.java)
                .flatMap { Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(it.text).map(MatchResult::value).toList() }
                .filter { it in visibleNames }
                .mapTo(this) { aliases[it] ?: it }
              ownedChildren(declaration, KtCallableReferenceExpression::class.java)
                .map { it.callableReference.getReferencedName() }.mapTo(this) { aliases[it] ?: it }
            },
          )
        }
      }
    } finally {
      Disposer.dispose(disposable)
    }
  }

  private fun <T : PsiElement> ownedChildren(declaration: KtDeclaration, type: Class<T>): List<T> =
    PsiTreeUtil.findChildrenOfType(declaration, type).filter { child ->
      generateSequence(child.parent) { it.parent }.takeWhile { it !== declaration }
        .none { it is KtNamedFunction || it is KtClassOrObject || it is KtProperty }
    }

  private fun importsAny(source: String, prefixes: List<String>): Boolean =
    source.lineSequence().map(String::trim).filter { it.startsWith("import ") }
      .map { it.removePrefix("import ").substringBefore(" as ").trim() }
      .any { imported -> prefixes.any { imported == it || imported.startsWith("$it.") } }

  private fun referencesAny(source: String, prefixes: List<String>): Boolean =
    importsAny(source, prefixes) || prefixes.any { prefix ->
      Regex("\\b${Regex.escape(prefix)}(?:\\.[A-Za-z_][A-Za-z0-9_]*)+\\b").containsMatchIn(source)
    }

  private fun referencesProcessAdapter(source: String): Boolean {
    val forbiddenImports = setOf("java.lang.Process", "java.lang.ProcessBuilder", "java.lang.ProcessHandle", "java.lang.Runtime")
    val aliases = source.lineSequence().map(String::trim).filter { it.startsWith("import ") && " as " in it }
      .mapNotNull { line ->
        val parts = line.removePrefix("import ").split(" as ", limit = 2)
        parts.lastOrNull()?.takeIf { parts.first() in forbiddenImports }
      }.toSet()
    val forbiddenNames = setOf("Process", "ProcessBuilder", "ProcessHandle", "Runtime") + aliases
    val names = forbiddenNames.joinToString("|") { Regex.escape(it) }
    return Regex("\\b(?:java\\.lang\\.)?(?:$names)\\b").containsMatchIn(source) ||
      Regex("\\b(?:java\\.lang\\.)?Runtime\\.getRuntime\\(\\)\\.exec\\b").containsMatchIn(source)
  }

  private data class FunctionBody(val name: String, val path: String, val calls: Set<String>, val launchesCommand: Boolean,
    val localOwner: String?, val contextOwner: String?, val functionDeclaration: Boolean, val calledFunctions: Set<String>,
    val packageName: String, val imports: Map<String, String>, val wildcardPackages: Set<String>,
    val memberOwners: Set<String>, val visibleNames: Set<String>)

  private companion object {
    val forbiddenStorageImports = listOf(
      "dev.skillbill.tray.infrastructure",
      "skillbill.engine",
      "skillbill.infrastructure",
      "java.sql",
      "org.sqlite",
      "androidx.room3",
      "androidx.paging",
      "androidx.sqlite",
      "androidx.datastore",
    )
    val forbiddenApplicationImports = listOf(
      "skillbill.engine",
      "skillbill.infrastructure.sqlite",
      "org.sqlite",
      "androidx.room3",
      "androidx.paging",
      "androidx.sqlite",
      "androidx.datastore",
      "java.sql",
    )
    val forbiddenPresentationTokens = listOf(
      "kotlinx.serialization",
      "JsonObject",
      "JsonElement",
      "java.io.File",
      "java.nio.file",
      "File(",
    )
    val forbiddenCoreTokens = listOf("java.awt", "javax.swing", "androidx.compose", "ComposeWindow")
    val controlEntryPoints = setOf("control", "pause", "stop", "perform")
    val governedFunctionRoots = setOf("/composition/", "/application/", "/infrastructure/data/")
    val forbiddenWorkDataProcessAdapters = setOf(
      "CliBoardSource", "CliResolver", "ProcessRunner", "WorkPayloadDecoder", "WorkPayloadKeys",
    )
    const val DESKTOP_PACKAGE_ROOT = "composeApp/src/jvmMain/kotlin/dev/skillbill/tray"
    val approvedNativeHostProcessFiles = setOf(
      "$DESKTOP_PACKAGE_ROOT/infrastructure/NativeServicesImpl.kt",
      "$DESKTOP_PACKAGE_ROOT/infrastructure/NativeTrayHost.kt",
      "$DESKTOP_PACKAGE_ROOT/infrastructure/TrayPopoverPlacement.kt",
      "$DESKTOP_PACKAGE_ROOT/infrastructure/macos/MacStatusItemBridge.kt",
    )
  }
}
