package com.folio.reader.settings

import com.folio.reader.experience.ReadingExperienceData
import com.folio.reader.experience.SoundscapePreferences

/** Companion/audio preferences do not change the native page's CSS or geometry. */
fun ReaderSettings.forReaderContent(): ReaderSettings = copy(
    soundscape = SoundscapePreferences(),
    readingExperience = ReadingExperienceData(),
)
