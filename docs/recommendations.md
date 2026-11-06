# Home recommendations

Home uses deterministic metadata rules. It does not pick random titles or treat a
source's latest uploads as personalized recommendations.

## Books

The most recently opened finished book anchors “Because you finished.” Only unread,
non-excluded books qualify. Forward volumes in the same series come first in volume
order, followed by other works by the same author. Author matching ignores casing,
punctuation and placeholder names. Ties use title and ID; duplicate EPUBs are removed.
A single useful match is enough to show the shelf.

## Manga

The five most recent eligible library reads supply creators and genres. Up to three
recently read sources supply browse candidates; selected-extension mode can also use
sources the reader explicitly chose. Sources without a latest feed use their popular
feed. Selected sources respect saved languages, falling back to recent-read languages.

Candidate details establish the match. A shared author or artist receives a strong
boost alongside genre overlap. Genre matches require two content genres, or one specific genre; a broad
label such as “Action” alone is insufficient. Demographics and formats do not establish
similarity. Recent reads have more influence. Cards explain their creator or genre link.

Owned titles and URLs, recent manga IDs, and duplicate normalized titles are filtered.
Existing statistics exclusions also gate discovery sources and reading anchors.
Unrelated titles do not pad the default shelf. Explicitly selected extensions may
provide fallback picks labeled “Latest from …” or “Popular on …”.

Discovery examines up to 24 candidates, with four detail requests at a time and a
six-second browse/detail budget. Individual failures do not discard other matches;
completed matches survive a timeout. Sparse source metadata may produce fewer picks
or no personalized shelf.

Successful results are cached in memory for six hours; empty results for 30 minutes.
Library membership, metadata, recent-read order, exclusion/category membership, and
discovery preferences invalidate the cache. Progress within the same reading profile
does not refetch. Discovery never blocks Home's initial local state.
