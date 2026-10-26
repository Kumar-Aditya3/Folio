package com.folio.reader.surfaces

internal fun reminderDue(nowMinute: Int, chosenMinute: Int, today: String, lastSent: String?, hasRead: Boolean): Boolean =
    !hasRead && lastSent != today && chosenMinute in 0..1439 && nowMinute >= chosenMinute &&
        nowMinute < (chosenMinute + 90).coerceAtMost(1440)

internal fun goalPercent(minutes: Long, goal: Int): Int =
    if (goal <= 0) 0 else (minutes.coerceAtLeast(0).coerceAtMost(goal.toLong()) * 100 / goal).toInt()

internal fun nextReadingChapter(chapters: List<com.folio.reader.manga.MangaChapter>): com.folio.reader.manga.MangaChapter? =
    chapters.filter { !it.read }.minWithOrNull(compareBy<com.folio.reader.manga.MangaChapter> {
        val number = if (it.chapterNumber >= 0) it.chapterNumber else com.folio.reader.manga.ChapterNumberParser.parse(it.name)
        if (number >= 0) number else Float.MAX_VALUE
    }.thenBy { it.sortOrder })
