package com.folio.reader.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.folio.reader.ui.theme.FolioTokens

/**
 * Wires routes to screen composables (§3.2 FOLIO_IMPLEMENTATION_SPEC).
 *
 * Phase 1 step 1: wraps the *existing* screen composables unchanged. Routes are
 * registered here; [FolioNavShell] hosts this inside the bottom-bar scaffold.
 * Callbacks that need the activity (pickers, dialogs) come in via [callbacks].
 */
@Composable
fun FolioNavHost(
    navController: NavHostController,
    navModel: FolioNavModel,
    callbacks: FolioNavCallbacks,
    modifier: Modifier = Modifier
) {
    val graph = navModel.graph

    NavHost(
        navController = navController,
        startDestination = FolioRoutes.LIBRARY,
        modifier = modifier,
        // Pushes travel: a fade plus a short horizontal slide, so opening a book
        // reads as moving *into* it and back reads as returning. The library
        // default (~700ms with delays) reads as lag; these are 220/180 with the
        // slide capped at 4% of the width, which is felt rather than watched.
        enterTransition = {
            fadeIn(tween(FolioTokens.motionStandard.toInt())) +
                slideInHorizontally(tween(FolioTokens.motionStandard.toInt())) { it / 24 }
        },
        exitTransition = {
            fadeOut(tween(FolioTokens.motionFast.toInt() + 60)) +
                slideOutHorizontally(tween(FolioTokens.motionStandard.toInt())) { -it / 40 }
        },
        popEnterTransition = {
            fadeIn(tween(FolioTokens.motionStandard.toInt())) +
                slideInHorizontally(tween(FolioTokens.motionStandard.toInt())) { -it / 40 }
        },
        popExitTransition = {
            fadeOut(tween(FolioTokens.motionFast.toInt() + 60)) +
                slideOutHorizontally(tween(FolioTokens.motionStandard.toInt())) { it / 24 }
        }
    ) {
        // ── Bottom-bar destinations ─────────────────────────────────────────
        composable(FolioRoutes.HOME) {
            navModel.homeContent()
        }

        composable(FolioRoutes.LIBRARY) {
            navModel.libraryContent(
                onOpenReader = { bookId -> navController.navigate(FolioDestination.reader(bookId)) },
                onOpenBookDetail = { bookId -> navController.navigate(FolioDestination.bookDetail(bookId)) },
                onOpenSearch = { navController.navigate(FolioRoutes.SEARCH) },
                onOpenSettings = { navController.goToTopLevelTab(FolioRoutes.MORE) },
                onOpenTags = { navController.navigate(FolioRoutes.TAGS) },
                onOpenQuotes = { navController.navigate(FolioRoutes.QUOTES) },
                onOpenRevisit = { navController.navigate(FolioRoutes.REVISIT) },
                onOpenMangaBrowse = { navController.navigate(FolioRoutes.MANGA_BROWSE) },
                onOpenMangaExtensions = { navController.navigate(FolioRoutes.EXTENSIONS) },
                onOpenMangaHistory = { navController.navigate(FolioRoutes.MANGA_HISTORY) },
                onOpenMangaDetail = { mangaId -> navController.navigate(FolioDestination.mangaDetail(mangaId)) },
                onOpenMangaDownloads = { navController.navigate(FolioRoutes.MANGA_DOWNLOADS) },
                onOpenMangaSource = { sourceId, query ->
                    navController.navigate(FolioDestination.mangaSourceBrowse(sourceId, query))
                }
            )
        }

        composable(FolioRoutes.STATS) {
            navModel.statsContent(
                onOpenBookDetail = { bookId -> navController.navigate(FolioDestination.bookDetail(bookId)) }
            )
        }

        composable(FolioRoutes.MORE) {
            navModel.moreContent(
                onOpenSettings = { category -> navController.navigate(FolioDestination.settings(category)) },
                onOpenTags = { navController.navigate(FolioRoutes.TAGS) },
                onOpenQuotes = { navController.navigate(FolioRoutes.QUOTES) },
                onOpenRevisit = { navController.navigate(FolioRoutes.REVISIT) },
                onOpenExtensions = { navController.navigate(FolioRoutes.EXTENSIONS) },
                onOpenDownloads = { navController.navigate(FolioRoutes.MANGA_DOWNLOADS) },
                onOpenHistory = { navController.navigate(FolioRoutes.MANGA_HISTORY) }
            )
        }

        // ── Pushed destinations (bar hidden) ────────────────────────────────
        composable(
            route = FolioRoutes.READER,
            arguments = listOf(
                navArgument(FolioNavArgs.BOOK_ID) { type = NavType.StringType },
                navArgument(FolioNavArgs.SPINE) { type = NavType.IntType; defaultValue = -1 }
            ),
            deepLinks = listOf(navDeepLink { uriPattern = "folio://reader/{${FolioNavArgs.BOOK_ID}}" })
        ) { entry ->
            val bookId = entry.arguments?.getString(FolioNavArgs.BOOK_ID) ?: return@composable
            val spine = entry.arguments?.getInt(FolioNavArgs.SPINE)?.takeIf { it >= 0 }
            navModel.readerContent(
                bookId = bookId,
                targetSpineIndex = spine,
                onBack = { navController.popBackStack() },
                onOpenSearch = { navController.navigate(FolioRoutes.SEARCH) },
                onOpenSettings = { navController.goToTopLevelTab(FolioRoutes.MORE) }
            )
        }

        composable(
            route = FolioRoutes.BOOK_DETAIL,
            arguments = listOf(navArgument(FolioNavArgs.BOOK_ID) { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "folio://book/{${FolioNavArgs.BOOK_ID}}" })
        ) { entry ->
            val bookId = entry.arguments?.getString(FolioNavArgs.BOOK_ID) ?: return@composable
            navModel.bookDetailContent(
                bookId = bookId,
                onBack = { navController.popBackStack() },
                onStartReading = { navController.navigate(FolioDestination.reader(bookId)) },
                onOpenTags = { navController.navigate(FolioRoutes.TAGS) }
            )
        }

        composable(FolioRoutes.SEARCH) {
            navModel.searchContent(
                onBack = { navController.popBackStack() },
                onOpenReader = { bookId, spine ->
                    navController.navigate(FolioDestination.reader(bookId, spine))
                }
            )
        }

        composable(
            route = FolioRoutes.SETTINGS,
            arguments = listOf(navArgument(FolioNavArgs.CATEGORY) { type = NavType.StringType })
        ) { entry ->
            val category = entry.arguments?.getString(FolioNavArgs.CATEGORY) ?: "general"
            navModel.settingsContent(
                category = category,
                onBack = { navController.popBackStack() }
            )
        }

        composable(FolioRoutes.TAGS) {
            navModel.tagsContent(
                onBack = { navController.popBackStack() },
                onOpenBookDetail = { bookId -> navController.navigate(FolioDestination.bookDetail(bookId)) },
                onOpenReader = { bookId -> navController.navigate(FolioDestination.reader(bookId)) }
            )
        }

        composable(FolioRoutes.QUOTES) {
            navModel.quotesContent(
                onBack = { navController.popBackStack() },
                onOpenReader = { bookId -> navController.navigate(FolioDestination.reader(bookId)) },
                onOpenMangaDetail = { mangaId -> navController.navigate(FolioDestination.mangaDetail(mangaId)) }
            )
        }

        composable(FolioRoutes.REVISIT) {
            navModel.revisitContent(
                onBack = { navController.popBackStack() },
                onOpenReader = { bookId -> navController.navigate(FolioDestination.reader(bookId)) },
                onOpenMangaDetail = { mangaId -> navController.navigate(FolioDestination.mangaDetail(mangaId)) }
            )
        }

        composable(
            route = FolioRoutes.MANGA_DETAIL,
            arguments = listOf(navArgument(FolioNavArgs.MANGA_ID) { type = NavType.StringType })
        ) { entry ->
            val mangaId = entry.arguments?.getString(FolioNavArgs.MANGA_ID) ?: return@composable
            navModel.mangaDetailContent(
                mangaId = mangaId,
                onBack = { navController.popBackStack() },
                onRead = { chapterId ->
                    navController.navigate(FolioDestination.mangaReader(mangaId, chapterId))
                }
            )
        }

        composable(
            route = FolioRoutes.MANGA_READER,
            arguments = listOf(
                navArgument(FolioNavArgs.MANGA_ID) { type = NavType.StringType },
                navArgument(FolioNavArgs.CHAPTER_ID) { type = NavType.StringType }
            )
        ) { entry ->
            val mangaId = entry.arguments?.getString(FolioNavArgs.MANGA_ID) ?: return@composable
            val chapterId = entry.arguments?.getString(FolioNavArgs.CHAPTER_ID) ?: return@composable
            navModel.mangaReaderContent(
                mangaId = mangaId,
                chapterId = chapterId,
                onBack = { navController.popBackStack() },
                onNextChapter = { next ->
                    navController.navigate(FolioDestination.mangaReader(mangaId, next))
                }
            )
        }

        composable(
            route = FolioRoutes.MANGA_SOURCE_BROWSE,
            arguments = listOf(
                navArgument(FolioNavArgs.SOURCE_ID) { type = NavType.LongType },
                navArgument(FolioNavArgs.QUERY) {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val sourceId = entry.arguments?.getLong(FolioNavArgs.SOURCE_ID) ?: return@composable
            val query = entry.arguments?.getString(FolioNavArgs.QUERY).orEmpty()
            navModel.mangaSourceBrowseContent(
                sourceId = sourceId,
                query = query,
                onBack = { navController.popBackStack() },
                onOpenManga = { mangaId -> navController.navigate(FolioDestination.mangaDetail(mangaId)) }
            )
        }

        composable(FolioRoutes.MANGA_BROWSE) {
            navModel.mangaBrowseContent(
                onBack = { navController.popBackStack() },
                onOpenSource = { sourceId, query ->
                    navController.navigate(FolioDestination.mangaSourceBrowse(sourceId, query))
                },
                onOpenExtensions = { navController.navigate(FolioRoutes.EXTENSIONS) },
                onOpenManga = { mangaId -> navController.navigate(FolioDestination.mangaDetail(mangaId)) }
            )
        }

        composable(FolioRoutes.EXTENSIONS) {
            navModel.extensionsContent(onBack = { navController.popBackStack() })
        }

        composable(FolioRoutes.MANGA_DOWNLOADS) {
            navModel.mangaDownloadsContent(onBack = { navController.popBackStack() })
        }

        composable(FolioRoutes.MANGA_HISTORY) {
            navModel.mangaHistoryContent(
                onBack = { navController.popBackStack() },
                onOpenManga = { mangaId -> navController.navigate(FolioDestination.mangaDetail(mangaId)) }
            )
        }
    }
}
