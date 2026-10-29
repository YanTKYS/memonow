package com.yantkys.memonow

import com.yantkys.memonow.data.Post
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class PostDaysTest {
    private fun post(id: String, time: String) = Post(id = id, body = id, createdAt = Instant.parse(time).toEpochMilli())

    @Test fun japaneseMidnightMonthAndYearBoundariesUseLocalDateRatherThanUtcDate() {
        val posts = listOf(post("before", "2026-12-31T14:59:59.999Z"), post("after", "2026-12-31T15:00:00Z"))
        val japan = PostDays(posts, ZoneId.of("Asia/Tokyo"))
        assertEquals(mapOf(LocalDate.of(2026,12,31) to 1, LocalDate.of(2027,1,1) to 1), japan.counts)
        assertEquals(listOf(posts[1]), japan.visible(posts, LocalDate.of(2027,1,1)))
        val utc = PostDays(posts, ZoneOffset.UTC)
        assertEquals(mapOf(LocalDate.of(2026,12,31) to 2), utc.counts)
        assertFalse(japan.matches(posts, ZoneOffset.UTC))
        assertEquals(posts, utc.visible(posts, null))
    }

    @Test fun dstShortAndLongDaysUseCalendarBoundariesIncludingTheRepeatedHour() {
        val zone = ZoneId.of("America/New_York")
        for (day in listOf(LocalDate.of(2026,3,8), LocalDate.of(2026,11,1))) {
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            assertEquals(if (day.monthValue == 3) 23L else 25L, Duration.between(start,end).toHours())
            val posts = listOf(start.minusMillis(1), start, end.minusMillis(1), end).mapIndexed { i,t -> post("$i",t.toString()) }
            val index = PostDays(posts,zone)
            assertEquals(listOf(posts[1],posts[2]), index.visible(posts,day))
            assertEquals(2,index.counts[day])
        }
        val repeated = listOf(post("first", "2026-11-01T05:30:00Z"), post("second", "2026-11-01T06:30:00Z"))
        assertEquals(repeated,PostDays(repeated,zone).visible(repeated,LocalDate.of(2026,11,1)))
    }

    @Test fun metadataEditsReuseIndexAndTenThousandPostsKeepOriginalTieOrder() {
        val posts = (0 until 10_000).map { Post(sequence = it + 1L, id = "$it", body = "body", createdAt = 0) }
        val index = PostDays(posts,ZoneOffset.UTC)
        val edited = posts.map { it.copy(body = "changed", moodId = "fun", placeName = "沖縄") }
        assertTrue(index.matches(edited,ZoneOffset.UTC))
        assertEquals(10_000,index.counts[LocalDate.of(1970,1,1)])
        assertEquals(edited,index.visible(edited,LocalDate.of(1970,1,1)))
        assertFalse(index.matches(posts.drop(1),ZoneOffset.UTC))
        assertFalse(index.matches(posts.mapIndexed { i,p -> if(i==0) p.copy(createdAt=1) else p },ZoneOffset.UTC))
    }

    @Test fun gridsStartOnSundayAndHandleEmptyMonthsLeapYearsAndExtremeYearNavigation() {
        for (month in listOf(YearMonth.of(2026,2), YearMonth.of(2024,2), YearMonth.of(2026,12), YearMonth.of(2027,1))) {
            val cells = monthCells(month)
            assertEquals(0,cells.size % 7)
            assertTrue(cells.size in 28..42)
            assertEquals((1..month.lengthOfMonth()).map(month::atDay),cells.filterNotNull())
            assertEquals(month.atDay(1).dayOfWeek.value % 7,cells.indexOf(month.atDay(1)))
        }
        assertEquals(YearMonth.of(2027,1),shiftMonth(YearMonth.of(2026,12),1))
        assertEquals(YearMonth.of(2026,12),shiftMonth(YearMonth.of(2027,1),-1))
        assertNull(shiftMonth(YearMonth.of(Year.MAX_VALUE,12),1))
        assertNull(shiftMonth(YearMonth.of(Year.MIN_VALUE,1),-1))
    }
}
