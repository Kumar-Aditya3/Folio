package com.folio.reader.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.folio.reader.ui.components.FolioSharedElementProvider
import com.folio.reader.ui.components.FolioSharedElementScope
import com.folio.reader.ui.theme.FolioTokens

/**
 * The four bottom-bar destinations — the set the §17 tab dissolve applies to.
 * A switch *between* these is a change of page in place, not travel down a
 * stack, so it dissolves where it stands instead of sliding like a push does.
 */
private val topLevelRoutes = setOf(
    FolioRoutes.HOME,
    FolioRoutes.LIBRARY,
    FolioRoutes.STATS,
    FolioRoutes.MORE,
)

/**
 * True when both the page being left and the page being entered are top-level
 * tabs. A push (library → book detail) keeps the slide; a return (detail →
 * library) keeps the reverse slide; only tab↔tab dissolves in place.
 */
private fun androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.isTabMorph(): Boolean =
    initialState.destination.route in topLevelRoutes &&
        targetState.destination.route in topLevelRoutes

/**
 * Pops the reader — and anything pushed on top of it — back to [route].
 *
 * ### Why the reader's back cannot just pop
 *
 * The reader is reachable from Home *and* from the Library, so "pop the reader" is only
 * half an answer: the destination it pops to is whichever tab was underneath when it was
 * opened, and when that tab is Home the reader's back lands on Home. The reader's report is
 * exactly that — *"a physical back should bring me back to library, not home"* — and it was
 * measured press by press on the device: search → back → reader (correct), reader → back →
 * Home (wrong).
 *
 * ### What this does
 *
 * A route-based pop down to the tab. `popBackStack(route, inclusive = false)` removes every
 * entry above the Library *wherever it sits in the stack*, so one call covers both entry
 * paths: when the reader came from the Library, that is the entry directly beneath it; when
 * it came from Home, the Library is still below it, because the bottom bar navigates with
 * `saveState`/`restoreState` and keeps every tab it has visited. No special case per path.
 *
 * It replaces popping exactly one destination (`popBackStack()`), which is what produced the
 * bug: that pops to *whatever* is underneath, and from Home that is Home.
 *
 * Doing it here rather than in a `BackHandler` on the reader screen is deliberate. The reader
 * is a destination; where a destination's back leads is a property of the graph, and a
 * screen-level handler cannot see the tab beneath it — which is exactly the information the
 * fix needs. The host's own back walk (`MainActivity.onBackWalked`) only reaches
 * `navController.popBackStack()` after the reader's `onBack` has already run, so the two do
 * not fight.
 *
 * Deliberately *not* asserted to succeed: if the Library is somehow not on the stack,
 * popping the reader is still the right fallback, and the return value is not worth a crash.
 */
private fun NavHostController.popToTab(route: String) {
    popBackStack(route, inclusive = false)
}

/**
 * Wires routes to screen composables (§3.2 FOLIO_IMPLEMENTATION_SPEC).
 *
 * Phase 1 step 1: wraps the *existing* screen composables unchanged. Routes are
 * registered here; [FolioNavShell] hosts this inside the bottom-bar scaffold.
 * Callbacks that need the activity (pickers, dialogs) come in via [callbacks].
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun FolioNavHost(
    navController: NavHostController,
    navModel: FolioNavModel,
    callbacks: FolioNavCallbacks,
    modifier: Modifier = Modifier
) {
    val graph = navModel.graph

    SharedTransitionLayout(modifier = modifier) {
        // §17 shared elements: one transition scope spans every destination, so a
        // cover tapped on a shelf is the same object that lands on the detail page.
        FolioSharedElementProvider(sharedTransitionScope = this) {
            NavHost(
                navController = navController,
                // Home is the first of the four bar items and the app's opening
                // surface. It was starting on Library, which put the shelf — and
                // its cover decodes — in the reader's path on every launch and
                // left the Home tab unvisited until tapped.
                startDestination = FolioRoutes.HOME,
                modifier = Modifier.fillMaxSize(),
                // §17 morphing tabs: a switch between the bar's destinations is the same
                // page changing its mind, not travel down a stack, so it dissolves in place
                // while a push keeps its travel — a fade plus a short horizontal slide, so
                // opening a book reads as moving *into* it and back as returning.
                //
                // A tab↔tab switch deliberately does *not* slide or scale. Scaling a whole
                // page re-rasterises every cover and glyph in it for the duration, so the
                // page goes soft and then sharpens; the library's own shelf swap does the
                // same and was the reported glitch. The bar's selected segment already
                // springs toward the new tab, which carries the direction a slide would.
                // The library default (~700ms with delays) reads as lag; these are 220/180
                // with the slide capped at 4% of the width.
                enterTransition = {
                    val slide = if (isTabMorph()) EnterTransition.None else
                        slideInHorizontally(tween(FolioTokens.motionStandard.toInt())) { it / 24 }
                    fadeIn(tween(FolioTokens.motionStandard.toInt())) + slide
                },
                exitTransition = {
                    val slide = if (isTabMorph()) ExitTransition.None else
                        slideOutHorizontally(tween(FolioTokens.motionStandard.toInt())) { -it / 40 }
                    fadeOut(tween(FolioTokens.motionFast.toInt() + 60)) + slide
                },
                popEnterTransition = {
                    val slide = if (isTabMorph()) EnterTransition.None else
                        slideInHorizontally(tween(FolioTokens.motionStandard.toInt())) { -it / 40 }
                    fadeIn(tween(FolioTokens.motionStandard.toInt())) + slide
                },
                popExitTransition = {
                    val slide = if (isTabMorph()) ExitTransition.None else
                        slideOutHorizontally(tween(FolioTokens.motionStandard.toInt())) { it / 24 }
                    fadeOut(tween(FolioTokens.motionFast.toInt() + 60)) + slide
                }
            ) {
                // ── Bottom-bar destinations ─────────────────────────────────────────
                composable(FolioRoutes.HOME) {
                    FolioSharedElementScope(this) {
                        navModel.homeContent()
                    }
                }

                composable(FolioRoutes.LIBRARY) {
                    FolioSharedElementScope(this) {
                        navModel.libraryContent(
                            onOpenReader = { bookId -> navController.navigate(FolioDestination.reader(bookId)) },
                            onOpenReaderAt = { bookId, spine, frac ->
                                navController.navigate(
                                    FolioDestination.reader(bookId, spine, frac?.let { (it * 1000).toInt() })
                                )
                            },
                            onOpenDocument = { documentId ->
                                navController.navigate(FolioDestination.documentReader(documentId))
                            },
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
                }

                composable(FolioRoutes.STATS) {
                    FolioSharedElementScope(this) {
                        navModel.statsContent(
                            onOpenBookDetail = { bookId -> navController.navigate(FolioDestination.bookDetail(bookId)) }
                        )
                    }
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
                        navArgument(FolioNavArgs.SPINE) { type = NavType.IntType; defaultValue = -1 },
                        navArgument(FolioNavArgs.FRAC) { type = NavType.IntType; defaultValue = -1 }
                    ),
                    deepLinks = listOf(navDeepLink { uriPattern = "folio://reader/{${FolioNavArgs.BOOK_ID}}" })
                ) { entry ->
                    val bookId = entry.arguments?.getString(FolioNavArgs.BOOK_ID) ?: return@composable
                    val spine = entry.arguments?.getInt(FolioNavArgs.SPINE)?.takeIf { it >= 0 }
                    // Per-mille back to a fraction; -1 (the default) means "no intra-chapter target".
                    val targetFraction = entry.arguments?.getInt(FolioNavArgs.FRAC)
                        ?.takeIf { it in 0..1000 }?.let { it / 1000f }
                    // §17: the reader is a morph destination too. A cover tapped on a
                    // shelf lands here as the plate the first chapter starts under, so
                    // opening a book reads as the cover flying to where you will read
                    // it rather than a page replacing a page. Gated per-platform by the
                    // caller — see ReaderContent's landing flag.
                    FolioSharedElementScope(this) {
                        navModel.readerContent(
                            bookId = bookId,
                            targetSpineIndex = spine,
                            targetFraction = targetFraction,
                            // Back from the reader lands on the **Library**, whichever tab it
                            // was opened from — including Home. See [popToTab].
                            onBack = { navController.popToTab(FolioRoutes.LIBRARY) },
                            // Search from the reader goes to its own destination so search's
                            // own back can return here instead of to the Library.
                            onOpenSearch = { navController.navigate(FolioRoutes.SEARCH_FROM_READER) },
                            onOpenSettings = { navController.goToTopLevelTab(FolioRoutes.MORE) }
                        )
                    }
                }

                composable(
                    route = FolioRoutes.DOCUMENT_READER,
                    arguments = listOf(
                        navArgument(FolioNavArgs.DOCUMENT_ID) { type = NavType.StringType }
                    )
                ) { entry ->
                    val documentId =
                        entry.arguments?.getString(FolioNavArgs.DOCUMENT_ID)
                            ?: return@composable
                    FolioSharedElementScope(this) {
                        navModel.documentReaderContent(
                            documentId = documentId,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                composable(
                    route = FolioRoutes.BOOK_DETAIL,
                    arguments = listOf(navArgument(FolioNavArgs.BOOK_ID) { type = NavType.StringType }),
                    deepLinks = listOf(navDeepLink { uriPattern = "folio://book/{${FolioNavArgs.BOOK_ID}}" })
                ) { entry ->
                    val bookId = entry.arguments?.getString(FolioNavArgs.BOOK_ID) ?: return@composable
                    FolioSharedElementScope(this) {
                        navModel.bookDetailContent(
                            bookId = bookId,
                            onBack = { navController.popBackStack() },
                            onStartReading = { navController.navigate(FolioDestination.reader(bookId)) },
                            onOpenTags = { navController.navigate(FolioRoutes.TAGS) }
                        )
                    }
                }

                composable(FolioRoutes.SEARCH) {
                    navModel.searchContent(
                        onBack = { navController.popBackStack() },
                        onOpenReader = { bookId, spine, frac ->
                            navController.navigate(
                                FolioDestination.reader(bookId, spine, frac?.let { (it * 1000).toInt() })
                            )
                        }
                    )
                }

                // Search opened from the reader chrome. Same screen, same state holder — the
                // only difference is what its back means, and that is a property of the stack
                // rather than of the screen. Because this destination is pushed *on top of*
                // the reader, its back is an ordinary pop and returns to the reader; the
                // Library's search ([FolioRoutes.SEARCH]) pops to the Library because that is
                // what sits under it. Splitting the route is what lets both be right without
                // a screen-level handler guessing which one it is.
                //
                // Opening a result pushes the reader again rather than reusing the one below,
                // so backing out of it returns here — to the results the reader was reading —
                // instead of dropping them.
                composable(FolioRoutes.SEARCH_FROM_READER) {
                    navModel.searchContent(
                        onBack = { navController.popBackStack() },
                        onOpenReader = { bookId, spine, frac ->
                            navController.navigate(
                                FolioDestination.reader(bookId, spine, frac?.let { (it * 1000).toInt() })
                            )
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
                    FolioSharedElementScope(this) {
                        navModel.mangaDetailContent(
                            mangaId = mangaId,
                            onBack = { navController.popBackStack() },
                            onRead = { chapterId ->
                                navController.navigate(FolioDestination.mangaReader(mangaId, chapterId))
                            }
                        )
                    }
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
                    FolioSharedElementScope(this) {
                        navModel.mangaReaderContent(
                            mangaId = mangaId,
                            chapterId = chapterId,
                            onBack = { navController.popBackStack() },
                            onNextChapter = { next ->
                                navController.navigate(FolioDestination.mangaReader(mangaId, next))
                            }
                        )
                    }
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
                    FolioSharedElementScope(this) {
                        navModel.mangaHistoryContent(
                            onBack = { navController.popBackStack() },
                            onOpenManga = { mangaId -> navController.navigate(FolioDestination.mangaDetail(mangaId)) }
                        )
                    }
                }
            }
        }
    }
}
