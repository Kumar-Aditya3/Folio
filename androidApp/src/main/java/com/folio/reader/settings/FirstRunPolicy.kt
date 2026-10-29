package com.folio.reader.settings

enum class FirstRunStep(val title: String, val introduction: String) {
    Welcome("Welcome to Folio", "Make a little room for reading. A few choices will shape your library, your page and the features you use."),
    Appearance("Make yourself at home", "Choose the light you like to read in. Your app and reading page will use the same theme family."),
    Echoes("Find echoes between books", "Discover related passages and search by meaning, privately on your device. This optional feature uses a downloadable model."),
    Narration("Give your books a voice", "Choose an in-reader text-to-speech model and voice. Download a pack now, or add one later in Settings → Narration."),
    Notifications("Choose your nudges", "Decide whether Folio may send reminders and chapter updates. Android will ask for permission only when you tap Allow."),
    Ready("Your reading room is ready", "Your choices are saved. Every feature and model can be changed later in Settings.");

    fun next() = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
    fun previous() = entries[(ordinal - 1).coerceAtLeast(0)]
}

/** Updates migrate quietly; an interrupted first-install setup resumes even after an update. */
internal fun needsFirstRunSetup(completed: Boolean, legacyIntroduced: Boolean,
    started: Boolean, freshInstallation: Boolean): Boolean =
    !completed && !legacyIntroduced && (started || freshInstallation)
