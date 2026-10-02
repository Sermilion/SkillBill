package skillbill.infrastructure.contracts.phaseoutput

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat
import kotlin.coroutines.cancellation.CancellationException

internal object StrictPhaseOutputParser {
  private val strictJsonMapper: ObjectMapper by lazy {
    ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
  }

  private val strictYamlMapper: YAMLMapper by lazy {
    YAMLMapper(YAMLFactory()).apply { enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY) }
  }

  fun parseDocument(text: String): StrictParse =
    formatsFor(text).firstNotNullOfOrNull { format ->
      parseStrict(text, format).let { result ->
        when (result) {
          is StrictParse.Success -> result
          is StrictParse.Failure ->
            result.takeIf {
              it.code == FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY
            }
        }
      }
    } ?: parseStrict(text, formatsFor(text).first())

  fun parseStrict(
    text: String,
    format: FeatureTaskRuntimePhaseOutputFormat,
  ): StrictParse =
    try {
      val mapper =
        if (format == FeatureTaskRuntimePhaseOutputFormat.JSON) {
          strictJsonMapper
        } else {
          strictYamlMapper
        }
      mapper.factory.createParser(text).use { parser ->
        val node = mapper.readTree<JsonNode>(parser)
        when {
          parser.nextToken() != null ->
            StrictParse.Failure(
              FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
              "Phase output contains trailing content or multiple documents.",
            )
          node == null ->
            StrictParse.Failure(
              FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
              "Phase output is empty and cannot be parsed as one object.",
            )
          else -> StrictParse.Success(format, node)
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (error: MismatchedInputException) {
      StrictParse.Failure(
        FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY,
        "Phase output contains a duplicate key: ${error.originalMessage}",
      )
    } catch (error: JsonProcessingException) {
      StrictParse.Failure(
        FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
        "Phase output is malformed and cannot be parsed as one document: ${error.originalMessage}",
      )
    }

  fun formatsFor(text: String): List<FeatureTaskRuntimePhaseOutputFormat> {
    val first = text.firstOrNull { !it.isWhitespace() }
    return if (first == '{' || first == '[') {
      if (looksLikeYamlFlow(text)) {
        listOf(FeatureTaskRuntimePhaseOutputFormat.JSON, FeatureTaskRuntimePhaseOutputFormat.YAML)
      } else {
        listOf(FeatureTaskRuntimePhaseOutputFormat.JSON)
      }
    } else {
      listOf(FeatureTaskRuntimePhaseOutputFormat.YAML)
    }
  }

  private fun looksLikeYamlFlow(text: String): Boolean =
    Regex("(?s)^\\s*\\{\\s*[A-Za-z_][A-Za-z0-9_-]*\\s*:").containsMatchIn(text) ||
      Regex("""(?s)^\s*\{\s*"[^"]*"\s*:[^{}]*,\s*[A-Za-z_][A-Za-z0-9_-]*\s*:""").containsMatchIn(text) ||
      containsSingleQuoteOutsideDoubleString(text)

  private fun containsSingleQuoteOutsideDoubleString(text: String): Boolean {
    var inDouble = false
    var escaped = false
    text.forEach { ch ->
      if (inDouble) {
        if (escaped) {
          escaped = false
        } else {
          when (ch) {
            '\\' -> escaped = true
            '"' -> inDouble = false
          }
        }
      } else if (ch == '"') {
        inDouble = true
      } else if (ch == '\'') {
        return true
      }
    }
    return false
  }
}
