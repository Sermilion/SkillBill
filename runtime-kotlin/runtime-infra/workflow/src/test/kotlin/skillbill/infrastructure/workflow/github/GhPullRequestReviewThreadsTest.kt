package skillbill.infrastructure.workflow.github

import skillbill.ports.review.pullrequest.model.ReviewPullRequest
import skillbill.ports.review.pullrequest.model.ReviewThreadListing
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GhPullRequestReviewThreadsTest {
  private val repoRoot = Path.of("/tmp/skillbill-review-threads")
  private val pullRequest =
    ReviewPullRequest(42, "https://github.com/acme/repo/pull/42", "acme", "repo", "feat/x", "main", "abc123")

  @Test
  fun `threads come from paged graphql with their resolved and outdated flags as reported`() {
    val calls = mutableListOf<List<String>>()
    val threads =
      GhPullRequestReviewThreads { _, args ->
        calls += args
        val page = if ("cursor=CURSOR-1" in args) SECOND_PAGE else FIRST_PAGE
        GhCommandResult(exitCode = 0, stdout = page)
      }

    val listing = assertIs<ReviewThreadListing.Ok>(threads.reviewThreads(repoRoot, pullRequest))

    assertEquals(
      listOf(Triple("T_resolved", true, false), Triple("T_outdated", false, true), Triple("T_live", false, false)),
      listing.threads.map { thread -> Triple(thread.id, thread.isResolved, thread.isOutdated) },
    )
    assertEquals("reviewer", listing.threads.last().comments.single().author)
    assertEquals(2, calls.size)
    calls.forEach { args ->
      assertEquals(listOf("api", "graphql"), args.take(2))
      assertTrue(args.last().startsWith("query=") && "reviewThreads(first:50,after:\$cursor)" in args.last())
    }
    assertTrue(calls.first().none { arg -> arg.startsWith("cursor=") })
    assertTrue("cursor=CURSOR-1" in calls.last())
  }

  @Test
  fun `truncated graphql output is unavailable rather than an empty thread list`() {
    val threads =
      GhPullRequestReviewThreads { _, _ -> GhCommandResult(exitCode = 0, stdout = FIRST_PAGE.take(120)) }

    assertIs<ReviewThreadListing.Unavailable>(threads.reviewThreads(repoRoot, pullRequest))
  }

  private companion object {
    val FIRST_PAGE =
      """
      {"data":{"repository":{"pullRequest":{"reviewThreads":{
        "pageInfo":{"hasNextPage":true,"endCursor":"CURSOR-1"},
        "nodes":[
          {"id":"T_resolved","isResolved":true,"isOutdated":false,"path":"a.kt","line":3,"originalLine":3,
           "diffSide":"RIGHT","comments":{"nodes":[]}},
          {"id":"T_outdated","isResolved":false,"isOutdated":true,"path":"b.kt","line":null,"originalLine":9,
           "diffSide":"RIGHT","comments":{"nodes":[]}}
        ]}}}}}
      """.trimIndent()

    val SECOND_PAGE =
      """
      {"data":{"repository":{"pullRequest":{"reviewThreads":{
        "pageInfo":{"hasNextPage":false,"endCursor":"CURSOR-2"},
        "nodes":[
          {"id":"T_live","isResolved":false,"isOutdated":false,"path":"c.kt","line":7,"originalLine":7,
           "diffSide":"RIGHT","comments":{"nodes":[{"id":"C1","databaseId":1,"author":{"login":"reviewer"},
           "body":"Rename this.","createdAt":"2026-09-01T00:00:00Z","url":"https://github.com/acme/repo/pull/42#c1"}]}}
        ]}}}}}
      """.trimIndent()
  }
}
