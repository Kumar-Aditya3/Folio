# Manga extension reliability

The connected device's crash buffer on 26 October 2026 contained a Folio crash at
14:27:04 in `DefaultDispatcher-worker-6`:

```text
java.lang.AbstractMethodError:
kotlinx.serialization.internal.GeneratedSerializer.typeParametersSerializers()
at kotlinx.serialization.internal.PluginGeneratedSerialDescriptor...
at kotlinx.serialization.json.okio.OkioStreamsKt.decodeFromBufferedSource...
```

Folio bundled serialization 1.7.3. Extension serializers compiled against 1.8+
can omit this method because they inherit a JVM default implementation. The 1.7.3
interface declares it abstract. Updating all serialization modules to 1.8.1 supplies
that implementation. See the [upstream report](https://github.com/Kotlin/kotlinx.serialization/issues/2968).
The obfuscated extension frames do not identify which installed source triggered it.

An older checked-in `logcat.txt` contains September bitmap drawing crashes involving
a 216 MB bitmap. Page decode caps were already present; they now also apply when
the caller has no target width.

Blocked requests record a notice per source and return their failure immediately.
They never open a visible browser or wait for a browser to close. Global search
offers an Open source action even when the source has no results. Inside that source,
the user can open its browser check, finish it, then explicitly retry. Closing the
browser alone does not clear the notice. Successful source requests clear only the
notice present when that request started.

Chapter refresh attempts the chapter endpoint even when metadata fails and retains
saved chapters on an empty refresh. Android passes the chapter's metadata to the
extension and uses page list order rather than extension-provided page indices.
Search requests receive the extension's default filter list. Detail metadata is
retained in a bounded cache for the following chapter request. Image requests use
the same access-error reporting as catalogue requests. Extension linkage errors
become visible request failures, including inside OkHttp interceptors.

These host fixes cannot establish that every upstream source currently works.
Source-specific parser or access failures still need a manga URL and a fresh request
trace to reproduce.

The user's follow-up identified AllManga crashes, Comick (unoriginal) chapter pages
failing, and Ero18x manga with no chapters. AllManga's current page loader requires
`chapter.memo["mangaId"]` ([AllManga source](https://github.com/keiyoushi/extensions-source/blob/main/src/en/allanime/src/eu/kanade/tachiyomi/extension/en/allanime/AllManga.kt));
Ero18x's Madara engine uses manga memo for its route
([Madara source](https://github.com/keiyoushi/extensions-source/blob/main/lib-multisrc/madara/src/eu/kanade/tachiyomi/multisrc/madara/MadaraNoAjax.kt)).
Folio previously discarded both. Opaque source memo now survives browse results,
library persistence, chapter refresh, reader requests and downloads, with additive
database columns. Reopen an affected manga from its source and refresh its chapters
to populate metadata missing from older saved entries. Comick's page-list parser
also uses JSON serializers affected by the runtime mismatch; a specific chapter URL
is still needed to establish any additional site/parser error.
