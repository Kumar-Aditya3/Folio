package com.folio.reader.ui.render

import com.folio.reader.model.Highlight
import com.folio.reader.settings.Theme
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Draws the chapter's highlights into the page itself.
 *
 * Folio anchors annotations by text, so the page re-finds each snippet across text
 * nodes and wraps it in a `<mark>`. Colours come from the theme's own palette and
 * are applied as a wash only — the text colour is never touched, so the words stay
 * readable on every theme. On dark themes a translucent wash alone sinks into the
 * background, so the colour is repeated as an inset underline.
 */
object HighlightPaint {

    @Serializable
    private data class Mark(
        @SerialName("i") val id: String,
        @SerialName("t") val text: String,
        @SerialName("b") val background: String,
        @SerialName("u") val underline: String
    )

    /** Shared styling only; each mark carries its own colours so one page can mix them. */
    const val css: String =
        "mark.folio-hl{color:inherit;" +
                "-webkit-box-decoration-break:clone;box-decoration-break:clone;" +
                "border-radius:2px;padding:0;}"

    /** Wash + rule for one ARGB, tuned so text keeps its contrast on this theme. */
    fun colorsFor(argb: Int, isDark: Boolean): Pair<String, String> {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val rgb = "$r,$g,$b"
        return if (isDark) "rgba($rgb,0.30)" to "inset 0 -2px 0 rgba($rgb,0.95)"
        else "rgba($rgb,0.42)" to ""
    }

    private fun payload(highlights: List<Highlight>, theme: Theme): String {
        val marks = highlights.filter { !it.isDeleted && it.selectedText.isNotBlank() }.map {
            val (background, underline) = colorsFor(it.effectiveColor, theme.isDark)
            Mark(it.id, it.selectedText.trim(), background, underline)
        }
        return Json.encodeToString(ListSerializer(Mark.serializer()), marks)
    }

    /** Installs the painter on a fresh document and paints it. */
    fun js(highlights: List<Highlight>, theme: Theme): String = js(payload(highlights, theme))

    /** Re-runs the painter already installed on the current document. */
    fun applyJs(highlights: List<Highlight>, theme: Theme): String =
        "window.__folioPaintHighlights&&window.__folioPaintHighlights(${payload(highlights, theme)});"

    private fun js(payload: String): String =
        "(function(){\n" +
                "var ITEMS=$payload;\n" +
                "function isWs(c){return c===' '||c==='\\t'||c==='\\n'||c==='\\r'||c==='\\f'||c==='\\u00a0';}\n" +
                // Whitespace is dropped from both the document map and the needle: a
                // selection taken across a paragraph, list item or line break carries a
                // newline that the document itself never contains, so any space-aware
                // comparison silently fails on exactly the highlights readers make most.
                "function norm(s){return s.replace(/\\s+/g,'');}\n" +
                // One walk per match: wrapping splits text nodes, so the offset map
                // must always describe the document as it currently is.
                "function collect(){\n" +
                "  var n=[],o=[],txt=[];\n" +
                "  if(!document.body)return{full:'',n:n,o:o};\n" +
                "  var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);\n" +
                "  while(w.nextNode()){\n" +
                "    var node=w.currentNode,v=node.nodeValue||'',p=node.parentNode;\n" +
                "    if(!p)continue;\n" +
                "    var tag=p.nodeName;\n" +
                "    if(tag==='SCRIPT'||tag==='STYLE'||tag==='NOSCRIPT'||tag==='TITLE'||tag==='TEXTAREA')continue;\n" +
                "    if(p.closest&&p.closest('#folio-overlay-root,#folio-selbtn'))continue;\n" +
                "    for(var j=0;j<v.length;j++){\n" +
                "      var c=v.charAt(j);\n" +
                "      if(isWs(c))continue;\n" +
                "      txt.push(c);\n" +
                "      n.push(node);o.push(j);\n" +
                "    }\n" +
                "  }\n" +
                "  return{full:txt.join(''),n:n,o:o};\n" +
                "}\n" +
                "function unwrap(){\n" +
                "  var all=document.getElementsByTagName('mark'),found=[],i,m,par;\n" +
                "  for(i=0;i<all.length;i++){if(all[i].className.indexOf('folio-hl')>=0)found.push(all[i]);}\n" +
                "  for(i=0;i<found.length;i++){m=found[i];par=m.parentNode;if(!par)continue;" +
                "    while(m.firstChild)par.insertBefore(m.firstChild,m);par.removeChild(m);}\n" +
                "  if(document.body&&document.body.normalize)document.body.normalize();\n" +
                "}\n" +
                // Map offsets skip whitespace, so a run inside one node is contiguous
                // even when its raw indexes are not: the gap between them is all
                // whitespace, and it stays inside the same mark.
                "function gapIsWs(node,a,b){var s=node.nodeValue||'';for(var i=a;i<b;i++){if(!isWs(s.charAt(i)))return false;}return true;}\n" +
                "function wrap(c,h,len,bg,sh,mid){\n" +
                "  var end=h+len,i=h,segs=[],seg=null,done=false;\n" +
                "  while(i<end){\n" +
                "    var node=c.n[i];\n" +
                "    if(seg&&seg.node===node&&(c.o[i]===seg.last+1||gapIsWs(node,seg.last+1,c.o[i]))){seg.last=c.o[i];}\n" +
                "    else{if(seg)segs.push(seg);seg={node:node,first:c.o[i],last:c.o[i]};}\n" +
                "    i++;\n" +
                "  }\n" +
                "  if(seg)segs.push(seg);\n" +
                "  for(var s=segs.length-1;s>=0;s--){\n" +
                "    try{\n" +
                "      var g=segs[s],r=document.createRange();\n" +
                "      r.setStart(g.node,g.first);r.setEnd(g.node,g.last+1);\n" +
                "      var mk=document.createElement('mark');mk.className='folio-hl';\n" +
                "      if(mid)mk.setAttribute('data-folio-hl',mid);\n" +
                "      mk.style.background=bg;if(sh)mk.style.boxShadow=sh;\n" +
                "      r.surroundContents(mk);done=true;\n" +
                "    }catch(e){}\n" +
                "  }\n" +
                "  return done;\n" +
                "}\n" +
                "window.__folioPaintHighlights=function(items){\n" +
                "  unwrap();\n" +
                "  for(var k=0;k<items.length;k++){\n" +
                "    var it=items[k],needle=norm(it.t||'');\n" +
                "    if(!needle)continue;\n" +
                "    var pos=0,guard=0;\n" +
                "    while(guard++<40){\n" +
                "      var c=collect(),hit=c.full.indexOf(needle,pos);\n" +
                "      if(hit<0||!wrap(c,hit,needle.length,it.b,it.u,it.i))break;\n" +
                "      pos=hit+needle.length;\n" +
                "    }\n" +
                "  }\n" +
                "  if(window.__folioRelayout)window.__folioRelayout();\n" +
                "};\n" +
                "window.__folioPaintHighlights(ITEMS);\n" +
                "})();"
}
