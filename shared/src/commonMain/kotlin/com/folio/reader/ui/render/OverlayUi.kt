package com.folio.reader.ui.render

/**
 * Glass overlay panels (contents, annotations, reading settings) rendered INSIDE
 * the page surface. On desktop the embedded browser is a heavyweight window that
 * always paints above Compose, so floating panels are drawn by the page itself:
 * the host pushes an HTML spec, the page reports interactions back through the
 * folio-ovl title protocol. Actions: close, toc:<i>, ann:<kind>:<id>,
 * set:<key>:<value>, allsettings, del:<kind>:<id>.
 */
data class OverlayColors(
    val bg: String,        // page background (#rrggbb)
    val fg: String,
    val accent: String,
    val surface: String,   // panel tint (#rrggbb)
    val isDark: Boolean
)

object OverlayUi {

    /** Safe for HTML text nodes and for single/double-quoted attribute values. */
    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        .replace('\n', ' ')
        .replace('\r', ' ')

    private fun shell(title: String, bodyHtml: String, c: OverlayColors, width: Int = 300, kind: String = ""): String {
        // Near-opaque fill, mirroring the Compose glassPanel: a see-through tint let
        // the page bleed through at full contrast and labels dissolved into it.
        val fill = c.bg + if (c.isDark) "DB" else "E3"
        val sheen = if (c.isDark)
            "linear-gradient(rgba(255,255,255,0.14),rgba(255,255,255,0.04) 50%,rgba(255,255,255,0.08))"
        else
            "linear-gradient(rgba(255,255,255,0.26),rgba(255,255,255,0.08) 50%,rgba(255,255,255,0.17))"
        val hairline = if (c.isDark) "rgba(255,255,255,0.26)" else "rgba(0,0,0,0.16)"
        val hi = if (c.isDark) "rgba(255,255,255,0.13)" else "rgba(255,255,255,0.60)"
        // Near-opaque fill, no backdrop-filter blur: over an almost solid tint there
        // is nothing left to blur, and the sheen + rim + shadow carry the glassy look.
        return """
<div data-act="close" style="position:fixed;inset:0;background:rgba(0,0,0,0.45);z-index:2147483500;"></div>
<div data-kind="$kind" style="position:fixed;top:0;right:0;bottom:0;width:${width}px;max-width:88vw;z-index:2147483501;
 background-color:$fill;background-image:$sheen;
 border-left:1px solid $hairline;color:${c.fg};font-family:'Segoe UI',system-ui,sans-serif;font-size:14px;
 box-shadow:-18px 0 60px rgba(0,0,0,0.45), inset 1px 1px 0 $hi;
 display:flex;flex-direction:column;">
  <div style="display:flex;align-items:center;justify-content:space-between;padding:16px 18px 10px;">
    <div style="font-size:17px;font-weight:600;">$title</div>
    <button data-act="close" style="all:unset;cursor:pointer;font-size:18px;opacity:0.7;padding:4px 8px;">&#10005;</button>
  </div>
  <div data-scroll style="overflow-y:auto;padding:6px 18px 20px;display:flex;flex-direction:column;gap:10px;">
   $bodyHtml
  </div>
</div>"""
    }

    private val itemCss = "all:unset;cursor:pointer;display:block;width:100%;box-sizing:border-box;" +
            "padding:10px 12px;border-radius:10px;"

    fun toc(chapters: List<String>, current: Int, c: OverlayColors): String {
        val rows = chapters.mapIndexed { i, title ->
            val active = i == current
            val style = if (active) "$itemCss background:${c.accent}22;color:${c.accent};font-weight:600;" else
                "$itemCss color:${c.fg};"
            val cls = if (active) " class=\"ovl-active\"" else ""
            "<button$cls data-act=\"toc:$i\" style=\"$style\" onmouseover=\"this.style.background='${if (c.isDark) "rgba(255,255,255,0.08)" else "rgba(0,0,0,0.06)"}'\" onmouseout=\"this.style.background='${if (active) c.accent + "22" else "transparent"}'\">${esc(title)}</button>"
        }.joinToString("")
        return shell("Contents", rows, c, width = 280, kind = "toc")
    }

    data class AnnotationRow(
        val kind: String,
        val id: String,
        val title: String,
        val sub: String,
        /** Note attached to this row (highlights own their notes). */
        val note: String? = null,
        /** Id of the attached note, when deletable. */
        val noteId: String? = null,
        /** Whether this row offers the note action. */
        val canNote: Boolean = false
    )

    fun annotations(bookmarks: List<AnnotationRow>, highlights: List<AnnotationRow>, notes: List<AnnotationRow>, c: OverlayColors): String {
        val hover = if (c.isDark) "rgba(255,255,255,0.12)" else "rgba(0,0,0,0.08)"
        val rest = if (c.isDark) "rgba(255,255,255,0.05)" else "rgba(0,0,0,0.04)"
        fun section(name: String, rows: List<AnnotationRow>) = if (rows.isEmpty()) "" else {
            "<div style='font-size:12px;font-weight:700;letter-spacing:0.6px;opacity:0.6;text-transform:uppercase;margin-top:6px;'>$name</div>" +
                    rows.joinToString("") { r ->
                        val noteAction = if (r.canNote)
                            "<button data-act=\"note:${r.kind}:${r.id}\" title=\"${if (r.note.isNullOrBlank()) "Add note" else "Edit note"}\" style=\"all:unset;cursor:pointer;color:${c.accent};padding:4px 8px;font-size:12px;font-weight:600;\">${if (r.note.isNullOrBlank()) "＋note" else "note"}</button>"
                        else ""
                        val nested = if (!r.note.isNullOrBlank())
                            "<div style=\"display:flex;gap:6px;align-items:flex-start;margin-top:6px;padding:8px 10px;border-left:2px solid ${c.accent};border-radius:0 8px 8px 0;background:$rest;\">" +
                                    "<div style=\"flex:1;min-width:0;font-size:12.5px;line-height:1.45;white-space:pre-wrap;\">${esc(r.note)}</div>" +
                                    (if (r.noteId != null) "<button data-act=\"del:nt:${r.noteId}\" title=\"Delete note\" style=\"all:unset;cursor:pointer;color:${c.accent};font-size:12px;padding:0 2px;\">&#10005;</button>" else "") +
                                    "</div>"
                        else ""
                        "<div style=\"display:flex;flex-direction:column;$itemCss background:$rest;\">" +
                                "<div style=\"display:flex;gap:8px;align-items:center;\" data-act=\"ann:${r.kind}:${r.id}\" " +
                                "onmouseover=\"this.style.background='$hover'\" onmouseout=\"this.style.background='transparent'\">" +
                                "<div style=\"flex:1;min-width:0;\"><div style=\"white-space:nowrap;overflow:hidden;text-overflow:ellipsis;\">${esc(r.title)}</div>" +
                                "<div style=\"font-size:11px;opacity:0.6;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;\">${esc(r.sub)}</div></div>" +
                                noteAction +
                                "<button data-act=\"del:${r.kind}:${r.id}\" style=\"all:unset;cursor:pointer;color:${c.accent};padding:4px 8px;\" title=\"Remove\">&#128465;</button></div>" +
                                nested + "</div>"
                    }
        }
        val body = section("Bookmarks", bookmarks) + section("Highlights", highlights) + section("Notes", notes) +
                (if (bookmarks.isEmpty() && highlights.isEmpty() && notes.isEmpty())
                    "<div style='opacity:0.65'>Nothing here yet. Bookmark spots, add highlights and notes while reading.</div>" else "")
        return shell("Annotations", body, c, kind = "annotations")
    }

    /**
     * In-page note composer. Drawn by the page because the embedded browser is a
     * heavyweight window that paints over any Compose dialog on desktop.
     */
    fun noteComposer(highlightId: String, quote: String, existing: String, c: OverlayColors): String {
        val field = "<textarea data-note-input spellcheck='false' rows='6' style=\"width:100%;box-sizing:border-box;resize:none;" +
                "padding:12px;border-radius:12px;background:${if (c.isDark) "rgba(255,255,255,0.06)" else "rgba(0,0,0,0.04)"};" +
                "color:${c.fg};border:1px solid ${if (c.isDark) "#444" else "#ccc"};font:14px/1.5 'Segoe UI',system-ui,sans-serif;\" " +
                "placeholder='Write a note about this passage…'>${esc(existing)}</textarea>"
        val body =
                "<div style='font-size:13px;opacity:0.75;line-height:1.5;padding:10px 12px;border-left:2px solid ${c.accent};" +
                        "border-radius:0 8px 8px 0;background:${if (c.isDark) "rgba(255,255,255,0.05)" else "rgba(0,0,0,0.04)"};'>${esc(quote)}</div>" +
                field +
                "<button data-act='savenote:$highlightId' style='$itemCss text-align:center;background:${c.accent};color:#fff;font-weight:600;margin-top:6px;border-radius:12px;padding:12px;'>Save note</button>"
        return shell("Note", body, c, kind = "note")
    }

    fun settings(
        fontSize: Float, lineHeight: Float, margin: Float, fontFamily: String,
        fontOptions: List<String>, themeId: String, themes: List<Triple<String, String, String>>, // id, name, bg
        layoutMode: String,
        highlightColors: List<String>, highlightIndex: Int,
        c: OverlayColors
    ): String {
        val slider = { label: String, key: String, min: Double, max: Double, step: Double, value: Double, fmt: String ->
            "<div style='display:flex;justify-content:space-between;font-size:12px;opacity:0.75;margin-top:4px;'><span>$label</span><span>$fmt</span></div>" +
                    // Wrapped in a div: as a direct flex-column item Chromium's range
                    // input leaks +4px into the scroll container's overflow, spawning a
                    // phantom horizontal scrollbar.
                    "<div><input type='range' min='$min' max='$max' step='$step' value='$value' data-act='set:$key' style='width:100%;accent-color:${c.accent};' /></div>"
        }
        val fontSel = "<div style='display:flex;justify-content:space-between;font-size:12px;opacity:0.75;margin-top:4px;'><span>Typeface</span></div>" +
                "<select data-act='set:font' style='width:100%;box-sizing:border-box;padding:8px;border-radius:8px;background:${if (c.isDark) "#222" else "#fff"};color:${c.fg};border:1px solid ${if (c.isDark) "#444" else "#ccc"};'>" +
                fontOptions.joinToString("") { o -> "<option value='${esc(o)}' ${if (o == fontFamily) "selected" else ""}>${esc(o)}</option>" } +
                "</select>"
        val themeRow = "<div style='display:flex;justify-content:space-between;font-size:12px;opacity:0.75;margin-top:4px;'><span>Theme</span></div>" +
                "<div style='display:flex;gap:6px;flex-wrap:wrap;'>" +
                themes.joinToString("") { t ->
                    val sel = t.first == themeId
                    "<button data-act='set:theme:${t.first}' title='${esc(t.second)}' style='all:unset;cursor:pointer;width:38px;height:38px;border-radius:10px;background:${t.third};border:2px solid ${if (sel) c.accent else (if (c.isDark) "#444" else "#ccc")};" +
                            (if (sel) "box-shadow:0 0 0 2px ${c.accent}55;" else "") + "'></button>"
                } + "</div>"
        val highlightRow = if (highlightColors.isEmpty()) "" else
            "<div style='display:flex;justify-content:space-between;font-size:12px;opacity:0.75;margin-top:4px;'><span>Highlight</span></div>" +
                    "<div style='display:flex;gap:6px;flex-wrap:wrap;'>" +
                    highlightColors.mapIndexed { idx, hex ->
                        val sel = idx == highlightIndex
                        "<button data-act='set:hlcolor:$idx' title='Highlight colour' style='all:unset;cursor:pointer;width:28px;height:28px;border-radius:8px;background:$hex;border:2px solid ${if (sel) c.accent else (if (c.isDark) "#444" else "#ccc")};" +
                                (if (sel) "box-shadow:0 0 0 2px ${c.accent}55;" else "") + "'></button>"
                    }.joinToString("") + "</div>"
        val segment = { label: String, act: String, options: List<Pair<String, String>>, selected: String ->
            "<div style='display:flex;justify-content:space-between;font-size:12px;opacity:0.75;margin-top:4px;'><span>$label</span></div>" +
                    "<div style='display:flex;gap:6px;'>" +
                    options.joinToString("") { (value, title) ->
                        val sel = value == selected
                        "<button data-act='$act:$value' style='all:unset;cursor:pointer;flex:1;box-sizing:border-box;text-align:center;padding:8px 4px;border-radius:9px;font-size:12.5px;" +
                                "background:${if (sel) c.accent else "transparent"};color:${if (sel) "#fff" else c.fg};" +
                                "border:1px solid ${if (sel) c.accent else (if (c.isDark) "#444" else "#ccc")};${if (sel) "font-weight:600;" else ""}'>${esc(title)}</button>"
                    } + "</div>"
        }
        val layoutRow = segment(
            "Layout", "set:layout",
            listOf("CONTINUOUS" to "Scroll", "PAGINATED" to "Page", "SPREAD" to "Double Page"),
            layoutMode
        )
        val body =
            slider("Text size", "size", 12.0, 26.0, 0.5, fontSize.toDouble(), "%.1f".format(fontSize)) +
                    slider("Line spacing", "lh", 1.0, 3.0, 0.1, lineHeight.toDouble(), "%.1f".format(lineHeight)) +
                    slider("Margins", "mg", 0.0, 64.0, 1.0, margin.toDouble(), "${margin.toInt()} px") +
                    layoutRow + fontSel + themeRow + highlightRow +
                    "<button data-act='allsettings' style='$itemCss text-align:center;background:${c.accent};color:#fff;font-weight:600;margin-top:8px;border-radius:12px;padding:12px;'>All settings</button>"
        return shell("Reading settings", body, c, kind = "settings")
    }
}
