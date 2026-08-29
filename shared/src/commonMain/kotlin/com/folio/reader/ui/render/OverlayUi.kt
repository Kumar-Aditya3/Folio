package com.folio.reader.ui.render

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Glass overlay panels (contents, annotations, reading settings) rendered INSIDE
 * the page surface. On desktop the embedded browser is a heavyweight window that
 * always paints above Compose, so floating panels are drawn by the page itself:
 * the host pushes an HTML spec, the page reports interactions back through the
 * folio-ovl title protocol. Actions: close, toc:<i>, set:<key>:<value>,
 * allsettings, del:<kind>:<id>.
 */
data class OverlayColors(
    val bg: String,        // page background (#rrggbb)
    val fg: String,
    val accent: String,
    val surface: String,   // panel tint (#rrggbb)
    val isDark: Boolean
)

object OverlayUi {

    private fun esc(s: String): String = Json.encodeToString(String.serializer(), s)
        .removeSurrounding("\"")

    private fun shell(title: String, bodyHtml: String, c: OverlayColors, width: Int = 300): String {
        val tint = if (c.isDark) "rgba(16,16,22,0.55)" else "rgba(250,248,242,0.52)"
        val hairline = if (c.isDark) "rgba(255,255,255,0.22)" else "rgba(0,0,0,0.14)"
        val hi = if (c.isDark) "rgba(255,255,255,0.10)" else "rgba(255,255,255,0.55)"
        return """
<div data-act="close" style="position:fixed;inset:0;background:rgba(0,0,0,0.45);z-index:2147483500;"></div>
<div style="position:fixed;top:0;right:0;bottom:0;width:${width}px;max-width:88vw;z-index:2147483501;
 background:$tint;backdrop-filter:blur(26px) saturate(1.8);-webkit-backdrop-filter:blur(26px) saturate(1.8);
 border-left:1px solid $hairline;color:${c.fg};font-family:'Segoe UI',system-ui,sans-serif;font-size:14px;
 box-shadow:-18px 0 60px rgba(0,0,0,0.45), inset 1px 1px 0 $hi;
 display:flex;flex-direction:column;">
  <div style="display:flex;align-items:center;justify-content:space-between;padding:16px 18px 10px;">
    <div style="font-size:17px;font-weight:600;">$title</div>
    <button data-act="close" style="all:unset;cursor:pointer;font-size:18px;opacity:0.7;padding:4px 8px;">&#10005;</button>
  </div>
  <div style="overflow-y:auto;padding:6px 18px 20px;display:flex;flex-direction:column;gap:10px;">
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
            "<button data-act=\"toc:$i\" style=\"$style\" onmouseover=\"this.style.background='${if (c.isDark) "rgba(255,255,255,0.08)" else "rgba(0,0,0,0.06)"}'\" onmouseout=\"this.style.background='${if (active) c.accent + "22" else "transparent"}'\">${esc(title)}</button>"
        }.joinToString("")
        return shell("Contents", rows, c, width = 280)
    }

    data class AnnotationRow(val kind: String, val id: String, val title: String, val sub: String)

    fun annotations(bookmarks: List<AnnotationRow>, highlights: List<AnnotationRow>, notes: List<AnnotationRow>, c: OverlayColors): String {
        fun section(name: String, rows: List<AnnotationRow>) = if (rows.isEmpty()) "" else {
            "<div style='font-size:12px;font-weight:700;letter-spacing:0.6px;opacity:0.6;text-transform:uppercase;margin-top:6px;'>$name</div>" +
                    rows.joinToString("") { r ->
                        "<div style=\"display:flex;gap:8px;align-items:center;$itemCss background:${if (c.isDark) "rgba(255,255,255,0.05)" else "rgba(0,0,0,0.04)"};\">" +
                                "<div style=\"flex:1;min-width:0;\"><div style=\"white-space:nowrap;overflow:hidden;text-overflow:ellipsis;\">${esc(r.title)}</div>" +
                                "<div style=\"font-size:11px;opacity:0.6;\">${esc(r.sub)}</div></div>" +
                                "<button data-act=\"del:${r.kind}:${r.id}\" style=\"all:unset;cursor:pointer;color:${c.accent};padding:4px 8px;\" title=\"Remove\">&#128465;</button></div>"
                    }
        }
        val body = section("Bookmarks", bookmarks) + section("Highlights", highlights) + section("Notes", notes) +
                (if (bookmarks.isEmpty() && highlights.isEmpty() && notes.isEmpty())
                    "<div style='opacity:0.65'>Nothing here yet. Bookmark spots, add highlights and notes while reading.</div>" else "")
        return shell("Annotations", body, c)
    }

    fun settings(
        fontSize: Float, lineHeight: Float, margin: Float, fontFamily: String,
        fontOptions: List<String>, themeId: String, themes: List<Triple<String, String, String>>, // id, name, bg
        c: OverlayColors
    ): String {
        val slider = { label: String, key: String, min: Double, max: Double, step: Double, value: Double, fmt: String ->
            "<div style='display:flex;justify-content:space-between;font-size:12px;opacity:0.75;margin-top:4px;'><span>$label</span><span>$fmt</span></div>" +
                    "<input type='range' min='$min' max='$max' step='$step' value='$value' data-act='set:$key' style='width:100%;accent-color:${c.accent};' />"
        }
        val fontSel = "<div style='display:flex;justify-content:space-between;font-size:12px;opacity:0.75;margin-top:4px;'><span>Typeface</span></div>" +
                "<select data-act='set:font' style='width:100%;padding:8px;border-radius:8px;background:${if (c.isDark) "#222" else "#fff"};color:${c.fg};border:1px solid ${if (c.isDark) "#444" else "#ccc"};'>" +
                fontOptions.joinToString("") { o -> "<option value='${esc(o)}' ${if (o == fontFamily) "selected" else ""}>${esc(o)}</option>" } +
                "</select>"
        val themeRow = "<div style='display:flex;justify-content:space-between;font-size:12px;opacity:0.75;margin-top:4px;'><span>Theme</span></div>" +
                "<div style='display:flex;gap:8px;flex-wrap:wrap;'>" +
                themes.joinToString("") { t ->
                    val sel = t.first == themeId
                    "<button data-act='set:theme:${t.first}' title='${esc(t.second)}' style='all:unset;cursor:pointer;width:40px;height:40px;border-radius:10px;background:${t.third};border:2px solid ${if (sel) c.accent else (if (c.isDark) "#444" else "#ccc")};" +
                            (if (sel) "box-shadow:0 0 0 2px ${c.accent}55;" else "") + "'></button>"
                } + "</div>"
        val body =
            slider("Text size", "size", 12.0, 26.0, 0.5, fontSize.toDouble(), "%.1f".format(fontSize)) +
                    slider("Line spacing", "lh", 1.0, 3.0, 0.1, lineHeight.toDouble(), "%.1f".format(lineHeight)) +
                    slider("Margins", "mg", 0.0, 64.0, 1.0, margin.toDouble(), "${margin.toInt()} px") +
                    fontSel + themeRow +
                    "<button data-act='allsettings' style='$itemCss text-align:center;background:${c.accent};color:#fff;font-weight:600;margin-top:8px;border-radius:12px;padding:12px;'>All settings</button>"
        return shell("Reading settings", body, c)
    }
}
