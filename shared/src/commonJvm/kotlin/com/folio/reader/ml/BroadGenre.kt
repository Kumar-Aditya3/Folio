package com.folio.reader.ml

/**
 * A small, fixed vocabulary of **broad** genres — the top-level shelves a reader would recognise,
 * fiction and non-fiction — used to name the Atlas's emergent communities.
 *
 * ### Why broad, and why fixed
 *
 * The Atlas needs a *stable* label space: the communities are discovered from the data and change
 * as the library grows, so the names hung on them must not. A fixed, coarse taxonomy (≈20 shelves)
 * is what lets a community read as "Science Fiction" rather than the c-TF-IDF bigram salad it used
 * to. It is deliberately *not* an exhaustive BISAC tree — a reader does not want 3 000 sub-codes on
 * a galaxy map, and the zero-shot classifier cannot tell fine sub-genres apart from a whole-book
 * mean vector anyway.
 *
 * Each entry carries three things the rest of the feature keys on:
 * - [displayName] — what the map and the sheet show.
 * - [labelText] — the short phrase embedded once per model for the zero-shot fallback. It is a
 *   natural phrase rather than the enum name because MiniLM scores prose, not identifiers.
 * - [hueId] — a stable colour slot so a genre keeps the same colour across sessions (the renderer
 *   maps it through a fixed palette). It is the ordinal, pinned here so reordering the enum is a
 *   visible, deliberate act rather than a silent recolour of the whole galaxy.
 * - [bisacPrefixes] / [keywords] — the metadata-first mapping rules ([GenreTaxonomy.canonicalize]).
 */
enum class BroadGenre(
    val displayName: String,
    val labelText: String,
    val bisacPrefixes: List<String>,
    val keywords: List<String>,
) {
    SCIENCE_FICTION(
        "Science Fiction",
        "science fiction and speculative fiction",
        listOf("FIC028", "FIC050"),
        listOf("science fiction", "sci-fi", "sci fi", "dystopian", "space opera", "cyberpunk", "speculative"),
    ),
    FANTASY(
        "Fantasy",
        "fantasy, magic and myth",
        listOf("FIC009", "FIC010"),
        listOf("fantasy", "epic fantasy", "sword and sorcery", "magic", "mythology", "dragons"),
    ),
    MYSTERY_CRIME(
        "Mystery & Crime",
        "mystery, crime and thriller",
        listOf("FIC022", "FIC031", "FIC030", "FIC050"),
        listOf("mystery", "detective", "crime", "thriller", "suspense", "noir", "whodunit"),
    ),
    ROMANCE(
        "Romance",
        "romance and love stories",
        listOf("FIC027"),
        listOf("romance", "love story", "romantic"),
    ),
    HORROR(
        "Horror",
        "horror and the supernatural",
        listOf("FIC015"),
        listOf("horror", "ghost", "haunting", "supernatural", "occult"),
    ),
    HISTORICAL_FICTION(
        "Historical Fiction",
        "historical fiction set in the past",
        listOf("FIC014"),
        listOf("historical fiction"),
    ),
    LITERARY_FICTION(
        "Literary Fiction",
        "literary fiction and classic literature",
        listOf("FIC019", "FIC004", "FIC000", "FIC045"),
        listOf("literary", "classics", "literature", "fiction general", "short stories"),
    ),
    ADVENTURE(
        "Adventure",
        "action and adventure",
        listOf("FIC002"),
        listOf("adventure", "action"),
    ),
    YOUNG_ADULT(
        "Young Adult",
        "young adult and children's books",
        listOf("JUV", "JNF", "YAF", "YAN"),
        listOf("young adult", "juvenile", "children", "middle grade"),
    ),
    COMICS(
        "Comics & Graphic Novels",
        "comics, manga and graphic novels",
        listOf("CGN"),
        listOf("comic", "graphic novel", "manga", "graphic novels"),
    ),
    POETRY(
        "Poetry",
        "poetry and verse",
        listOf("POE"),
        listOf("poetry", "poems", "verse"),
    ),
    DRAMA(
        "Drama & Plays",
        "drama, plays and theatre",
        listOf("DRA"),
        listOf("drama", "plays", "theatre", "theater", "playwriting"),
    ),
    BIOGRAPHY(
        "Biography & Memoir",
        "biography and memoir",
        listOf("BIO"),
        listOf("biography", "memoir", "autobiography", "diaries", "letters"),
    ),
    HISTORY(
        "History",
        "history and historical events",
        listOf("HIS"),
        listOf("history", "historical", "ancient", "medieval", "world war"),
    ),
    SCIENCE_NATURE(
        "Science & Nature",
        "science, nature and mathematics",
        listOf("SCI", "NAT", "MAT"),
        listOf("physics", "biology", "chemistry", "astronomy", "nature", "mathematics", "natural history", "evolution"),
    ),
    TECHNOLOGY(
        "Technology & Computing",
        "technology, computing and engineering",
        listOf("COM", "TEC"),
        listOf("computer", "programming", "software", "technology", "engineering", "artificial intelligence"),
    ),
    BUSINESS(
        "Business & Economics",
        "business, economics and finance",
        listOf("BUS"),
        listOf("business", "economics", "management", "finance", "marketing", "entrepreneur", "investing"),
    ),
    SELF_HELP(
        "Self-Help & Wellness",
        "self-help, health and wellness",
        listOf("SEL", "HEA", "PSY", "FAM"),
        listOf("self-help", "self help", "wellness", "health", "psychology", "mindfulness", "personal growth"),
    ),
    PHILOSOPHY_RELIGION(
        "Philosophy & Religion",
        "philosophy, religion and spirituality",
        listOf("PHI", "REL", "OCC", "BODY"),
        listOf("philosophy", "religion", "spirituality", "theology", "ethics", "meditation"),
    ),
    SOCIETY_POLITICS(
        "Society & Politics",
        "politics, society and current affairs",
        listOf("POL", "SOC", "LAW", "EDU"),
        listOf("political", "politics", "society", "social science", "sociology", "current affairs", "law"),
    ),
    TRAVEL_FOOD(
        "Travel & Food",
        "travel, cooking and food",
        listOf("TRV", "CKB"),
        listOf("travel", "cooking", "cookbook", "food", "cuisine", "recipes"),
    ),
    ;

    /** Stable colour slot for the renderer's genre palette; pinned to the ordinal. */
    val hueId: Int get() = ordinal

    /**
     * Several natural-language exemplar phrases whose **mean** is the genre's prototype vector.
     *
     * A single [labelText] is a weak anchor: one short string sits in a narrow, model-specific band
     * against a whole-book mean, so the argmax is close to noise. Averaging a handful of concrete
     * exemplars (the way a reader would describe the shelf) produces a far more stable prototype —
     * the calibration + margin gating in [GenreClassifier] then turns it into a real ranking. Kept
     * as plain prose (not enum identifiers) because the models score prose, not tokens. [labelText]
     * is always the first phrase so the prototype stays anchored to the canonical label.
     */
    val prototypePhrases: List<String>
        get() = buildList {
            add(labelText)
            addAll(
                when (this@BroadGenre) {
                    SCIENCE_FICTION -> listOf(
                        "a space opera with starships, aliens and distant planets",
                        "a dystopian future ruled by technology",
                        "time travel and artificial intelligence",
                    )
                    FANTASY -> listOf(
                        "a quest through a kingdom of wizards and dragons",
                        "an epic tale of magic, myth and sorcery",
                        "elves, spells and an ancient prophecy",
                    )
                    MYSTERY_CRIME -> listOf(
                        "a detective investigates a murder",
                        "a suspenseful crime thriller full of clues and suspects",
                        "a noir whodunit with a twist ending",
                    )
                    ROMANCE -> listOf(
                        "a love story between two people",
                        "a romantic tale of longing and heartbreak",
                        "falling in love against the odds",
                    )
                    HORROR -> listOf(
                        "a terrifying haunted house and vengeful ghosts",
                        "supernatural dread and the occult",
                        "a monster stalks its victims",
                    )
                    HISTORICAL_FICTION -> listOf(
                        "a novel set in a vividly recreated past era",
                        "characters living through a historical period",
                        "a wartime story set generations ago",
                    )
                    LITERARY_FICTION -> listOf(
                        "a literary novel about the human condition",
                        "a character study of ordinary life",
                        "a classic work of serious fiction",
                    )
                    ADVENTURE -> listOf(
                        "a daring expedition and a perilous journey",
                        "action, danger and survival in the wild",
                        "a swashbuckling tale of exploration",
                    )
                    YOUNG_ADULT -> listOf(
                        "a coming-of-age story for teenagers",
                        "a young adult novel about growing up",
                        "a children's adventure for younger readers",
                    )
                    COMICS -> listOf(
                        "a graphic novel told in illustrated panels",
                        "a comic book of superheroes",
                        "manga art and sequential storytelling",
                    )
                    POETRY -> listOf(
                        "a collection of poems and verse",
                        "lyric poetry and stanzas",
                    )
                    DRAMA -> listOf(
                        "a stage play written for the theatre",
                        "dramatic dialogue and acts and scenes",
                    )
                    BIOGRAPHY -> listOf(
                        "the life story of a real person",
                        "a memoir of lived experience",
                        "an autobiography and personal recollection",
                    )
                    HISTORY -> listOf(
                        "an account of past historical events",
                        "the history of a war, empire or civilisation",
                        "a study of the ancient and medieval world",
                    )
                    SCIENCE_NATURE -> listOf(
                        "the science of physics, biology and the natural world",
                        "astronomy, chemistry and evolution",
                        "a study of nature and mathematics",
                    )
                    TECHNOLOGY -> listOf(
                        "computer programming and software engineering",
                        "artificial intelligence and technology",
                        "how computers and machines work",
                    )
                    BUSINESS -> listOf(
                        "business strategy, management and economics",
                        "finance, marketing and entrepreneurship",
                        "investing and the economy",
                    )
                    SELF_HELP -> listOf(
                        "self-improvement, habits and personal growth",
                        "health, wellness and mindfulness",
                        "practical psychology for a better life",
                    )
                    PHILOSOPHY_RELIGION -> listOf(
                        "philosophy, ethics and the meaning of life",
                        "religion, spirituality and theology",
                        "meditation and questions of belief",
                    )
                    SOCIETY_POLITICS -> listOf(
                        "politics, government and public policy",
                        "society, sociology and current affairs",
                        "law and social justice",
                    )
                    TRAVEL_FOOD -> listOf(
                        "a travel guide to places around the world",
                        "cooking, recipes and cuisine",
                        "food, restaurants and journeys abroad",
                    )
                }
            )
        }
}

/**
 * The metadata-first half of genre resolution: maps an EPUB's `<dc:subject>` strings (plain words
 * or BISAC codes) onto the [BroadGenre] taxonomy, deterministically and with no model.
 *
 * A publisher's own subjects are the best signal there is when they exist, which is why they take
 * precedence over the zero-shot fallback ([GenreClassifier]). The mapping is intentionally
 * conservative — a subject that matches nothing yields null so the caller can fall back rather than
 * force a wrong shelf.
 */
object GenreTaxonomy {

    val all: List<BroadGenre> = BroadGenre.entries.toList()

    fun byName(name: String?): BroadGenre? =
        if (name == null) null else all.firstOrNull { it.name == name }

    /** Looks a genre up by its human-facing [BroadGenre.displayName] (what the map/legend show). */
    fun byDisplayName(name: String?): BroadGenre? =
        if (name == null) null else all.firstOrNull { it.displayName == name }

    /**
     * Best broad genre for a set of subject strings, or null when none map.
     *
     * Each subject votes for at most one genre — a BISAC code prefix match (strong) if the string
     * is a code, otherwise its longest matching keyword (so "science fiction" resolves to
     * [BroadGenre.SCIENCE_FICTION] rather than [BroadGenre.SCIENCE_NATURE], because the more
     * specific keyword is longer). Votes are tallied and the heaviest wins, ties broken toward the
     * earlier taxonomy entry so the result is stable.
     */
    fun canonicalize(subjects: List<String>): BroadGenre? {
        if (subjects.isEmpty()) return null
        val votes = HashMap<BroadGenre, Double>()
        for (raw in subjects) {
            val (genre, strength) = matchSubject(raw) ?: continue
            votes[genre] = (votes[genre] ?: 0.0) + strength
        }
        if (votes.isEmpty()) return null
        return votes.entries
            .sortedWith(compareByDescending<Map.Entry<BroadGenre, Double>> { it.value }.thenBy { it.key.ordinal })
            .first().key
    }

    /** The single best (genre, strength) for one subject string, or null. */
    private fun matchSubject(raw: String): Pair<BroadGenre, Double>? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        // BISAC / code match first — but only when the string actually has a code *shape* (a short
        // letter prefix followed by digits, e.g. "FIC028000"). Without the digit requirement a plain
        // subject like "Science Fiction" would match the bare "SCI" prefix of Science & Nature, which
        // is exactly the wrong shelf. A leading code token is extracted so "FIC028000" resolves even
        // when the rest of the string is prose.
        val alnum = trimmed.uppercase().filter { it.isLetterOrDigit() }
        val codeToken = CODE_SHAPE.find(alnum)?.value
        if (codeToken != null) {
            var best: BroadGenre? = null
            var bestLen = 0
            for (g in all) for (p in g.bisacPrefixes) {
                if (codeToken.startsWith(p) && p.length > bestLen) { bestLen = p.length; best = g }
            }
            if (best != null) return best to (10.0 + bestLen)
        }

        // Keyword match — longest matching keyword wins (most specific).
        val lower = trimmed.lowercase()
        var best: BroadGenre? = null
        var bestLen = 0
        for (g in all) for (kw in g.keywords) {
            if (kw.length > bestLen && lower.contains(kw)) { bestLen = kw.length; best = g }
        }
        return if (best != null) best to bestLen.toDouble() else null
    }

    /** A BISAC-shaped code token: 2–4 letters then at least three digits (e.g. FIC028000). */
    private val CODE_SHAPE = Regex("^[A-Z]{2,4}[0-9]{3,}")

}
