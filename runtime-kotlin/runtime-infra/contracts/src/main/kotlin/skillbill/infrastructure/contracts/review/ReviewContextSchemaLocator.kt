package skillbill.infrastructure.contracts.review

import skillbill.error.shellcontent.invalidReviewContextSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader

internal fun readReviewContextSchemaText(): String =
  ClasspathContractSchemaLoader.readClasspathYamlText(
    classLoader = ReviewContextSchemaValidator::class.java.classLoader,
    resource = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
    missingError = {
      invalidReviewContextSchemaError(
        sourceLabel = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
        reason =
          "Canonical review context schema is missing. Expected to find it on the JVM classpath at " +
            "'$REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE'.",
      )
    },
  )
