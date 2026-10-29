package com.folio.reader.narration

import kotlinx.serialization.Serializable

@Serializable
data class NarrationOptions(
    val paragraphPhrasing: Boolean = true,
    val structuralPauses: Boolean = true,
    val audioCache: Boolean = false,
    val cacheMegabytes: Int = 128,
    val prepareWhileCharging: Boolean = false,
)

@Serializable
data class PronunciationRule(val written: String, val spoken: String)

data class NarrationStorageState(val bytes: Long = 0, val preparing: Boolean = false,
    val message: String? = null, val previewing: Boolean = false)

data class PhononVoice(val id: String, val label: String, val description: String, val sha256: String)
object PhononVoices {
    const val revision = "0ee17205f254b5abc43b266c8a0f215565c352f7"
    val all = listOf(
        PhononVoice("vjFyp2BcCyTlUmYb", "Everly", "Relaxed · American", "958ea94da14f46dd48c7f64adac11ed2ad2aa45f7ccd165dfebab5272664402e"),
        PhononVoice("bXwrkHhURL1VFJdG", "Hadley", "Steady · American", "a879938aa9a2df0a8220bdadef88137929f71e887bd91e7db205bd5fa11661d2"),
        PhononVoice("d9XCyhjo4JhkHF1d", "Miles", "Dynamic · American", "eab20e4f159b48cfbc01b1885804e24f681f80e7a64f909182763a4e3361bee8"),
        PhononVoice("YyKcxPG7iIgS3jsK", "Hugh", "Soft · British", "3169d74b47a54d1eb15e4c243f56393abe9fe90c7e1c308ecf96ee67618b152a"),
        PhononVoice("JOW0vchyswkQUMDS", "Spencer", "Patient · British", "cc9ac1107b0ffce282374a19beb7bfa95847724e6b7afbac991b4ae4277d5e6d"),
        PhononVoice("gfP44RlHwr2kEwk7", "Bryony", "Rich · British", "bcc207a73b7885e45b49ab633d3377a10158292e3a7cef9cd6764ad107f1cb9b"),
    )
    const val audition = "The room was quiet. Outside, the rain had stopped, and the first light touched the windows. “Are you coming?” she asked. He closed the book, considered the question, and smiled. “In a moment.”"
}
