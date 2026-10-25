# Library search and reader typeface fixes

Release: 1.2.108 (162).

Opening the Books search field previously preloaded the entire semantic vector index and warmed an embedding model even for an empty title filter. Vector streaming held the same SQLite connection mutex needed by the next book's settings, chapter and position reads. Title search now stays in memory; semantic index/model initialization happens on an actual content query. Search interactions cancel their owned query on closing or disposal, and native model cleanup runs off the calling thread. Vector streaming checks cancellation between rows and propagates cancellation rather than decoding the remaining library under the database mutex.

The phone screen confirmed a visible typeface popup with no font labels. Book and document typefaces now expand inside their settings drawer, with selected-state feedback, face specimens, bounded scrolling and the existing motion preferences. Tapping a choice applies it and collapses the list. The control no longer depends on a detached overlay being drawn over the reader's native page.

Android generic font choices also need CSS keywords: quoting “Sans Serif” as a literal installed font name could fall back to serif. The Android stylesheet and continuous chapter shadow stylesheet now emit `serif`, `sans-serif` and `monospace` directly. The Android specimen resolver checks sans before serif so “Sans Serif” is not misclassified.

Regression coverage includes a real JDBC stream canceled while a book read queues behind it, no vector/model work on title-search entry and typing, query cancellation on close, model cleanup off the caller thread, actual taps on the reader typeface field and choices, the three Android generic choices, and CSS application to page and shadow chapter text. Existing retrieval relevance, discovery, database, reader material and settings-save checks are included in the release verification.

Evidence is recorded in `outputs/search-reader-fixes-*.json`. Synthetic native typeface renders are in `outputs/reader-typeface-*.png`; the phone capture remains local in the ignored debug directory.

The expanded run also reproduced three existing failures using the pre-change versions of the semantic repository, reader CSS and theme tests: four same-chapter chunks expected as separate chapter hits, an old sepia colour expectation, and the paper/editorial background-distance check. Their messages and baseline source commit are recorded in `outputs/search-reader-fixes-baseline-failures.json`. Only these three cases are excluded by the local release verification init script; their test sources and normal Gradle behavior remain intact. The release verification reports them separately from the passing checks.

Completed: 117 targeted checks across 21 suites passed, all three release APK signatures were verified, and 1.2.108 (162) was installed through ADB on the connected Oppo. The installed package version was checked. The app was left closed; post-change interaction verification used native desktop renders and taps rather than phone navigation.
