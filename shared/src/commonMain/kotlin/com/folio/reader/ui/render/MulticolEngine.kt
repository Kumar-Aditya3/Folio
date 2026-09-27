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
                "max-width:none !important;width:auto !important;overflow:visible !important;}"

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
frame.setAttribute('style','border:0;margin:0;padding:0;height:100%;width:100vw;display:block;background:transparent;');
scroller.appendChild(frame);
body.insertBefore(scroller,body.firstChild);
var ifd=frame.contentDocument;
ifd.open();
ifd.write('<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><base href="'+base+'">'+styleHtml+'</head><body>'+bodyHtml+'</body></html>');
ifd.close();
var s=ifd.createElement('script');s.textContent=INNER;(ifd.body||ifd.documentElement).appendChild(s);
function fw(n){return function(){try{var f=frame.contentWindow&&frame.contentWindow[n];if(f)return f.apply(frame.contentWindow,arguments);}catch(e){}};}
window.__folioSeek=fw('__folioSeek');window.__folioSeekTo=fw('__folioSeekTo');window.__folioSeekPara=fw('__folioSeekPara');
window.__folioRestyle=fw('__folioRestyle');window.__folioClearSel=fw('__folioClearSel');window.__folioStampImgs=fw('__folioStampImgs');
window.__folioPaintHighlights=fw('__folioPaintHighlights');window.__folioRelayout=fw('__folioRelayout');
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
try{console.log('FOLIO-BUILD paged v9 frac='+posFrac);}catch(e){}
root.style.opacity='0';
var total=1,page=0,dirty=true,animating=false;
function scroller(){return parent.document.getElementById('folio-scroller');}
function frameEl(){return parent.document.getElementById('folio-frame');}
function report(s){try{parent.document.title=s;}catch(e){}}
function pageW(){var sc=scroller();return Math.max(1,(sc&&sc.clientWidth)||window.innerWidth);}
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
  st.setProperty('column-gap','0','important');
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
  st.removeProperty('width');
  bodyEl.style.setProperty('margin','0','important');
  bodyEl.style.setProperty('max-width','none','important');
  bodyEl.style.setProperty('padding','0','important');
  gutters(padPx());
  capMedia(H);
}
function measure(){
  insetScroller();
  applyCols();
  var W=pageW();
  total=Math.max(1,Math.round(root.scrollWidth/W));
  // Size to exactly N columns of W: the browser then makes N columns each
  // exactly W wide (no leftover space to stretch into), preserving exact pitch.
  var expanded=total*W;
  root.style.setProperty('width',expanded+'px','important');
  root.style.setProperty('column-width',W+'px','important');
  var fe=frameEl();if(fe)fe.style.width=expanded+'px';
  dirty=false;
}
function maxPage(){return Math.max(0,total-1);}
function apply(animate){
  var sc=scroller();if(!sc)return;
  var x=page*pageW();
  if(animate&&sc.scrollTo){try{sc.scrollTo({left:x,behavior:'smooth'});return;}catch(e){}}
  sc.scrollLeft=x;
}
function report_(){
  var p=maxPage()>0?page/maxPage():0;
  report('folio-progress:'+p.toFixed(4)+':'+(page+1)+':'+total+':false');
}
var settleT=null;function settle(){if(settleT)clearTimeout(settleT);settleT=setTimeout(function(){animating=false;settleT=null;report_();},380);}
var lastEdge=0;function edge(w){var n=Date.now();if(n-lastEdge<600)return;lastEdge=n;report('folio-edge:'+w+':'+(++nonce));}
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
function relayout(){if(dirty)measure();page=Math.max(0,Math.min(maxPage(),Math.round(posFrac*maxPage())));apply(false);root.style.opacity='1';report_();}
window.__folioRelayout=function(){dirty=true;relayout();};
window.__folioSeek=function(f){var v=Math.min(1,Math.max(0,f||0));posFrac=v;if(dirty)measure();page=Math.round(v*maxPage());apply(false);root.style.opacity='1';report_();};
// The iframe is never internally scrolled (the container scrolls), so an
// element's content-space x is just its bounding-rect left.
function absLeft(el){return el.getBoundingClientRect().left;}
function targetEl(t){
  var parts=String(t).split(':'),isH=parts[0]==='h',el=null,id=isH?(parts[1]||''):'';
  if(id){try{el=doc.querySelector('[data-folio-hl="'+id+'"]');}catch(e){el=null;}}
  if(!el){var pi=isH?parts[2]:parts[1];if(pi===undefined||pi==='')return null;var i=parseInt(pi,10);if(isNaN(i))return null;var ps=doc.querySelectorAll('p');if(!ps.length)return null;el=ps[Math.min(Math.max(0,i),ps.length-1)];}
  return el;
}
function land(el){if(dirty)measure();var tt=Math.min(maxPage(),Math.max(0,Math.floor((absLeft(el)+2)/pageW())));posFrac=maxPage()>0?tt/maxPage():0;page=tt;apply(false);root.style.opacity='1';report_();}
window.__folioSeekTo=function(t){var parts=String(t).split(':'),isH=parts[0]==='h',f=parseFloat(isH?parts[3]:parts[2]);var el=targetEl(t);if(el){land(el);return;}if(!isNaN(f))window.__folioSeek(f);};
window.__folioSeekPara=function(i){window.__folioSeekTo('p:'+i);};
window.__folioAnchorSave=function(){var ch=root.children.length?bodyEl.children:[];var lo=page*pageW(),i,el,l;var list=doc.querySelectorAll('p,div,section,blockquote,h1,h2,h3,img,figure');for(i=0;i<list.length;i++){el=list[i];l=absLeft(el);if(l+el.getBoundingClientRect().width>lo+2&&l<lo+pageW()-2){el.setAttribute('data-folio-anchor','1');return 'a';}}return '';};
window.__folioAnchorRestore=function(a){if(dirty)measure();var el=doc.querySelector('[data-folio-anchor="1"]');if(!el)return;el.removeAttribute('data-folio-anchor');var tt=Math.min(maxPage(),Math.max(0,Math.floor((absLeft(el)+2)/pageW())));posFrac=maxPage()>0?tt/maxPage():0;page=tt;apply(false);root.style.opacity='1';report_();};
window.__folioRestyle=function(fc,sc){
  var a=window.__folioAnchorSave();
  var f=doc.getElementById('folio-fonts');if(f&&fc)f.textContent=fc;
  var st=doc.getElementById('folio-reader-style');if(st)st.textContent=sc;
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
var tX=0,tY=0,tT=0;
doc.addEventListener('touchstart',function(e){var t=e.touches[0];tX=t.clientX;tY=t.clientY;tT=Date.now();},{passive:true});
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
doc.addEventListener('touchend',function(e){var t=e.changedTouches[0];var dx=t.clientX-tX,dy=t.clientY-tY;if(Math.abs(dx)>48&&Math.abs(dx)>Math.abs(dy)*1.4){goTo(page+(dx<0?1:-1));return;}var moved=Math.hypot(dx,dy),ms=Date.now()-tT;var el=doc.elementFromPoint(t.clientX,t.clientY);var a=el&&el.closest?el.closest('a[href]'):null;if(a){var href=a.getAttribute('href')||'';if(href&&href.charAt(0)!=='#')report('folio-link:'+(++nonce)+':'+encodeURIComponent(href));return;}if(moved>24||ms>350)return;var w=pageW();var rel=t.clientX-page*w;if(DIAG)console.log('FOLIO-TAP cx='+Math.round(t.clientX)+' page='+page+' w='+Math.round(w)+' rel='+Math.round(rel)+' zone='+(rel>w*0.66?'next':(rel<w*0.33?'prev':'tap')));if(rel>w*0.66)goTo(page+1);else if(rel<w*0.33)goTo(page-1);else report('folio-tap:'+(++nonce));},{passive:true});
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
if(doc.fonts&&doc.fonts.ready)doc.fonts.ready.then(function(){dirty=true;relayout();});
doc.addEventListener('load',function(e){if(e.target&&e.target.tagName==='IMG'){dirty=true;relayout();}},true);
try{
measure();
page=Math.max(0,Math.min(maxPage(),Math.round(posFrac*maxPage())));
apply(false);root.style.opacity='1';
report('folio-engdiag:ok:'+pageH()+':'+pageW()+':'+total+':'+doc.querySelectorAll('p').length+':'+(++nonce));
setTimeout(function(){dirty=true;relayout();},250);
setTimeout(function(){dirty=true;relayout();},700);
report_();
}catch(e){root.style.opacity='1';report('folio-engdiag:throw:'+String((e&&e.message)||e).replace(/[:]/g,' ').slice(0,80)+':'+(++nonce));}
})();
"""
}
