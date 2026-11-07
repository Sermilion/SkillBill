package dev.skillbill.desktop.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class ValidationReportShapeTest {
  @Test
  fun `heading without body remains visible in report`() {
    val shape = shapeValidationReport("Summary.\n\n**Warnings.**\n\n**Checks.**\nPassed.")!!

    assertEquals(listOf("Warnings", "Checks"), shape.parts.map { it.title })
  }

  @Test
  fun `report headings split parts and warnings are flagged`() {
    val shape = shapeValidationReport(
      """
      Validation finished. The branch is clean.

      Extra context before the first section.

      **Checks run.**
      - Unit tests passed.

      **Couldn't run.**
      - Browser tests were skipped.

      Warnings:
      The later phases could not run concurrently.
      """.trimIndent(),
    )!!

    assertEquals("Validation finished.", shape.headline)
    assertEquals(listOf("Details", "Checks run", "Couldn't run", "Warnings"), shape.parts.map { it.title })
    assertEquals(listOf("Couldn't run", "Warnings"), shape.parts.filter { it.flagged }.map { it.title })
    assertEquals(listOf("Checks run"), shape.parts.filter { it.openByDefault }.map { it.title })
  }
}
