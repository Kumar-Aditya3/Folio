# Android App Issues - Fix Tracker

## Status: 2026-08-26 06:56 UTC

### ✅ FIXED
1. **Settings screen crash** - RESOLVED ✓
   - Root cause: Nested LazyColumn in Column with fillMaxSize() creates infinite height constraints
   - Solution: Changed outer LazyColumn to weight(1f) + fillMaxWidth(), converted all panel LazyColumns to plain Columns
   - Status: Verified working, no crash, Settings screen opens successfully

2. **Database connection pool exhaustion / EPUB infinite loading** - RESOLVED ✓
   - Root cause: Inconsistent use of connectionMutex.withLock across database read methods
   - Multiple threads tried to use single cached SQLite connection simultaneously
   - SQLiteConnectionPool timeout: "unable to grant connection for 4+ seconds"
   - Solution: Added connectionMutex.withLock to all read methods:
     * getBookByEpubHash, getBookByIsbn
     * getPosition, getLatestPositionAcrossDevices, getAllPositionsForBook
     * getHighlight, getHighlightsForBook, getDeletedHighlights
     * countHighlightsForChapter
   - Status: Fix applied, rebuilt, and deployed (awaiting user verification)

3. **Cover page text centering** - Fixed earlier
   - Root cause: ParagraphStyle in AnnotatedString overrides Text's textAlign
   - Solution: Clear paragraphStyles when building cover page AnnotatedString

### 🔴 TODO - CRITICAL
4. **Settings are read-only / cannot be changed**
   - User report: "the setting opens but i cant actually change them"
   - Current: Settings panels only show static text labels
   - Need: Add actual interactive controls (sliders, switches, dropdowns)
   - All 7 panels are placeholder implementations with no interactive widgets

5. **Text alignment setting not working / not centered**
   - User mentioned: "the text not being centred and occupying..."
   - May be part of general "settings don't work" issue
   - Need to:
     a) Implement interactive alignment controls in settings
     b) Verify alignment actually applies to reader content
     c) Check if text is properly centered when alignment = CENTER

## Investigation Complete
✓ Diagnosed EPUB loading issue → database connection pool exhaustion
✓ Fixed by adding consistent mutex locking to all read operations
✓ Rebuilt and deployed with fix

## Next Steps
1. **Verify EPUB loading fix** - User should test opening a book to confirm no infinite spinner
2. **Implement interactive settings controls** - Replace static labels with actual UI controls
3. **Fix text alignment** - Ensure alignment setting is saved and applied to reader

## Files Modified
- `shared/src/composeUi/kotlin/com/folio/reader/ui/settings/SettingsScreen.kt` (removed nested LazyColumns)
- `shared/src/commonJvm/kotlin/com/folio/reader/database/Database.kt` (added connectionMutex to 9 methods)
