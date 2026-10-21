package com.sentinel.admin.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for [RouteDateRangeCalculator] verifying boundary rules,
 * timezone awareness, input parsing, and validation logic.
 */
class RouteDateRangeCalculatorTest {

    private val fixedZone = ZoneId.of("UTC")

    @Test
    fun getTodayRange_returnsInclusiveSingleDayForToday() {
        val today = LocalDate.now(fixedZone)
        val (start, end) = RouteDateRangeCalculator.getTodayRange(fixedZone)

        assertEquals(today, start)
        assertEquals(today, end)
        assertEquals(start, end)
    }

    @Test
    fun getSevenDaysRange_returnsExactlySevenInclusiveDays() {
        val today = LocalDate.now(fixedZone)
        val (start, end) = RouteDateRangeCalculator.getSevenDaysRange(fixedZone)

        assertEquals(today, end)
        assertEquals(today.minusDays(6), start)
        assertEquals(7, java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1)
    }

    @Test
    fun validateRange_acceptsValidChronologicalRange() {
        val today = LocalDate.now(fixedZone)
        val start = today.minusDays(10).toString()
        val end = today.minusDays(2).toString()

        val result = RouteDateRangeCalculator.validateRange(start, end, fixedZone)
        assertTrue(result is DateValidationResult.Success)
        val success = result as DateValidationResult.Success
        assertEquals(LocalDate.parse(start), success.startDate)
        assertEquals(LocalDate.parse(end), success.endDate)
    }

    @Test
    fun validateRange_acceptsSingleDayRange() {
        val today = LocalDate.now(fixedZone)
        val dateStr = today.minusDays(5).toString()

        val result = RouteDateRangeCalculator.validateRange(dateStr, dateStr, fixedZone)
        assertTrue(result is DateValidationResult.Success)
        val success = result as DateValidationResult.Success
        assertEquals(success.startDate, success.endDate)
    }

    @Test
    fun validateRange_trimsWhitespace() {
        val today = LocalDate.now(fixedZone)
        val start = "  " + today.minusDays(4).toString() + "  "
        val end = " " + today.minusDays(1).toString() + " "

        val result = RouteDateRangeCalculator.validateRange(start, end, fixedZone)
        assertTrue(result is DateValidationResult.Success)
    }

    @Test
    fun validateRange_rejectsEmptyInputs() {
        val r1 = RouteDateRangeCalculator.validateRange("", "2026-10-10", fixedZone)
        assertTrue(r1 is DateValidationResult.Error)
        assertEquals("Start date cannot be empty", (r1 as DateValidationResult.Error).message)

        val r2 = RouteDateRangeCalculator.validateRange("2026-10-10", "   ", fixedZone)
        assertTrue(r2 is DateValidationResult.Error)
        assertEquals("End date cannot be empty", (r2 as DateValidationResult.Error).message)
    }

    @Test
    fun validateRange_rejectsMalformedDateFormat() {
        val r1 = RouteDateRangeCalculator.validateRange("2026/10/10", "2026-10-11", fixedZone)
        assertTrue(r1 is DateValidationResult.Error)
        assertTrue((r1 as DateValidationResult.Error).message.contains("Invalid start date format"))

        val r2 = RouteDateRangeCalculator.validateRange("2026-10-10", "not-a-date", fixedZone)
        assertTrue(r2 is DateValidationResult.Error)
        assertTrue((r2 as DateValidationResult.Error).message.contains("Invalid end date format"))
    }

    @Test
    fun validateRange_rejectsFutureDates() {
        val today = LocalDate.now(fixedZone)
        val tomorrow = today.plusDays(1).toString()
        val yesterday = today.minusDays(1).toString()

        val r1 = RouteDateRangeCalculator.validateRange(tomorrow, tomorrow, fixedZone)
        assertTrue(r1 is DateValidationResult.Error)
        assertTrue((r1 as DateValidationResult.Error).message.contains("future"))

        val r2 = RouteDateRangeCalculator.validateRange(yesterday, tomorrow, fixedZone)
        assertTrue(r2 is DateValidationResult.Error)
        assertTrue((r2 as DateValidationResult.Error).message.contains("End date cannot be in the future"))
    }

    @Test
    fun validateRange_rejectsInvertedDates() {
        val today = LocalDate.now(fixedZone)
        val earlier = today.minusDays(5).toString()
        val later = today.minusDays(2).toString()

        val result = RouteDateRangeCalculator.validateRange(later, earlier, fixedZone)
        assertTrue(result is DateValidationResult.Error)
        assertEquals("Start date cannot be after end date", (result as DateValidationResult.Error).message)
    }
}
