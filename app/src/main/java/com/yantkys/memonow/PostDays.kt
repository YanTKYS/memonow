package com.yantkys.memonow

import com.yantkys.memonow.data.Post
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Position index: edits/metadata emissions reuse date conversion and counts. */
internal class PostDays(posts: List<Post>, val zone: ZoneId) {
    private val times = LongArray(posts.size) { posts[it].createdAt }
    private val positions = posts.indices.groupBy { Instant.ofEpochMilli(times[it]).atZone(zone).toLocalDate() }
    val counts: Map<LocalDate, Int> = positions.mapValues { it.value.size }

    fun matches(posts: List<Post>, zone: ZoneId) = this.zone == zone && posts.size == times.size &&
        posts.indices.all { posts[it].createdAt == times[it] }

    fun visible(posts: List<Post>, day: LocalDate?): List<Post> =
        if (day == null) posts else positions[day].orEmpty().map { posts[it] }
}

internal fun monthCells(month: YearMonth): List<LocalDate?> {
    val offset = month.atDay(1).dayOfWeek.value % 7 // Sunday first.
    val size = ((offset + month.lengthOfMonth() + 6) / 7) * 7
    return List(size) { index ->
        (index - offset + 1).takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay)
    }
}

internal fun shiftMonth(month: YearMonth, delta: Long): YearMonth? = runCatching { month.plusMonths(delta) }.getOrNull()
