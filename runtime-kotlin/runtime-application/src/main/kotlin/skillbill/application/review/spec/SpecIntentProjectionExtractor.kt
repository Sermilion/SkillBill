package skillbill.application.review.spec

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.repoRelativePath
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.review.context.ReviewContextWireMap
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.execution.SpecIntentProjection
import skillbill.review.context.model.execution.SpecIntentProvenance
import skillbill.review.context.model.execution.SpecIntentSurroundingContext
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest

@Inject
class SpecIntentProjectionExtractor(
  private val envelopeValidator: ReviewContextEnvelopeValidator,
  private val fileStore: DecompositionManifestStore,
) {
  internal fun extract(
    repoRoot: Path,
    specPath: Path,
    budget: ReviewContextBudgetPolicy,
    surrounding: SpecIntentSurroundingContext? = null,
  ): SpecIntentSourceRead<SpecIntentProjection> {
    val normalized = resolvedParentSpecPath(repoRoot, specPath)
    val bytes =
      when (val read = readSpecBytes(normalized)) {
        is SpecIntentSourceRead.Unavailable -> return read
        is SpecIntentSourceRead.Read -> read.value
      }
    val specText = bytes.toString(Charsets.UTF_8)
    val intendedOutcome =
      GovernedSpecSectionParser.parseProseSection(specText, ::isIntendedOutcomeHeading)
        .ifBlank { documentTitle(specText) }
    if (intendedOutcome.isBlank()) {
      return unavailable(normalized, "unparseable")
    }
    val projection =
      SpecIntentProjection(
        intendedOutcome = intendedOutcome,
        acceptanceCriteria =
          GovernedSpecSectionParser.parseListSection(specText) {
            it.startsWith(ACCEPTANCE_CRITERIA_PREFIX)
          },
        constraints = GovernedSpecSectionParser.parseListSection(specText) { it.startsWith(CONSTRAINTS_PREFIX) },
        nonGoals =
          GovernedSpecSectionParser.parseListSection(specText) { title ->
            title.startsWith(NON_GOALS_PREFIX) || title == NON_GOALS_SPACED
          },
        deferredItems = GovernedSpecSectionParser.parseListSection(specText) { it.startsWith(DEFERRED_PREFIX) },
        provenance =
          SpecIntentProvenance(
            specPath = repoRelativePath(repoRoot, normalized),
            contentDigest = sha256Hex(bytes),
          ),
        declaredByteBudget = budget.maxSpecIntentProjectionBytes.toInt().coerceAtLeast(1),
        surroundingContext = surrounding,
      )
    return try {
      envelopeValidator.validateSpecIntentProjection(
        ReviewContextWireMap.from(projection.toProjectionPayload()),
        "spec_intent_projection",
      )
      SpecIntentSourceRead.Read(projection)
    } catch (error: InvalidReviewContextSchemaError) {
      unavailable(normalized, "unparseable", error)
    }
  }

  internal fun surroundingContext(
    repoRoot: Path,
    specPath: Path,
  ): SpecIntentSourceRead<SpecIntentSurroundingContext> {
    val normalized = resolvedParentSpecPath(repoRoot, specPath)
    return when (val read = readSpecBytes(normalized)) {
      is SpecIntentSourceRead.Unavailable -> read
      is SpecIntentSourceRead.Read ->
        SpecIntentSourceRead.Read(
          SpecIntentSurroundingContext(
            specPath = repoRelativePath(repoRoot, normalized),
            contentDigest = sha256Hex(read.value),
          ),
        )
    }
  }

  private fun readSpecBytes(path: Path): SpecIntentSourceRead<ByteArray> {
    if (!fileStore.isRegularFile(path)) {
      return unavailable(path, "missing")
    }
    return try {
      SpecIntentSourceRead.Read(fileStore.readText(path).toByteArray(Charsets.UTF_8))
    } catch (error: IOException) {
      error.rethrowIfCooperativeCancellationOrInterruption()
      unavailable(path, "unreadable")
    }
  }

  private fun isIntendedOutcomeHeading(title: String): Boolean =
    title.startsWith(INTENDED_OUTCOME_PREFIX) || title == SCOPE_HEADING

  private fun documentTitle(specText: String): String {
    val heading =
      specText.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("#") && !it.startsWith("##") }
        ?: return ""
    return heading.trimStart('#').trim()
  }

  private fun unavailable(
    path: Path,
    reason: String,
    cause: Throwable? = null,
  ): SpecIntentSourceRead.Unavailable = SpecIntentSourceRead.Unavailable(path.toString(), reason, cause)

  private companion object {
    const val INTENDED_OUTCOME_PREFIX = "intended outcome"
    const val SCOPE_HEADING = "scope"
    const val CONSTRAINTS_PREFIX = "constraints"
    const val NON_GOALS_PREFIX = "non-goal"
    const val NON_GOALS_SPACED = "non goals"
    const val DEFERRED_PREFIX = "deferred"
  }
}

/** The outcome of reading a spec intent source: the value read, or why the source is unavailable. */
internal sealed interface SpecIntentSourceRead<out T> {
  data class Read<T>(val value: T) : SpecIntentSourceRead<T>

  data class Unavailable(
    val specPath: String,
    val reason: String,
    val cause: Throwable? = null,
  ) : SpecIntentSourceRead<Nothing>
}

private fun sha256Hex(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }
