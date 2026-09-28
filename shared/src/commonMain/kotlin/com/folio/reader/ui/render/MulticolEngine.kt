package com.folio.reader.ui.render

/**
 * Paged reader engine that renders the chapter inside its **own `<iframe>`** and
 * lays it out with **CSS multi-column**, paging by scrolling the container — the
 * architecture foliate-js (`paginator.js` `View`) and epub.js (`IframeView`) use.
 *
 * Why the iframe: a plain top-document multicol strip, composited under the
 * Compose `AndroidView`, only re-rasterised its first column on a scroll/transform
 * (the "multicol overflow paint" failure seen on both JCEF and Android WebView).
 * An iframe gets its own document/compositing pass, so the browser's column
 * breaker paints every page — no hand-slicing, so a page break can't cut a line
 * and a continuation can't lose its styling.
 *
 * Structure built at runtime by [bootstrapJs] in the top document:
 * ```
 * <body> …styles… <div id=folio-scroller><iframe id=folio-frame></iframe></div> </body>
 * ```
 * The chapter + reader styles are written into the iframe; [innerJs] runs *inside*
 * it, columnises `documentElement`, sizes the iframe to the full multi-column
 * width, and pages by setting `#folio-scroller.scrollLeft`. The iframe is
 * position-fixed over the whole viewport, so iframe and top coordinates coincide
 * (selection popups and taps need no mapping).
 *
 * The engine speaks the same `document.title` protocol as [PageEngine] — it
 * reports to `parent.document.title` — and [bootstrapJs] installs top-window
 * proxies (`__folioSeek*`, `__folioRestyle`, `__folioPaintHighlights`,
 * `__folioStampImgs`, `__folioClearSel`, `__folioRelayout`) that forward into the
 * iframe, so the Kotlin bridge and highlight/restyle call sites are unchanged.
 *
 * Android paged only. Desktop stays on [PageEngine] (JCEF multicol unverified);
 * continuous stays on [ContinuousEngine].
 */
object MulticolEngine {

    /**
     * Neutral layout CSS for the paged path. Real layout is applied inline by
     * [innerJs] inside the iframe; this only stops the copied top-document sheet
     * from imposing a conflicting box. Theme/typography come from the base
     * [ReaderCss] rules, which are copied into the iframe verbatim.
     */
    fun frameCss(marginTop: Float, marginBottom: Float): String =
        "html,body{margin:0 !important;padding:0 !important;height:auto !important;" +
                "max-width:none !important;width:auto !important;overflow:visible !important;" +
                // Pin text auto-sizing off: the paged engine grows the document into a very
                // wide multi-column strip, and WebView font boosting inflates glyphs against
                // that width on first layout, then de-inflates — the "loads big, snaps to
                // normal" chapter jump (layout, not fonts).
                "-webkit-text-size-adjust:100% !important;text-size-adjust:100% !important;}" +
                // The engine only exists once bootstrapJs runs at onPageFinished, so without
                // this the chapter paints first as one full-width, uncolumnised page and then
                // snaps into the page layout. bootstrapJs lifts it with an inline style, so
                // this must stay unimportant, and it must not reach the iframe — bootstrap
                // copies this very sheet in, hence the [data-folio-frame] scope.
                "html:not([data-folio-frame])>body{opacity:0;}"

    /** Escapes a script body as a JS double-quoted string literal (`<` hex-escaped to dodge `</script>`). */
    private fun jsLit(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '<' -> sb.append("\\x3c")
            '\u2028' -> sb.append("\\u2028")
            '\u2029' -> sb.append("\\u2029")
            else -> sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Top-document bootstrap: moves the chapter + styles into an iframe, injects
     * [inner] into it, and installs forwarding proxies. Injected once per load in
     * place of the old paged engine script.
     */
    fun bootstrapJs(inner: String): String {
        val innerLit = jsLit(inner)
        return """
(function(){
if(window.__folioFramed)return;window.__folioFramed=true;
try{
var INNER=$innerLit;
var doc=document,body=doc.body;
body.style.opacity='0';
var base=doc.baseURI||'file:///folio/';
var styleHtml='',i;
var sts=doc.querySelectorAll('style');
for(i=0;i<sts.length;i++){var sid=sts[i].id?(' id="'+sts[i].id+'"'):'';styleHtml+='<style'+sid+'>'+sts[i].textContent+'<\/style>';}
var lnk=doc.querySelectorAll('link[rel="stylesheet"]');
for(i=0;i<lnk.length;i++){styleHtml+=lnk[i].outerHTML;}
// Classic print-book paragraphs (paged mode only): no blank line between prose
// paragraphs and a first-line indent on every paragraph that follows another —
// so the first paragraph of a chapter / the one after a heading, scene break,
// blockquote, image or list stays flush, exactly like a printed page. Appended
// AFTER the reader sheet so these equal-specificity !important rules win on
// source order and beat the reader's text-indent:0 / paragraph-spacing rules.
styleHtml+='<style id="folio-paged-type">p{margin-top:0 !important;margin-bottom:0 !important;}p + p{text-indent:1.2em !important;}</style>';
// Everything that is not a style/script/overlay is the chapter; move it into the iframe.
var holder=doc.createElement('div'),kids=[].slice.call(body.childNodes),k;
for(k=0;k<kids.length;k++){var el=kids[k],t=el.nodeType===1?el.tagName:'';
  if(t==='STYLE'||t==='SCRIPT'||t==='LINK')continue;
  if(el.nodeType===1&&(el.id==='folio-overlay-root'||el.id==='folio-selbtn'))continue;
  holder.appendChild(el);
}
var bodyHtml=holder.innerHTML;
var scroller=doc.createElement('div');scroller.id='folio-scroller';
scroller.setAttribute('style','position:fixed;left:0;top:0;right:0;bottom:0;overflow:hidden;');
var frame=doc.createElement('iframe');frame.id='folio-frame';frame.setAttribute('scrolling','no');
frame.setAttribute('style','border:0;margin:0;padding:0;height:100%;width:100vw;display:block;background:transparent;transform:translate3d(0,0,0);will-change:transform;backface-visibility:hidden;');
scroller.appendChild(frame);
body.insertBefore(scroller,body.firstChild);
var ifd=frame.contentDocument;
ifd.open();
ifd.write('<!doctype html><html data-folio-frame><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><base href="'+base+'">'+styleHtml+'</head><body>'+bodyHtml+'</body></html>');
ifd.close();
var s=ifd.createElement('script');s.textContent=INNER;(ifd.body||ifd.documentElement).appendChild(s);
function fw(n){return function(){try{var f=frame.contentWindow&&frame.contentWindow[n];if(f)return f.apply(frame.contentWindow,arguments);}catch(e){}};}
window.__folioSeek=fw('__folioSeek');window.__folioSeekTo=fw('__folioSeekTo');window.__folioSeekPara=fw('__folioSeekPara');
window.__folioRestyle=fw('__folioRestyle');window.__folioClearSel=fw('__folioClearSel');window.__folioStampImgs=fw('__folioStampImgs');
window.__folioPaintHighlights=fw('__folioPaintHighlights');window.__folioRelayout=fw('__folioRelayout');
window.__folioSetInsets=fw('__folioSetInsets');
body.style.opacity='1';
}catch(e){try{document.body.style.opacity='1';document.title='folio-engdiag:bootthrow:'+String((e&&e.message)||e).replace(/[:]/g,' ').slice(0,90)+':1';}catch(e2){}}
})();
"""
    }

    /** Engine that runs INSIDE the iframe: columnise, page, report, seek, gestures, selection. */
    fun innerJs(
        fraction: Float,
        marginTop: Float,
        marginBottom: Float,
        marginLeft: Float,
        marginRight: Float,
        measure: Int,
        diag: Boolean = false
    ): String = """
(function(){
if(window.__folioInner)return;window.__folioInner=true;
var MT=$marginTop,MB=$marginBottom,ML=$marginLeft,MR=$marginRight,MEASURE=$measure,posFrac=$fraction,DIAG=$diag,nonce=0;
var doc=document,root=doc.documentElement,bodyEl=doc.body;
try{console.log('FOLIO-BUILD paged v21 frac='+posFrac);}catch(e){}
root.style.opacity='0';
var total=1,page=0,dirty=true,animating=false,revealed=false,fontsReady=false,vShift=0;
function scroller(){return parent.document.getElementById('folio-scroller');}
function frameEl(){return parent.document.getElementById('folio-frame');}
function report(s){try{parent.document.title=s;}catch(e){}}
// Diagnostic: dumps the metrics that would move if the chapter is being resized after
// paint (font-size = boosting, pw/ph = viewport, sw/total = column measure). Surfaces in
// logcat under FolioPage via onConsoleMessage when READER_DEBUG_LOG is on.
function diag(w){try{var H=pageH(),x0=page*pitch(),x1=x0+pageW();var els=bodyEl.querySelectorAll('p,h1,h2,h3,h4,h5,h6,blockquote,li,figure,figcaption,img,pre,table,dd,dt,hr');var top=1e9,bot=-1e9,i,r,cx;for(i=0;i<els.length;i++){r=els[i].getBoundingClientRect();if(r.width<=0||r.height<=0)continue;cx=r.left;if(cx>=x0-1&&cx<x1-1){if(r.top<top)top=r.top;if(r.bottom>bot)bot=r.bottom;}}var cT=(top>1e8?-1:Math.round(top)),cB=(bot<0?-1:Math.round(bot)),raw=(bot<0||top>1e8)?0:Math.round((H-top-bot)/2);console.log('FOLIO-DIAG '+w+' ih='+window.innerHeight+' MT='+MT+' MB='+MB+' pw='+pageW()+' ph='+H+' cT='+cT+' cB='+cB+' raw='+raw+' vs='+vShift+' total='+total+' page='+page+' rev='+revealed);}catch(e){}}
function pageW(){var sc=scroller();return Math.max(1,(sc&&sc.clientWidth)||window.innerWidth);}
// Inter-page gutter. Pages step by a PITCH of one viewport + this gap, so at rest
// a page still fills the viewport exactly (the gap sits just off the right edge),
// but during the slide a strip of blank paper passes between the outgoing and
// incoming page — a real book gutter — so their naturally-misaligned text rows are
// separated by whitespace instead of abutting, which is what read as jarring.
function gap(){return Math.max(28,Math.round(pageW()*0.05));}
function pitch(){return pageW()+gap();}
function pageH(){var sc=scroller();return Math.max(1,(sc&&sc.clientHeight)||window.innerHeight);}
function padPx(){var W=pageW();if(MEASURE>0&&W>MEASURE)return Math.floor((W-MEASURE)/2);return 0;}
// Inset the whole page from the screen edges by the reader's margins, so the
// text block has consistent top/bottom/side margins like a real page (and like
// continuous mode) instead of filling the screen edge to edge.
function insetScroller(){var sc=scroller();if(sc){sc.style.left=ML+'px';sc.style.top=MT+'px';sc.style.right=MR+'px';sc.style.bottom=MB+'px';}}
// Reading gutters live on leaf text blocks (not containers, to avoid stacking
// margins), so the column box stays exactly one viewport wide and pages never
// drift. Each column holds whole blocks, so the gutter shows on every page.
function gutters(pad){
  var list=bodyEl.querySelectorAll('p,h1,h2,h3,h4,h5,h6,blockquote,li,figcaption,pre,dt,dd,td,th,hr');
  for(var i=0;i<list.length;i++){list[i].style.setProperty('margin-left',pad+'px','important');list[i].style.setProperty('margin-right',pad+'px','important');}
}
function capMedia(H){var m=doc.querySelectorAll('img,svg,video,canvas'),i,el;for(i=0;i<m.length;i++){el=m[i];el.style.setProperty('max-height',H+'px','important');el.style.setProperty('max-width','100%','important');el.style.setProperty('object-fit','contain','important');el.style.setProperty('break-inside','avoid','important');}}
function applyCols(){
  var W=pageW(),H=pageH(),st=root.style;
  // EXACT pitch: column-width == viewport, gap 0 → every column is one viewport
  // wide and sits at an exact multiple of W, so scrollLeft steps of W land dead
  // on a column boundary and cannot accumulate drift. (The earlier colW+gap
  // scheme let the engine stretch columns, which drifted a few px per page.)
  st.setProperty('column-width',W+'px','important');
  st.setProperty('column-gap',gap()+'px','important');
  st.setProperty('column-fill','auto','important');
  st.setProperty('height',H+'px','important');
  st.setProperty('margin','0','important');
  st.setProperty('padding','0','important');
  st.setProperty('overflow','hidden','important');
  st.setProperty('overflow-wrap','break-word','important');
  // Hyphenation keeps justified columns from opening rivers; needs a lang.
  if(!root.lang)root.lang='en';
  st.setProperty('hyphens','auto','important');
  st.setProperty('-webkit-hyphens','auto','important');
  // Font boosting OFF (see frameCss): the wide strip makes WebView inflate font-size on
  // the first layout, so the chapter painted BIG then snapped smaller once layout settled.
  // Pinned inline on the root/body with !important so nothing the publisher ships re-enables it.
  st.setProperty('-webkit-text-size-adjust','100%','important');
  st.setProperty('text-size-adjust','100%','important');
  st.removeProperty('width');
  bodyEl.style.setProperty('margin','0','important');
  bodyEl.style.setProperty('max-width','none','important');
  bodyEl.style.setProperty('padding','0','important');
  bodyEl.style.setProperty('-webkit-text-size-adjust','100%','important');
  bodyEl.style.setProperty('text-size-adjust','100%','important');
  gutters(padPx());
  capMedia(H);
}
function measure(){
  insetScroller();
  applyCols();
  var W=pageW(),G=gap(),P=W+G;
  total=Math.max(1,Math.round((root.scrollWidth+G)/P));
  // Size to exactly N columns of W at pitch P: the browser then makes N columns each
  // exactly W wide (no leftover space to stretch into), preserving exact pitch.
  var expanded=total*P-G;
  root.style.setProperty('width',expanded+'px','important');
  root.style.setProperty('column-width',W+'px','important');
  var fe=frameEl();if(fe)fe.style.width=expanded+'px';
  diag('measure');
  dirty=false;
}
function maxPage(){return Math.max(0,total-1);}
// Vertical centring. Multicol fills each page's column from the TOP, and orphan/widow
// control pushes trailing lines to the next column, so the last line usually stops short
// of the bottom — the page reads as sitting a little high (top gap < bottom gap). Nudge
// the whole strip DOWN by half that slack so the visible block is centred. The empty tail
// absorbs the shift, so no content clips; a cap (~1.5 lines) keeps a sparse last page near
// the top instead of floating a lone paragraph to mid-screen. Horizontal is untouched.
function pageVShift(){
  try{
    var H=pageH(),x0=page*pitch(),x1=x0+pageW();
    var els=bodyEl.querySelectorAll('p,h1,h2,h3,h4,h5,h6,blockquote,li,figure,figcaption,img,pre,table,dd,dt,hr');
    var top=1e9,bot=-1e9,i,r,cx;
    for(i=0;i<els.length;i++){r=els[i].getBoundingClientRect();if(r.width<=0||r.height<=0)continue;cx=r.left;if(cx>=x0-1&&cx<x1-1){if(r.top<top)top=r.top;if(r.bottom>bot)bot=r.bottom;}}
    if(bot<0||top>1e8)return 0;
    var shift=Math.round((H-top-bot)/2);if(shift<0)shift=0;
    var lh=parseFloat(window.getComputedStyle(bodyEl).lineHeight);if(!lh||isNaN(lh))lh=1.5*(parseFloat(window.getComputedStyle(bodyEl).fontSize)||16);
    var cap=Math.round(lh*1.5);if(shift>cap)shift=cap;
    return shift;
  }catch(e){return 0;}
}
// Paging moves the iframe with a GPU transform, not the container's scrollLeft.
// scrollLeft re-lays-out/clamps every assignment, which made a finger drag shake;
// translate3d is composited so the page slides under the thumb smoothly. The
// transform is on the FRAME element in the parent document, so element rects INSIDE
// the iframe stay content-space and all the seek/anchor/tap math is unchanged.
function setX(x,animate){var fe=frameEl();if(!fe)return;fe.style.transition=animate?'transform .22s cubic-bezier(.2,.7,.3,1)':'none';fe.style.transform='translate3d('+(-Math.round(x))+'px,'+Math.round(vShift)+'px,0)';}
function apply(animate){vShift=pageVShift();diag('apply');setX(page*pitch(),animate);}
// First paint of a freshly-loaded CHAPTER slides + fades in from the direction of
// travel, so crossing a chapter boundary reads as a continuation of the page turn
// instead of a reload flash. Forward (opened at the start, posFrac≈0) enters from the
// right like a next-page turn; backward (opened at the end, posFrac≈1) enters from the
// left like a prev-page turn. Runs once per engine; in-chapter turns are untouched.
function revealIn(){revealed=true;diag('reveal');var fe=frameEl();if(!fe){root.style.opacity='1';return;}vShift=pageVShift();var landing=-page*pitch();var dir=(posFrac<=0.001)?1:-1;var off=Math.round(pageW()*0.16);var vy=Math.round(vShift);fe.style.transition='none';fe.style.transform='translate3d('+(landing+dir*off)+'px,'+vy+'px,0)';root.style.transition='opacity .2s ease';root.style.opacity='0';requestAnimationFrame(function(){requestAnimationFrame(function(){fe.style.transition='transform .28s cubic-bezier(.2,.7,.3,1)';fe.style.transform='translate3d('+landing+'px,'+vy+'px,0)';root.style.opacity='1';});});}
function report_(){
  var p=maxPage()>0?page/maxPage():0;
  report('folio-progress:'+p.toFixed(4)+':'+(page+1)+':'+total+':false');
}
var settleT=null;function settle(){if(settleT)clearTimeout(settleT);settleT=setTimeout(function(){animating=false;settleT=null;report_();},380);}
var lastEdge=0;function edge(w){var n=Date.now();if(n-lastEdge<600)return;lastEdge=n;
  // Boundary turn: slide the current page off in the travel direction, THEN ask the
  // host to load the neighbour, so crossing chapters reads as the page leaving rather
  // than a reload pop. The incoming chapter's revealIn() slides in from the opposite
  // side. If the host does NOT reload (book's first/last chapter, no neighbour), this
  // engine survives and the deferred slide-back bounces the page home; on a real
  // reload this whole context is torn down first, so the bounce never runs.
  var fe=frameEl(),cur=-page*pitch(),vy=Math.round(vShift);
  if(fe){fe.style.transition='transform .18s ease-in';fe.style.transform='translate3d('+(w==='end'?cur-pageW():cur+pageW())+'px,'+vy+'px,0)';}
  setTimeout(function(){report('folio-edge:'+w+':'+(++nonce));},160);
  setTimeout(function(){var f2=frameEl();if(f2){f2.style.transition='transform .2s ease-out';f2.style.transform='translate3d('+cur+'px,'+vy+'px,0)';}},900);
}
function goTo(p,instant){
  if(p<0&&page<=0){edge('start');return;}
  if(p>maxPage()&&page>=maxPage()){edge('end');return;}
  p=Math.max(0,Math.min(maxPage(),p));
  if(p===page){apply(false);report_();return;}
  if(animating)return;
  animating=true;page=p;posFrac=maxPage()>0?page/maxPage():0;
  var reduce=false;try{reduce=window.matchMedia('(prefers-reduced-motion: reduce)').matches;}catch(e){}
  apply(!instant&&!reduce);settle();report_();
}
function relayout(){if(dirty)measure();page=Math.max(0,Math.min(maxPage(),Math.round(posFrac*maxPage())));diag('relayout');if(!revealed){if(fontsReady){revealIn();}else{apply(false);}}else{apply(false);root.style.opacity='1';}report_();}
window.__folioRelayout=function(){dirty=true;relayout();};
// Live side-margin update (paged): the host pushes new left/right insets when the
// reader's "Side margins" slider moves, so paged side margins are affectable WITHOUT a
// full document reload (a reload would reseed the page from posFrac and flash). Just
// updates ML/MR and re-measures at the new page width; posFrac holds the current page.
window.__folioSetInsets=function(ml,mr){if(ml!=null)ML=ml;if(mr!=null)MR=mr;dirty=true;relayout();};
window.__folioSeek=function(f){if(DIAG)try{console.log('FOLIO-SEEK v='+f);}catch(e){}var v=Math.min(1,Math.max(0,f||0));posFrac=v;if(dirty)measure();page=Math.round(v*maxPage());apply(false);root.style.opacity='1';revealed=true;report_();};
// The iframe is never internally scrolled (the container scrolls), so an
// element's content-space x is just its bounding-rect left.
function absLeft(el){return el.getBoundingClientRect().left;}
function targetEl(t){
  var parts=String(t).split(':'),isH=parts[0]==='h',el=null,id=isH?(parts[1]||''):'';
  if(id){try{el=doc.querySelector('[data-folio-hl="'+id+'"]');}catch(e){el=null;}}
  if(!el){var pi=isH?parts[2]:parts[1];if(pi===undefined||pi==='')return null;var i=parseInt(pi,10);if(isNaN(i))return null;var ps=doc.querySelectorAll('p');if(!ps.length)return null;el=ps[Math.min(Math.max(0,i),ps.length-1)];}
  return el;
}
function land(el){if(dirty)measure();var tt=Math.min(maxPage(),Math.max(0,Math.floor((absLeft(el)+2)/pitch())));posFrac=maxPage()>0?tt/maxPage():0;page=tt;apply(false);root.style.opacity='1';revealed=true;report_();}
window.__folioSeekTo=function(t){if(DIAG)try{console.log('FOLIO-SEEKTO t='+t);}catch(e){}var parts=String(t).split(':'),isH=parts[0]==='h',f=parseFloat(isH?parts[3]:parts[2]);var el=targetEl(t);if(el){land(el);return;}if(!isNaN(f))window.__folioSeek(f);};
window.__folioSeekPara=function(i){window.__folioSeekTo('p:'+i);};
window.__folioAnchorSave=function(){var lo=page*pitch(),i,el,r,l,w;var list=doc.querySelectorAll('p,div,section,blockquote,h1,h2,h3,img,figure');for(i=0;i<list.length;i++){el=list[i];r=el.getBoundingClientRect();w=r.width;l=r.left;
  // A block the column breaker fragmented across columns reports a UNION rect spanning the whole strip, so the chapter wrapper — first in document order — always "intersects" the current page at left 0, and restoring to it lands on page 1. Only a block that fits one page may anchor.
  if(w>0&&w<=pageW()+1&&l+w>lo+2&&l<lo+pageW()-2){el.setAttribute('data-folio-anchor','1');return 'a';}}return '';};
window.__folioAnchorRestore=function(a){if(dirty)measure();var el=doc.querySelector('[data-folio-anchor="1"]');if(!el)return;el.removeAttribute('data-folio-anchor');var tt=Math.min(maxPage(),Math.max(0,Math.floor((absLeft(el)+2)/pitch())));posFrac=maxPage()>0?tt/maxPage():0;page=tt;apply(false);root.style.opacity='1';revealed=true;report_();};
window.__folioRestyle=function(fc,sc){
  var f=doc.getElementById('folio-fonts'),st=doc.getElementById('folio-reader-style');
  var fontsOn=!!(f&&fc&&f.textContent!==fc),styleOn=!!(st&&sc&&st.textContent!==sc);
  // The host pushes the sheet once after every load with IDENTICAL text. Honouring
  // that push would reflow and run the anchor dance, which moves the reader off the
  // page the document was just seeded to.
  if(!fontsOn&&!styleOn)return;
  var a=window.__folioAnchorSave();
  if(fontsOn)f.textContent=fc;
  if(styleOn)st.textContent=sc;
  dirty=true;measure();
  setTimeout(function(){dirty=true;window.__folioAnchorRestore(a);},80);
  setTimeout(function(){dirty=true;window.__folioAnchorRestore(a);},400);
};
// Selection reporting: paged format folio-sel:<paraIndex>:<text>:<nonce> to the TOP title.
function reportSel(){
  var sel=window.getSelection();var text=sel?(sel.toString()||'').trim():'';
  if(!sel||sel.isCollapsed||text.length<3){report('folio-selclear:'+(++nonce));return;}
  var sc0=null;try{sc0=sel.getRangeAt(0).startContainer;}catch(e){return;}
  var node=sc0?(sc0.nodeType===1?sc0:sc0.parentElement):null;var idx=0,ps=doc.querySelectorAll('p'),i;
  if(node){var p=null;for(var q=node;q;q=q.parentElement){if(q.tagName==='P'){p=q;break;}}
    if(p){for(i=0;i<ps.length;i++){if(ps[i]===p){idx=i;break;}}}
    else{var kids=bodyEl?bodyEl.children:[],blk=node;while(blk&&blk.parentElement&&blk.parentElement!==bodyEl)blk=blk.parentElement;for(i=0;i<kids.length;i++){if(kids[i]===blk){idx=i;break;}}}}
  report('folio-sel:'+idx+':'+encodeURIComponent(text.slice(0,500))+':'+(++nonce));
}
window.__folioClearSel=function(){try{window.getSelection().removeAllRanges();}catch(e){}};
doc.addEventListener('selectionchange',function(){clearTimeout(window.__folioSelT);window.__folioSelT=setTimeout(reportSel,220);});
// Publisher filler spacers read as holes; hide them (matches PageEngine.emptyHideJs).
doc.querySelectorAll('p,div,section,blockquote').forEach(function(el){if(!el.querySelector('img,svg,canvas,video,hr,iframe')&&!(el.textContent||'').replace(/\s/g,'').length)el.style.display='none';});
// Gestures inside the iframe.
window.addEventListener('wheel',function(e){e.preventDefault();var d=Math.abs(e.deltaX)>Math.abs(e.deltaY)?e.deltaX:e.deltaY;if(!animating)goTo(page+(d>0?1:-1));},{passive:false});
doc.addEventListener('keydown',function(e){var k=e.key;if(k==='ArrowRight'||k==='PageDown'||k===' '||k==='ArrowDown'){e.preventDefault();goTo(page+1);}else if(k==='ArrowLeft'||k==='PageUp'||k==='ArrowUp'){e.preventDefault();goTo(page-1);}},true);
var tX=0,tY=0,tT=0,tsX=0,dragging=false,dragMoved=false,startScroll=0,dragTargetX=0,dragRAF=0;
doc.addEventListener('touchstart',function(e){var t=e.touches[0];tX=t.clientX;tY=t.clientY;tsX=t.screenX;tT=Date.now();if(animating){dragging=false;return;}dragging=true;dragMoved=false;startScroll=page*pitch();},{passive:true});
// Drag-to-turn: the strip follows the finger 1:1 (a page sliding under the thumb),
// then snaps or flicks on release. The drag delta uses SCREEN x, not the iframe's
// clientX: moving the frame shifts the iframe's own client coordinate space, so a
// clientX-based delta fed its own motion back in and made the page shake. Applied
// directly (transform is cheap/composited) for a 1:1, responsive follow.
doc.addEventListener('touchmove',function(e){if(!dragging)return;var t=e.touches[0];var dx=t.screenX-tsX,dy=t.clientY-tY;if(!dragMoved){if(Math.abs(dx)<8||Math.abs(dx)<=Math.abs(dy))return;dragMoved=true;}e.preventDefault();setX(startScroll-dx,false);},{passive:false});
// Three tap zones: right third → next page, left third → previous page, centre
// third → toggle the reader chrome (folio-tap). The tap x must be made
// PAGE-RELATIVE first: the iframe is one wide multi-column strip scrolled by the
// container, so `clientX` is a content coordinate that already includes the
// current page's scroll offset (page*W). Comparing the raw clientX against a
// single page width put every tap on pages after the first into the right third
// (always "next", and the centre never fired) — subtract page*W so the zones are
// measured against what is actually on screen. Centre-tap is emitted from the
// engine itself (like PageEngine); the host's native centre-tap detector stands
// down in paged mode so the two cannot double-toggle.
doc.addEventListener('touchend',function(e){var t=e.changedTouches[0];var dx=t.clientX-tX,dy=t.clientY-tY;var sdx=t.screenX-tsX;var ms=Date.now()-tT;var wasDrag=dragging&&dragMoved;dragging=false;if(dragRAF){cancelAnimationFrame(dragRAF);dragRAF=0;}if(wasDrag){var th=pageW()*0.18;var quick=ms<260&&Math.abs(sdx)>pageW()*0.06;if(sdx<=-th||(quick&&sdx<0)){goTo(page+1);}else if(sdx>=th||(quick&&sdx>0)){goTo(page-1);}else{animating=true;apply(true);settle();}return;}var moved=Math.hypot(dx,dy);var el=doc.elementFromPoint(t.clientX,t.clientY);var a=el&&el.closest?el.closest('a[href]'):null;if(a){var href=a.getAttribute('href')||'';if(href&&href.charAt(0)!=='#')report('folio-link:'+(++nonce)+':'+encodeURIComponent(href));return;}if(moved>24||ms>350)return;var w=pageW();var rel=t.clientX-page*pitch();if(DIAG)console.log('FOLIO-TAP cx='+Math.round(t.clientX)+' page='+page+' w='+Math.round(w)+' rel='+Math.round(rel)+' zone='+(rel>w*0.66?'next':(rel<w*0.33?'prev':'tap')));if(rel>w*0.66)goTo(page+1);else if(rel<w*0.33)goTo(page-1);else report('folio-tap:'+(++nonce));},{passive:true});
doc.addEventListener('click',function(ev){var a=ev.target&&ev.target.closest?ev.target.closest('a[href]'):null;if(a){var h=a.getAttribute('href')||'';if(h&&h.charAt(0)!=='#')ev.preventDefault();}},true);
window.addEventListener('resize',function(){dirty=true;relayout();});
// The scroller has overflow:hidden and is only ever moved programmatically by
// apply() (which already sets `page`/`posFrac`), so the scroll event is just an
// output — never a user gesture to reconcile from. It must NOT re-derive posFrac
// here: while the chapter is still measuring (total=1, or scrollLeft momentarily
// clamped to 0), a stray scroll event would overwrite an end-of-chapter seed
// (posFrac=1.0) with 0 and land the reader on page 1 — the "going back opens the
// previous chapter at its beginning instead of its end" bug. Just refresh the report.
if(scroller())scroller().addEventListener('scroll',function(){clearTimeout(window.__folioRepT);window.__folioRepT=setTimeout(report_,120);},{passive:true});
// First paint is held until the chapter's real fonts are IN, then revealed — but the
// wait is set up AFTER the first measure() below, on purpose. measure() forces a full
// layout, and that is what actually requests every @font-face the chapter uses:
// font-display:swap starts the load but paints a fallback face meanwhile. Only once
// layout has kicked those loads off can the font set be awaited honestly. Reading
// document.fonts.ready BEFORE that first layout (the v15 bug) resolved a tick early
// against a not-yet-populated set, so the reveal ran while the FALLBACK face was still
// showing. A fallback has larger metrics, so the page appeared "big" and then reflowed
// smaller the instant the real font swapped in — the chapter-load jump. Awaiting the
// used faces' load() promises (plus the ready net) closes that gap. No web fonts (or a
// document with no Font Loading API) reveals at once; a 650ms cap covers a stalled face.
doc.addEventListener('load',function(e){if(e.target&&e.target.tagName==='IMG'){dirty=true;relayout();}},true);
function markFontsReady(){if(fontsReady)return;fontsReady=true;dirty=true;relayout();}
try{
measure();
page=Math.max(0,Math.min(maxPage(),Math.round(posFrac*maxPage())));
// Stay hidden (root opacity is still 0) here regardless of posFrac: whether seeding to
// the chapter start or — for a backward turn — its END, the single reveal is driven by
// the font gate below, off the correct total measure() computes with the real font in.
// (Revealing at the start eagerly is what used to let a not-yet-settled total show page
// 1 then jump to the last page, and let the fallback face paint before its swap.)
apply(false);
report('folio-engdiag:ok:'+pageH()+':'+pageW()+':'+total+':'+doc.querySelectorAll('p').length+':'+(++nonce));
if(doc.fonts){
  // Await the faces this chapter actually uses, not the bare ready promise. Load the
  // body face by NAME (independent of layout's 'loading' timing) and every face the
  // forced layout already marked loading (headings, bold/italic runs). ready is kept in
  // the set as a safety net for a face that starts a beat later.
  var fontProms=[Promise.resolve(doc.fonts.ready).catch(function(){})];
  try{var cs=window.getComputedStyle(bodyEl);fontProms.push(doc.fonts.load((cs.fontWeight||'400')+' '+(cs.fontSize||'16px')+' '+cs.fontFamily).catch(function(){}));}catch(e){}
  try{doc.fonts.forEach(function(ff){if(ff.status==='loading')fontProms.push(ff.load().catch(function(){}));});}catch(e){}
  Promise.all(fontProms).then(markFontsReady,markFontsReady);
}else{markFontsReady();}
setTimeout(function(){if(!fontsReady)markFontsReady();},650);
setTimeout(function(){dirty=true;relayout();},250);
setTimeout(function(){dirty=true;relayout();},700);
report_();
}catch(e){root.style.opacity='1';report('folio-engdiag:throw:'+String((e&&e.message)||e).replace(/[:]/g,' ').slice(0,80)+':'+(++nonce));}
})();
"""
}
