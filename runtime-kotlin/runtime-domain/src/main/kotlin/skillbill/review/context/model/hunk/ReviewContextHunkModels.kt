package skillbill.review.context.model.hunk

import skillbill.review.model.requireRepositoryRelativePath
import skillbill.text.sha256HexUtf8

data class ReviewHunkEvidenceLocator(
  val storePath: String,
  val hunkHeader: String,
  val payloadFile: String = PAYLOAD_FILE,
) {
  init {
    requireRepositoryRelativePath(storePath)
    require(payloadFile == PAYLOAD_FILE) { "Hunk evidence payload file must be '$PAYLOAD_FILE'." }
    require(hunkHeader.isNotBlank()) { "Hunk evidence locator header must not be blank." }
  }

  val canonical: String get() = canonicalFields(storePath, payloadFile, hunkHeader)

  companion object {
    const val PAYLOAD_FILE: String = "diff.patch"

    fun header(
      oldStart: Int,
      oldCount: Int,
      newStart: Int,
      newCount: Int,
    ): String = "@@ -$oldStart,$oldCount +$newStart,$newCount @@"

    fun inProcess(
      contentDigest: String,
      oldStart: Int,
      oldCount: Int,
      newStart: Int,
      newCount: Int,
    ): ReviewHunkEvidenceLocator =
      ReviewHunkEvidenceLocator(
        storePath = ".skill-bill/run-evidence/in-process/$contentDigest",
        hunkHeader = header(oldStart, oldCount, newStart, newCount),
      )

    fun atStore(
      storePath: String,
      oldStart: Int,
      oldCount: Int,
      newStart: Int,
      newCount: Int,
    ): ReviewHunkEvidenceLocator =
      ReviewHunkEvidenceLocator(
        storePath = storePath,
        hunkHeader = header(oldStart, oldCount, newStart, newCount),
      )
  }
}

data class ReviewChangedHunk(
  val path: String,
  val oldStart: Int,
  val oldCount: Int,
  val newStart: Int,
  val newCount: Int,
  val content: String,
  val commitScope: String? = null,
  internal val indexedContentDigest: String? = null,
  internal val indexedEvidenceLocator: ReviewHunkEvidenceLocator? = null,
  internal val indexedHunkId: String? = null,
  internal val indexedContentBytes: Long? = null,
) {
  init {
    requireRepositoryRelativePath(path)
    require(
      oldStart >= REVIEW_MIN_HUNK_LINE && oldCount >= REVIEW_MIN_HUNK_LINE &&
        newStart >= REVIEW_MIN_HUNK_LINE && newCount >= REVIEW_MIN_HUNK_LINE,
    )
    require(commitScope == null || commitScope.isNotBlank()) { "Changed hunk commit scope must not be blank." }
    indexedContentDigest?.let {
      require(it.matches(SHA256_HEX)) { "Changed hunk content digest must be lowercase SHA-256." }
    }
    indexedHunkId?.let { require(it.matches(SHA256_HEX)) { "Changed hunk id must be lowercase SHA-256." } }
  }

  val contentDigest: String = indexedContentDigest ?: sha256HexUtf8(content.replace("\r\n", "\n"))

  val evidenceLocator: ReviewHunkEvidenceLocator =
    indexedEvidenceLocator
      ?: ReviewHunkEvidenceLocator.inProcess(contentDigest, oldStart, oldCount, newStart, newCount)

  val hunkId: String = indexedHunkId ?: sha256HexUtf8(canonicalIdentity(this, content))

  val contentBytes: Long =
    indexedContentBytes
      ?: content.replace("\r\n", "\n").toByteArray(Charsets.UTF_8).size.toLong()

  internal fun packetCanonical(): String =
    canonicalFields(
      hunkId,
      oldStart,
      oldCount,
      newStart,
      newCount,
      contentDigest,
      evidenceLocator.canonical,
    )

  fun asIndex(
    locator: ReviewHunkEvidenceLocator,
    body: String,
  ): ReviewChangedHunk {
    val normalized = body.replace("\r\n", "\n")
    return copy(
      content = "",
      indexedContentDigest = digestOfBody(normalized),
      indexedEvidenceLocator = locator,
      indexedHunkId = idFor(this, normalized),
      indexedContentBytes = normalized.toByteArray(Charsets.UTF_8).size.toLong(),
    )
  }

  companion object {
    fun digestOfBody(body: String): String = sha256HexUtf8(body.replace("\r\n", "\n"))

    fun idFor(
      hunk: ReviewChangedHunk,
      body: String = hunk.content,
    ): String = sha256HexUtf8(canonicalIdentity(hunk, body))

    private fun canonicalIdentity(
      hunk: ReviewChangedHunk,
      body: String,
    ): String =
      canonicalFields(
        hunk.path,
        hunk.oldStart,
        hunk.oldCount,
        hunk.newStart,
        hunk.newCount,
        body.replace("\r\n", "\n"),
        hunk.commitScope.orEmpty(),
      )
  }
}
