package com.folio.reader.ml

/**
 * A curated vocabulary of **narrative themes** — the mid-level ideas a reader would use to describe
 * what a book is *about*, one tier finer than [BroadGenre] and one tier coarser than a plot summary.
 *
 * ### Why a fixed vocabulary instead of extracted words
 *
 * The Atlas used to label its regions with class-based TF-IDF (the most *distinctive* words in a
 * cluster). On fiction that surfaces proper nouns — a region reads as "Molly" or "constellations",
 * i.e. one book's character or motif, not a category two books could share. "Distinctive" is the
 * wrong objective; what a reader wants is a *shared, human* concept.
 *
 * So instead of extracting words from the text we **project** each region onto this fixed list of
 * concepts: the same zero-shot embedding trick [GenreClassifier] uses for genres, one level finer.
 * A region is labelled by the theme whose prototype phrases its passages sit nearest to in embedding
 * space. Because the label space is designed, it can never emit a character name — the worst case is
 * a slightly-off theme, not gibberish.
 *
 * Each entry carries:
 * - [displayName] — what the map, legend and sheet show.
 * - [hueId] — a stable palette slot, so a theme can tint consistently if ever needed.
 * - [prototypePhrases] — several natural-language exemplars whose **mean** is the theme's prototype
 *   vector (a single phrase is a weak anchor against a whole-cluster mean; a handful is stable).
 *   Plain prose, because the embedder scores prose, not identifiers.
 */
enum class NarrativeTheme(
    val displayName: String,
    val prototypePhrases: List<String>,
) {
    COMING_OF_AGE(
        "Coming of Age",
        listOf(
            "a young person grows up and comes of age",
            "a coming-of-age story of adolescence and self-discovery",
            "leaving childhood behind and finding one's place in the world",
        ),
    ),
    FOUND_FAMILY(
        "Found Family",
        listOf(
            "a ragtag group of companions becomes a family",
            "strangers bound together into found family and fierce loyalty",
            "misfits who choose each other as kin",
        ),
    ),
    FRIENDSHIP_LOYALTY(
        "Friendship & Loyalty",
        listOf(
            "a story of deep friendship and loyalty between companions",
            "comrades who stand by one another",
            "bonds of trust tested by hardship",
        ),
    ),
    LOVE_AND_ROMANCE(
        "Love & Romance",
        listOf(
            "a romance and a love story between two people",
            "longing, desire and falling in love",
            "a passionate, tender relationship",
        ),
    ),
    FORBIDDEN_LOVE(
        "Forbidden Love",
        listOf(
            "a forbidden love against the rules of society",
            "star-crossed lovers kept apart",
            "a dangerous, secret romance",
        ),
    ),
    GRIEF_AND_LOSS(
        "Grief & Loss",
        listOf(
            "grief, mourning and the loss of a loved one",
            "coping with death and sorrow",
            "the ache of absence and remembrance",
        ),
    ),
    TRAUMA_AND_HEALING(
        "Trauma & Healing",
        listOf(
            "recovering from trauma and slowly healing",
            "wounds of the past and the road to recovery",
            "learning to live again after being broken",
        ),
    ),
    IDENTITY_AND_BELONGING(
        "Identity & Belonging",
        listOf(
            "a search for identity and where one belongs",
            "questions of self, name and belonging",
            "an outsider trying to find their place",
        ),
    ),
    REVENGE(
        "Revenge",
        listOf(
            "a quest for revenge and vengeance",
            "settling a score and repaying a wrong",
            "a vendetta driven by old grievances",
        ),
    ),
    BETRAYAL(
        "Betrayal",
        listOf(
            "a betrayal by someone once trusted",
            "treachery, broken oaths and shifting allegiances",
            "a knife in the back from an ally",
        ),
    ),
    POWER_AND_AMBITION(
        "Power & Ambition",
        listOf(
            "the pursuit of power and ruthless ambition",
            "climbing to the top at any cost",
            "the corrupting hunger for control",
        ),
    ),
    POLITICAL_INTRIGUE(
        "Political Intrigue",
        listOf(
            "court politics, scheming and intrigue",
            "plots, factions and a struggle for the throne",
            "diplomacy, conspiracy and the games of power",
        ),
    ),
    WAR_AND_BATTLE(
        "War & Battle",
        listOf(
            "a great war fought in sweeping battles",
            "soldiers, armies and the front line",
            "the brutality and strategy of warfare",
        ),
    ),
    REBELLION(
        "Rebellion & Uprising",
        listOf(
            "a rebellion and uprising against a tyrant",
            "revolution and the fight against oppression",
            "rising up to overthrow the powerful",
        ),
    ),
    SURVIVAL(
        "Survival",
        listOf(
            "a desperate struggle to survive against the odds",
            "endurance, hardship and staying alive",
            "surviving hostile conditions",
        ),
    ),
    QUEST_AND_JOURNEY(
        "Quest & Journey",
        listOf(
            "an epic quest and a long perilous journey",
            "a band of travellers on a dangerous road",
            "a journey toward a distant goal",
        ),
    ),
    ADVENTURE_AND_EXPLORATION(
        "Adventure & Exploration",
        listOf(
            "daring adventure and the exploration of unknown lands",
            "expeditions into uncharted territory",
            "thrilling escapades and discovery",
        ),
    ),
    MYSTERY_AND_INVESTIGATION(
        "Mystery & Investigation",
        listOf(
            "a mystery unravelled through investigation",
            "clues, suspects and solving a puzzle",
            "a detective piecing together the truth",
        ),
    ),
    CRIME_AND_UNDERWORLD(
        "Crime & the Underworld",
        listOf(
            "the criminal underworld of gangs and heists",
            "outlaws, thieves and organised crime",
            "a life of crime on the wrong side of the law",
        ),
    ),
    JUSTICE_AND_LAW(
        "Justice & the Law",
        listOf(
            "the pursuit of justice and the rule of law",
            "trials, courts and moral judgement",
            "right and wrong weighed in the balance",
        ),
    ),
    CHOSEN_ONE_DESTINY(
        "Destiny & the Chosen One",
        listOf(
            "a chosen one bound by prophecy and destiny",
            "a fated hero who must fulfil an ancient prophecy",
            "an ordinary person marked for a great purpose",
        ),
    ),
    MAGIC_AND_SORCERY(
        "Magic & Sorcery",
        listOf(
            "wizards, spells and the workings of magic",
            "sorcery, enchantment and arcane power",
            "a world shaped by magical forces",
        ),
    ),
    PROGRESSION_AND_POWER_UP(
        "Progression & Power",
        listOf(
            "a hero grows steadily stronger, levelling up their power",
            "cultivation, training and climbing the ranks of strength",
            "gaining skills, stats and abilities on a path to power",
        ),
    ),
    MONSTERS_AND_THE_HUNT(
        "Monsters & the Hunt",
        listOf(
            "hunting monsters and fearsome beasts",
            "creatures stalked and slain",
            "a hunter versus a deadly predator",
        ),
    ),
    APOCALYPSE_AND_END(
        "Apocalypse & the End",
        listOf(
            "the end of the world and the apocalypse",
            "survivors in a post-apocalyptic ruin",
            "civilisation collapsing into catastrophe",
        ),
    ),
    SPACE_AND_FUTURE(
        "Space & the Future",
        listOf(
            "starships, distant planets and travel among the stars",
            "a futuristic society and interstellar exploration",
            "the far future of humanity in space",
        ),
    ),
    TECHNOLOGY_AND_AI(
        "Technology & AI",
        listOf(
            "artificial intelligence and advanced technology",
            "machines, robots and the digital future",
            "the promise and peril of new technology",
        ),
    ),
    COSMIC_DREAD(
        "Cosmic Dread",
        listOf(
            "cosmic horror and creeping supernatural dread",
            "eldritch terror beyond human understanding",
            "an unspeakable presence and mounting fear",
        ),
    ),
    FAITH_AND_DOUBT(
        "Faith & Doubt",
        listOf(
            "faith, religion and spiritual doubt",
            "belief, gods and the search for meaning",
            "wrestling with the divine",
        ),
    ),
    FREEDOM_AND_OPPRESSION(
        "Freedom & Oppression",
        listOf(
            "the fight for freedom against oppression",
            "living under tyranny and control",
            "resistance to an unjust regime",
        ),
    ),
    CLASS_AND_SOCIETY(
        "Class & Society",
        listOf(
            "class divides and the structure of society",
            "wealth, poverty and social standing",
            "the tensions between rich and poor",
        ),
    ),
    FAMILY_AND_GENERATIONS(
        "Family & Generations",
        listOf(
            "a family saga across generations",
            "parents, children and inherited legacies",
            "the ties and burdens of family",
        ),
    ),
    DUTY_AND_SACRIFICE(
        "Duty & Sacrifice",
        listOf(
            "duty, honour and painful sacrifice",
            "giving up everything for a cause",
            "the cost of doing what must be done",
        ),
    ),
    MADNESS_AND_MIND(
        "Madness & the Mind",
        listOf(
            "madness, obsession and a mind unravelling",
            "psychological turmoil and delusion",
            "the fragile boundary of sanity",
        ),
    ),
    MEMORY_AND_TIME(
        "Memory & Time",
        listOf(
            "memory, the past and the passage of time",
            "time and remembrance shaping the present",
            "the weight of what came before",
        ),
    ),
    NATURE_AND_WILD(
        "Nature & the Wild",
        listOf(
            "the wilderness and the untamed natural world",
            "life in the wild among animals and the land",
            "humanity against the forces of nature",
        ),
    ),
    HISTORY_AND_PAST(
        "History & the Past",
        listOf(
            "a history of past events and bygone eras",
            "an account of what happened long ago",
            "the story of an age now gone",
        ),
    ),
    SCIENCE_AND_DISCOVERY(
        "Science & Discovery",
        listOf(
            "scientific discovery and how the world works",
            "experiments, evidence and the natural sciences",
            "understanding nature through science",
        ),
    ),
    PHILOSOPHY_AND_MEANING(
        "Philosophy & Meaning",
        listOf(
            "philosophy, ethics and the meaning of life",
            "big questions about existence and morality",
            "reflection on how one ought to live",
        ),
    ),
    SELF_AND_GROWTH(
        "Self & Growth",
        listOf(
            "self-improvement, habits and personal growth",
            "becoming a better version of oneself",
            "practical wisdom for living well",
        ),
    ),
    BUSINESS_AND_MONEY(
        "Business & Money",
        listOf(
            "business, money and the world of work",
            "economics, markets and enterprise",
            "building wealth and running a company",
        ),
    ),
    A_LIFE_LIVED(
        "A Life Lived",
        listOf(
            "the biography and life story of a real person",
            "a memoir of lived experience and recollection",
            "one person's journey through their life",
        ),
    ),
    ;

    /** Stable colour slot, pinned to the ordinal — reordering the enum is a deliberate recolour. */
    val hueId: Int get() = ordinal
}

/** Lookups over [NarrativeTheme] by name, mirroring [GenreTaxonomy]. */
object ThemeTaxonomy {
    val all: List<NarrativeTheme> = NarrativeTheme.entries.toList()

    fun byDisplayName(name: String?): NarrativeTheme? =
        if (name == null) null else all.firstOrNull { it.displayName == name }
}
