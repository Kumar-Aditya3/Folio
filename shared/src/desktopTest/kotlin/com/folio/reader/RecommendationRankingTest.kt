package com.folio.reader

import com.folio.reader.manga.MangaDetail
import com.folio.reader.manga.MangaEntry
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.ui.home.mangaAffinity
import com.folio.reader.ui.home.rankBookRecommendations
import com.folio.reader.ui.home.recommendationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommendationRankingTest {
    private fun book(id: String, number: Double? = null, series: String? = null,
        author: String = "Writer", status: BookStatus = BookStatus.UNREAD) = Book(
        id, id, authors = listOf(author), seriesId = series, seriesNumber = number,
        status = status, epubHash = id, epubFileSize = 1)

    @Test fun nextVolumesLeadRegardlessOfLibraryOrderAndEarlierVolumesStayOut() {
        val anchor = book("finished", 2.0, "series", status = BookStatus.FINISHED)
        val books = listOf(book("other"), book("fourth", 4.0, "series"), book("first", 1.0, "series"),
            book("third", 3.0, "series", author = "Someone else"), book("unknown-volume", series = "series"))
        assertEquals(listOf("third", "fourth", "unknown-volume", "other"),
            rankBookRecommendations(anchor, books).map { it.id })
        assertEquals(rankBookRecommendations(anchor, books), rankBookRecommendations(anchor, books.reversed()))
    }

    @Test fun onlyUnreadBooksAreSuggestedAndPlaceholderAuthorsDoNotMatch() {
        val anchor = book("finished", status = BookStatus.FINISHED)
        val books = BookStatus.entries.map { book(it.name, status = it) }
        assertEquals(listOf("UNREAD"), rankBookRecommendations(anchor, books).map { it.id })
        for (name in listOf("", "Unknown", "N/A", "Anonymous")) {
            assertNull(recommendationReason(book("anchor", author = name), book("candidate", author = name)))
        }
        assertEquals("By the same author", recommendationReason(book("a", author = " A. Writer "), book("b", author = "a writer")))
        assertNull(recommendationReason(book("a", author = "Doe, John"), book("b", author = "Smith, John")))
    }

    @Test fun mangaRequiresEvidenceAndCreatorMatchBeatsGenreOverlap() {
        val anchor = MangaEntry("a", 1, "Source", "/a", "Read title", author = "A. Creator",
            genres = listOf("Action", "Fantasy", "Mystery"))
        assertNull(mangaAffinity(MangaDetail("/none", "No metadata"), listOf(anchor)))
        assertNull(mangaAffinity(MangaDetail("/broad", "Too broad", genres = listOf("Action")), listOf(anchor)))
        assertNull(mangaAffinity(MangaDetail("/other", "Unrelated", genres = listOf("Romance", "Comedy")), listOf(anchor)))
        val genre = assertNotNull(mangaAffinity(MangaDetail("/genre", "Related", genres = listOf("Fantasy", "Mystery")), listOf(anchor)))
        val creator = assertNotNull(mangaAffinity(MangaDetail("/creator", "Creator", artist = "a creator"), listOf(anchor)))
        assertTrue(creator.score > genre.score)
        assertEquals("Shares a creator with Read title", creator.reason)
        assertTrue(genre.reason.contains("Mystery"))
        assertTrue(genre.reason.contains("Read title"))
        assertNull(mangaAffinity(MangaDetail("/unknown", "Unknown", author = "Unknown"), listOf(anchor.copy(author = "Unknown"))))
        assertNull(mangaAffinity(MangaDetail("/format", "Same format", genres = listOf("Manga", "Shounen")),
            listOf(anchor.copy(genres = listOf("Manga", "Shounen")))))
    }

    @Test fun newerReadsHaveMoreInfluenceAndAGenreSpecificMatchWorks() {
        val first = MangaEntry("a", 1, "Source", "/a", "Recent", genres = listOf("Sports"))
        val second = first.copy(id = "b", title = "Older")
        val candidate = MangaDetail("/c", "Candidate", genres = listOf("sports"))
        assertTrue(assertNotNull(mangaAffinity(candidate, listOf(first, second))).reason.contains("Recent"))
        assertNull(mangaAffinity(candidate, emptyList()))
    }
}
