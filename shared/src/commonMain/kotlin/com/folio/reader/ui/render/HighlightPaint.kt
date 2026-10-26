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
 * A highlight is anchored where the reader made it — [Highlight.spineIndex] names the
 * chapter section and [Highlight.startLocator] carries the paragraph index the selection
 * protocol recorded (`/spine/paragraph:0`). The painter resolves that anchor against the
 * live DOM: the section with the matching `data-folio-spine`, then the paragraph the
 * selection protocol would have indexed (the section's Nth `<p>`, or Nth direct child
 * when the section has no `<p>`s — the same rule `folio-sel2` uses). The text is the
 * *disambiguator within the anchor*, not the anchor itself.
 *
 * That ordering is what keeps a common word pinned to the spot it was selected in.
 * The first painter matched the bare text and wrapped **every** occurrence in **every**
 * section — highlighting "and" lit every "and" in the chapter, and in continuous mode,
 * where all window sections share one document, in the whole book.
 *
 * Colours come from the theme's own palette and are applied as a wash only — the text
 * colour is never touched, so the words stay readable on every theme. On dark themes a
 * translucent wash alone sinks into the background, so the colour is repeated as an
 * inset underline.
 */
object HighlightPaint {

    @Serializable
    private data class Mark(
        @SerialName("i") val id: String,
        @SerialName("t") val text: String,
        @SerialName("b") val background: String,
        @SerialName("u") val underline: String,
        /** The chapter section the highlight lives in (-1: unknown — legacy rows). */
        @SerialName("s") val spine: Int,
        /** The paragraph the selection protocol indexed (-1: unknown). */
        @SerialName("p") val paragraph: Int,
        @SerialName("n") val hasNote: Boolean,
    )

    /** Shared styling only; each mark carries its own colours so one page can mix them. */
    const val css: String =
        "mark.folio-hl{color:inherit;" +
                "-webkit-box-decoration-break:clone;box-decoration-break:clone;" +
                "border-radius:2px;padding:0;}" +
                "mark.folio-hl-new{background-repeat:no-repeat;animation:folio-ink-settle var(--folio-ink-duration,240ms) ease-out both;}" +
                "@keyframes folio-ink-settle{from{background-size:0% 100%}to{background-size:100% 100%}}" +
                "@media(prefers-reduced-motion:reduce){mark.folio-hl-new{animation:none}}" +
                "mark.folio-hl[data-folio-note='1']{position:relative;}" +
                "mark.folio-hl[data-folio-note='1']::after{content:'';position:absolute;right:-5px;top:0.1em;" +
                "width:3px;height:3px;border:1px solid currentColor;border-radius:50%;pointer-events:none;}"

    /** Wash + rule for one ARGB, tuned so text keeps its contrast on this theme. */
    fun colorsFor(argb: Int, isDark: Boolean): Pair<String, String> {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val rgb = "$r,$g,$b"
        return if (isDark) "rgba($rgb,0.30)" to "inset 0 -2px 0 rgba($rgb,0.95)"
        else "rgba($rgb,0.42)" to ""
    }

    /** The paragraph index half of the `/spine/paragraph:offset` locator. */
    private val LOCATOR_PARA = Regex("^/-?\\d+/(-?\\d+):")

    private fun payload(highlights: List<Highlight>, theme: Theme): String {
        val marks = highlights.filter { !it.isDeleted && it.selectedText.isNotBlank() }.map {
            val (background, underline) = colorsFor(it.effectiveColor, theme.isDark)
            Mark(
                id = it.id,
                text = it.selectedText.trim(),
                background = background,
                underline = underline,
                spine = it.spineIndex,
                paragraph = LOCATOR_PARA.find(it.startLocator)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: -1,
                hasNote = it.noteId != null,
            )
        }
        return Json.encodeToString(ListSerializer(Mark.serializer()), marks)
    }

    /** Installs the painter on a fresh document and paints it. */
    fun js(highlights: List<Highlight>, theme: Theme): String = js(payload(highlights, theme))

    /** Re-runs the painter already installed on the current document. */
    fun applyJs(highlights: List<Highlight>, theme: Theme): String =
        "window.__folioPaintHighlights&&window.__folioPaintHighlights(${payload(highlights, theme)});"

    fun motionJs(durationMs: Long): String = "(function setInk(d){try{d.documentElement.style.setProperty('--folio-ink-duration','${durationMs.coerceAtLeast(0)}ms');var f=d.querySelectorAll('iframe');for(var i=0;i<f.length;i++)if(f[i].contentDocument)setInk(f[i].contentDocument);}catch(e){}})(document);"

    private fun js(payload: String): String =
        "(function(){\n" +
                "var ITEMS=$payload,PREVIOUS=null;\n" +
                "function isWs(c){return c===' '||c==='\\t'||c==='\\n'||c==='\\r'||c==='\\f'||c==='\\u00a0';}\n" +
                // Whitespace is dropped from both the document map and the needle: a
                // selection taken across a paragraph, list item or line break carries a
                // newline that the document itself never contains, so any space-aware
                // comparison silently fails on exactly the highlights readers make most.
                "function norm(s){return s.replace(/\\s+/g,'');}\n" +
                // In continuous mode each `<section data-folio-spine>` hosts an open
                // Shadow DOM and the chapter text lives inside `section.shadowRoot`, out
                // of reach of a `document.body` walk. Return every shadow root when any
                // exist, otherwise fall back to `[document.body]` for paged / non-shadow.
                "function folioRoots(){var out=[],s=document.querySelectorAll('section[data-folio-spine]'),i,any=false;for(i=0;i<s.length;i++){if(s[i].shadowRoot){out.push(s[i].shadowRoot);any=true;}}return any?out:[document.body?document.body:document];}\n" +
                // One walk per match: wrapping splits text nodes, so the offset map
                // must always describe the document as it currently is.
                "function collect(root){\n" +
                "  var n=[],o=[],txt=[];\n" +
                "  if(!root)return{full:'',n:n,o:o};\n" +
                "  var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null,false);\n" +
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
                "  var roots=folioRoots(),ri;\n" +
                "  for(ri=0;ri<roots.length;ri++){\n" +
                "    var root=roots[ri];if(!root||!root.querySelectorAll)continue;\n" +
                "    var all=root.querySelectorAll('mark'),found=[],i,m,par;\n" +
                "    for(i=0;i<all.length;i++){if(all[i].className.indexOf('folio-hl')>=0)found.push(all[i]);}\n" +
                "    for(i=0;i<found.length;i++){m=found[i];par=m.parentNode;if(!par)continue;" +
                "      while(m.firstChild)par.insertBefore(m.firstChild,m);par.removeChild(m);}\n" +
                "    if(root.normalize)root.normalize();\n" +
                "  }\n" +
                "}\n" +
                // Map offsets skip whitespace, so a run inside one node is contiguous
                // even when its raw indexes are not: the gap between them is all
                // whitespace, and it stays inside the same mark.
                "function gapIsWs(node,a,b){var s=node.nodeValue||'';for(var i=a;i<b;i++){if(!isWs(s.charAt(i)))return false;}}\n" +
                "function wrap(c,h,len,bg,sh,mid,note){\n" +
                "  var end=h+len,i=h,segs=[],seg=null,done=false,noted=false;\n" +
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
                "      var mk=document.createElement('mark');var fresh=PREVIOUS&&!PREVIOUS[mid];mk.className='folio-hl'+(fresh?' folio-hl-new':'');\n" +
                "      if(mid)mk.setAttribute('data-folio-hl',mid);\n" +
                "      if(note&&!noted){mk.setAttribute('data-folio-note','1');mk.setAttribute('aria-description','Has a saved note');noted=true;}\n" +
                "      mk.style.background=fresh?'linear-gradient('+bg+','+bg+')':bg;if(sh)mk.style.boxShadow=sh;\n" +
                "      r.surroundContents(mk);done=true;\n" +
                "    }catch(e){}\n" +
                "  }\n" +
                "  return done;\n" +
                "}\n" +
                // Paint ONE occurrence of the needle inside one scope, at its first
                // position. A highlight is one range the reader made in one place; the
                // old painter looped and wrapped every occurrence in the scope, which is
                // how highlighting "and" lit every "and" on the page.
                "function paintIn(scope,needle,it){\n" +
                "  if(!scope)return false;\n" +
                "  var c=collect(scope),guard=0;\n" +
                "  while(guard++<3){\n" +
                "    var hit=c.full.indexOf(needle);\n" +
                "    if(hit<0)return false;\n" +
                "    if(wrap(c,hit,needle.length,it.b,it.u,it.i,it.n))return true;\n" +
                // A failed surroundContents may still have split nodes — re-map once.
                "    c=collect(scope);\n" +
                "  }\n" +
                "  return false;\n" +
                "}\n" +
                // The paragraph node the selection protocol would have indexed: the
                // section scope's Nth <p>, or Nth direct child when the section has no
                // <p>s. Must stay in lockstep with folioReportSel's index computation.
                "function paraNode(sroot,para){\n" +
                "  var ps=sroot.querySelectorAll('p');\n" +
                "  if(ps.length)return ps[para]||null;\n" +
                "  var kids=sroot.children||[];\n" +
                "  return kids[para]||null;\n" +
                "}\n" +
                "window.__folioPaintHighlights=function(items){\n" +
                "  PREVIOUS=window.__folioKnownHighlights||null;var known={};for(var ki=0;ki<items.length;ki++)known[items[ki].id||items[ki].i||'']=true;\n" +
                "  unwrap();\n" +
                "  var roots=folioRoots(),ri,k;\n" +
                // When the document really has spine sections, a highlight whose section
                // is not among them must NOT fall through to the legacy whole-document
                // match: its chapter is simply not on screen, and matching anyway would
                // light the first occurrence of its text in whatever chapter is.
                "  var hasSecs=document.querySelectorAll('section[data-folio-spine]').length>0;\n" +
                "  for(k=0;k<items.length;k++){\n" +
                "    var it=items[k],needle=norm(it.t||'');\n" +
                "    if(!needle)continue;\n" +
                "    var done=false;\n" +
                // Anchor ladder. 1) the section named by the highlight's spine, narrowed
                // to the paragraph the selection recorded; 2) that whole section (the
                // paragraph may have reflowed since — its INDEX is stale but the text
                // still pins the spot to one occurrence); 3) a plain first-match walk
                // over the roots, for paged documents that carry no spine sections at
                // all (there the caller has already filtered the highlights to the one
                // rendered chapter, so a first match IS the anchor).
                "    if(it.s!=null&&it.s>=0&&hasSecs){\n" +
                "      var all=document.querySelectorAll('section[data-folio-spine]'),i,sec=null;\n" +
                "      for(i=0;i<all.length;i++){if(parseInt(all[i].getAttribute('data-folio-spine'),10)===it.s){sec=all[i];break;}}\n" +
                "      if(sec){\n" +
                "        var sroot=sec.shadowRoot||sec;\n" +
                "        if(it.p!=null&&it.p>=0){\n" +
                "          var pn=paraNode(sroot,it.p);\n" +
                "          if(pn)done=paintIn(pn,needle,it);\n" +
                "        }\n" +
                "        if(!done)done=paintIn(sroot,needle,it);\n" +
                "      }\n" +
                "    }else{\n" +
                "      for(ri=0;ri<roots.length&&!done;ri++){\n" +
                "        done=paintIn(roots[ri],needle,it);\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "  window.__folioKnownHighlights=known;\n" +
                "  if(window.__folioRelayout)window.__folioRelayout();\n" +
                "};\n" +
                "window.__folioPaintHighlights(ITEMS);\n" +
                "})();"
}
