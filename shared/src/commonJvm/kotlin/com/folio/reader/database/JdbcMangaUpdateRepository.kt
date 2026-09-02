package com.folio.reader.database

import com.folio.reader.manga.ChapterNumberParser
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaChapterRef
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaRepository
import com.folio.reader.manga.MangaUpdateRepository
import com.folio.reader.manga.MangaUpdateRunResult
import com.folio.reader.manga.MangaUpdateState
import com.folio.reader.manga.chapterId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * JDBC-backed §11.3 update checks. Extensions are third-party HTTP: per run at most 20
 * manga, at most 2 sources in parallel, serial per source with >=1s between requests,
 * 30s per-request timeout, a source failing twice is dropped, 10-minute total budget.
 * Successive runs cover the whole library through the persisted round-robin cursor.
 */
class JdbcMangaUpdateRepository(
    private val db: Database,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: MangaChapterRepository,
    private val backend: MangaBackend,
) : MangaUpdateRepository {

    private data class GroupOutcome(val checked: Int, val seriesWithNew: Int, val newChapters: Int)

    private sealed interface CheckOutcome {
        data class Success(val newChapters: Int) : CheckOutcome
        data class Failure(val message: String) : CheckOutcome
    }

    override suspend fun runUpdateCheck(): MangaUpdateRunResult {
        val startedAtNanos = System.nanoTime()
        // Sources visible right now = local + loaded extensions. A missing or untrusted
        // extension never loads, so its sources are absent from observeSources() — this
        // is the §11.3 "never check an untrusted source" gate.
        val availableSourceIds = runCatching {
            withTimeoutOrNull(SOURCE_SNAPSHOT_TIMEOUT_MS) {
                backend.observeSources().first().map { it.id }.toSet()
            }
        }.getOrNull() ?: return MangaUpdateRunResult(0, 0, 0)

        val library = runCatching { mangaRepository.observeLibrary().first() }
            .getOrDefault(emptyList())
            .filter { it.inLibrary }
            .sortedWith(nullsLast(compareByDescending { it.lastReadAt }))
        if (library.isEmpty()) return MangaUpdateRunResult(0, 0, 0)

        val start = readCursor().mod(library.size)
        val batch = buildList {
            repeat(minOf(MAX_MANGA_PER_RUN, library.size)) { i ->
                add(library[(start + i).mod(library.size)])
            }
        }

        var checked = 0
        var seriesWithNew = 0
        var newTotal = 0
        try {
            coroutineScope {
                val sourceSlots = Semaphore(MAX_SOURCES_IN_PARALLEL)
                batch.filter { it.sourceId in availableSourceIds }
                    .groupBy { it.sourceId }
                    .values
                    .map { group ->
                        async {
                            sourceSlots.withPermit { checkSourceGroup(group, startedAtNanos) }
                        }
                    }
                    .awaitAll()
                    .forEach { outcome ->
                        checked += outcome.checked
                        seriesWithNew += outcome.seriesWithNew
                        newTotal += outcome.newChapters
                    }
            }
        } finally {
            // Persist even when the run is cancelled mid-way: re-checking the same window
            // on the next run is safe, silently skipping it is not.
            withContext(NonCancellable) {
                runCatching { writeCursor((start + batch.size).mod(library.size)) }
            }
        }
        return MangaUpdateRunResult(checked, seriesWithNew, newTotal)
    }

    private suspend fun checkSourceGroup(group: List<MangaEntry>, startedAtNanos: Long): GroupOutcome {
        var checked = 0
        var seriesWithNew = 0
        var newChapters = 0
        var failures = 0
        var lastRequestNanos = 0L
        for (manga in group) {
            if (overBudget(startedAtNanos)) break
            if (failures >= SOURCE_FAILURE_LIMIT) break
            // >=1s between requests to the same source.
            if (lastRequestNanos != 0L) {
                val sinceMs = (System.nanoTime() - lastRequestNanos) / 1_000_000
                if (sinceMs < MIN_REQUEST_INTERVAL_MS) delay(MIN_REQUEST_INTERVAL_MS - sinceMs)
            }
            lastRequestNanos = System.nanoTime()
            when (val outcome = checkManga(manga)) {
                is CheckOutcome.Success -> {
                    checked += 1
                    if (outcome.newChapters > 0) {
                        seriesWithNew += 1
                        newChapters += outcome.newChapters
                    }
                }
                is CheckOutcome.Failure -> failures += 1
            }
        }
        return GroupOutcome(checked, seriesWithNew, newChapters)
    }

    private suspend fun checkManga(manga: MangaEntry): CheckOutcome {
        val refs: List<MangaChapterRef>? = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { backend.fetchChapterList(manga.sourceId, manga.url) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            recordFailure(manga, e.message ?: e::class.java.simpleName)
            return CheckOutcome.Failure(e.message ?: "error")
        }
        if (refs == null) {
            recordFailure(manga, "Timed out fetching chapters")
            return CheckOutcome.Failure("timeout")
        }
        return try {
            val existingUrls = chapterRepository.getChapters(manga.id).map { it.url }.toSet()
            val newCount = refs.count { it.url !in existingUrls }
            if (newCount > 0) {
                // Replace with the full remote list; replaceChapters restores
                // read/bookmark/progress on rows whose url still exists.
                chapterRepository.replaceChapters(
                    manga.id,
                    refs.mapIndexed { index, ref -> mapChapter(manga, ref, index) },
                )
            }
            upsertState(manga.id, storedCount(manga.id) + newCount, null)
            CheckOutcome.Success(newCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            recordFailure(manga, e.message ?: e::class.java.simpleName)
            CheckOutcome.Failure(e.message ?: "error")
        }
    }

    private suspend fun recordFailure(manga: MangaEntry, message: String) {
        runCatching { upsertState(manga.id, storedCount(manga.id), message) }
    }

    /** Mirrors the MangaDetail/MangaLibrary refresh mapping. */
    private fun mapChapter(manga: MangaEntry, ref: MangaChapterRef, index: Int) = MangaChapter(
        id = chapterId(manga.id, ref.url),
        mangaId = manga.id,
        url = ref.url,
        name = ref.name,
        scanlator = ref.scanlator,
        chapterNumber = if (ref.chapterNumber >= 0f) ref.chapterNumber else ChapterNumberParser.parse(ref.name),
        dateUpload = ref.dateUpload,
        sortOrder = index,
    )

    override fun observeUpdateStates(): Flow<List<MangaUpdateState>> = db.mangaDataRevision.map {
        queryStates()
    }

    override suspend fun clearNewChapters(mangaId: String) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE manga_update_state SET new_chapter_count = 0 WHERE manga_id = ?").use {
                it.setString(1, mangaId)
                it.executeUpdate()
            }
        }
        db.bumpMangaData()
    }

    private suspend fun queryStates(): List<MangaUpdateState> = db.withConnection { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT manga_id, last_checked_at, new_chapter_count, last_error " +
                    "FROM manga_update_state ORDER BY last_checked_at DESC"
            ).use { rs ->
                val list = mutableListOf<MangaUpdateState>()
                while (rs.next()) {
                    list += MangaUpdateState(
                        mangaId = rs.getString("manga_id"),
                        lastCheckedAt = Instant.fromEpochMilliseconds(rs.getLong("last_checked_at")),
                        newChapterCount = rs.getInt("new_chapter_count"),
                        lastError = rs.getString("last_error"),
                    )
                }
                list
            }
        }
    }

    private suspend fun storedCount(mangaId: String): Int = db.withConnection { conn ->
        conn.prepareStatement("SELECT new_chapter_count FROM manga_update_state WHERE manga_id = ?").use { stmt ->
            stmt.setString(1, mangaId)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private suspend fun upsertState(mangaId: String, newChapterCount: Int, error: String?) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO manga_update_state (manga_id, last_checked_at, new_chapter_count, last_error)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(manga_id) DO UPDATE SET
                    last_checked_at = excluded.last_checked_at,
                    new_chapter_count = excluded.new_chapter_count,
                    last_error = excluded.last_error
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, mangaId)
                stmt.setLong(2, Clock.System.now().toEpochMilliseconds())
                stmt.setInt(3, newChapterCount)
                stmt.setString(4, error)
                stmt.executeUpdate()
            }
        }
        db.bumpMangaData()
    }

    private suspend fun readCursor(): Int = db.withConnection { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT cursor FROM manga_update_cursor WHERE id = 1").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    private suspend fun writeCursor(value: Int) {
        db.withConnection { conn ->
            conn.prepareStatement("INSERT OR REPLACE INTO manga_update_cursor (id, cursor) VALUES (1, ?)").use {
                it.setInt(1, value)
                it.executeUpdate()
            }
        }
    }

    private fun overBudget(startedAtNanos: Long): Boolean =
        System.nanoTime() - startedAtNanos >= RUN_BUDGET_MS * 1_000_000

    private companion object {
        const val MAX_MANGA_PER_RUN = 20
        const val MAX_SOURCES_IN_PARALLEL = 2
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val SOURCE_FAILURE_LIMIT = 2
        const val RUN_BUDGET_MS = 10 * 60_000L
        const val SOURCE_SNAPSHOT_TIMEOUT_MS = 30_000L
    }
}
