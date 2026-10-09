package skillbill.review.parsing

internal val reviewRunIdPattern =
  Regex(
    "^Review run ID:\\s*(?<value>[A-Za-z0-9._:-]+)\\s*$",
    RegexOption.MULTILINE,
  )
internal val reviewSessionIdPattern =
  Regex(
    "^Review session ID:\\s*(?<value>[A-Za-z0-9._:-]+)\\s*$",
    RegexOption.MULTILINE,
  )
internal val summaryPatterns: Map<String, Regex> =
  mapOf(
    "routed_skill" to Regex("^Routed to:\\s*(?<value>.+?)\\s*$", RegexOption.MULTILINE),
    "detected_scope" to Regex("^Detected review scope:\\s*(?<value>.+?)\\s*$", RegexOption.MULTILINE),
    "detected_stack" to Regex("^Detected stack:\\s*(?<value>.+?)\\s*$", RegexOption.MULTILINE),
    "execution_mode" to Regex("^Execution mode:\\s*(?<value>inline|delegated)\\s*$", RegexOption.MULTILINE),
  )

internal val reportedExecutionModePattern =
  Regex("^Execution mode:\\s*(?<value>\\S.*?)\\s*$", RegexOption.MULTILINE)
internal val specialistReviewsPattern =
  Regex(
    "^(?:Specialist reviews|Baseline review|Backend specialist reviews|KMP specialist reviews):\\s*(?<value>.+?)\\s*$",
    RegexOption.MULTILINE,
  )
internal val findingPattern =
  Regex(
    "^\\s*-\\s+\\[(?<findingId>F-\\d{3})]\\s+" +
      "(?<severity>Blocker|Major|Minor)\\s+\\|\\s+" +
      "(?<confidenceLevel>High|Medium|Low)\\s+\\|\\s+" +
      "(?:specialist=(?<specialistSkillName>[a-z0-9-]+)\\s+\\|\\s+)?" +
      "(?<location>[^|]+?)\\s+\\|\\s+" +
      "(?<description>.+)$",
    RegexOption.MULTILINE,
  )

internal val findingProvenancePattern =
  Regex("\\s*\\|\\s*(?<provenance>(?:specialists?|origins)=[^|]*)$")
internal val findingSpecialistsProvenancePattern = Regex("specialists?=(?<value>[^;]+)")

internal val severityAliases: Map<String, String> =
  mapOf(
    "high" to "Major",
    "medium" to "Minor",
    "low" to "Minor",
    "p1" to "Blocker",
    "p2" to "Major",
    "p3" to "Minor",
    "critical" to "Blocker",
    "blocker" to "Blocker",
    "major" to "Major",
    "minor" to "Minor",
    "info" to "Minor",
  )
