package com.folio.reader.ui.render

/**
 * Continuous-scroll bridge shared by the Android WebView and desktop JCEF
 * surfaces, replacing the per-platform inline bridges for continuous layout.
 *
 * The document may hold a *window* of chapters, each wrapped in
 * `<section data-folio-spine=… data-folio-chapter=…>`. The engine reports
 * progress *within the visible section* (so the position store, page counter and
 * bottom bar stay chapter-local), tells the host which section is visible, and
 * asks for extensions as the reader approaches either end of the window. The
 * host answers with `__folioAppend` / `__folioPrepend` DOM injections — no
 * document reload, no scroll jump — which is what makes chapters flow.
 *
 * Protocol (document.title, same channel as the paged engine):
 * - `folio-progress:<fraction>:<page>:<total>:<atEnd>:<spine>` — section-local;
 *   the paged engine omits the trailing spine part, so parsers accept both.
 * - `folio-extend:fwd|bwd:<nonce>` — the window wants to grow that way.
 * - `folio-sel2:<spine>:<paragraphIndex>:<encodedText>:<nonce>` — like
 *   folio-sel, but the paragraph index is scoped to its own section.
 */
object ContinuousEngine {

    /**
     * @param seedSpine the section the saved fraction belongs to (-1: first)
     * @param seedFraction saved 0..1 progress within that section
     * @param desktopEvents the desktop has no native tap/link detection on the
     *   browser component, so the engine provides pointer/click handlers there;
     *   Android already intercepts those natively and would double-report.
     */
    fun js(seedSpine: Int, seedFraction: Float, desktopEvents: Boolean, diag: Boolean = false, shadow: Boolean = true, shadowCss: String = "", signalPaint: Boolean = false): String = """
(function(){
if(window.__folioBridgeInstalled)return;window.__folioBridgeInstalled=true;
var SEED_SPINE=$seedSpine,SEED_FRAC=$seedFraction,DESKTOP=$desktopEvents,DIAG=$diag;
var SHADOW=$shadow;
var SIGNAL_PAINT=$signalPaint;
var SHADOW_CSS=${jsStr(shadowCss)};
var nonce=0,restorePending=SEED_FRAC>0.001;
try{console.log('FOLIO-BUILD continuous v10 seed='+SEED_SPINE+'/'+SEED_FRAC);}catch(e){}
function scroller(){return document.scrollingElement||document.documentElement;}
function secs(){return document.querySelectorAll('section[data-folio-spine]');}
function secBySpine(sp){var all=secs(),i;for(i=0;i<all.length;i++){if(parseInt(all[i].getAttribute('data-folio-spine'),10)===sp)return all[i];}return null;}
function secByChapter(id){var all=secs(),i;for(i=0;i<all.length;i++){if(all[i].getAttribute('data-folio-chapter')===id)return all[i];}return null;}
// Shadow-DOM helpers. Each chapter <section> host holds an OPEN shadowRoot with its
// content; queries route through the section's content root, and cross-root scans
// walk every host's shadowRoot. When SHADOW is off there are no shadow roots, so
// folioRoots() returns [document] and contentRoot(sec) returns sec — identical to
// the pre-shadow behaviour.
function folioRoots(){var out=[],s=document.querySelectorAll('section[data-folio-spine]'),i,any=false;for(i=0;i<s.length;i++){if(s[i].shadowRoot){out.push(s[i].shadowRoot);any=true;}}return any?out:[document];}
function contentRoot(sec){return (sec&&sec.shadowRoot)||sec;}
// One constructable stylesheet shared by every chapter's shadow root (cheap swap on
// restyle). Built once, before shadowify runs, when the platform supports it.
function initShadowSheet(){try{if(!SHADOW||window.__folioShadowSheet)return;if(typeof CSSStyleSheet!=='undefined'&&document.adoptedStyleSheets){var sh=new CSSStyleSheet();sh.replaceSync(SHADOW_CSS);window.__folioShadowSheet=sh;}}catch(e){}}
// Move a light-DOM chapter into an open shadow root: adopt the shared reader sheet
// (or inline a <style class="folio-sd-reader"> fallback), promote the inert
// type="text/folio-pub" publisher style into a live <style>, then relocate the rest
// of the content. Idempotent and guarded on data-folio-sd.
function shadowify(sec){try{if(!SHADOW||!sec||sec.shadowRoot||sec.getAttribute('data-folio-sd'))return;sec.setAttribute('data-folio-sd','1');var root=sec.attachShadow({mode:'open'});
// Move content in FIRST (promoting the inert type="text/folio-pub" publisher style into a
// live <style>) so the FXL decision below can measure real, publisher-styled boxes. The
// reader SHADOW_CSS sheet is adopted AFTER the decision, and only for reflow (non-FXL)
// sections, because it forces p/div margins & text-align that would wreck a fixed layout.
while(sec.firstChild){var ch=sec.firstChild;if(ch.nodeType===1&&ch.tagName==='STYLE'&&ch.getAttribute('type')==='text/folio-pub'){var ps=document.createElement('style');ps.textContent=ch.textContent;root.appendChild(ps);sec.removeChild(ch);}else{root.appendChild(ch);}}
// Force layout before the adopt-vs-FXL decision: reading host geometry flushes layout so
// isFxl()'s offsetHeight/getComputedStyle and pageDims()'s rects are current.
try{sec.getBoundingClientRect();var _oh=sec.offsetHeight;}catch(e){}
if(isFxl(sec,root)){fxlRender(sec,root);}
else if(window.__folioShadowSheet){try{root.adoptedStyleSheets=[window.__folioShadowSheet];}catch(e){}}
else{var st=document.createElement('style');st.className='folio-sd-reader';st.textContent=SHADOW_CSS;root.appendChild(st);}
}catch(e){}}
function shadowifyAll(){var a=secs(),i;for(i=0;i<a.length;i++)shadowify(a[i]);}
// FXL (fixed-layout / pre-paginated) support. Publisher front-matter pages (jacket art,
// copyright imprint) are built from absolutely-positioned <div class="w"> page-layers. Inside
// the shadow their host collapses to ~0px (the layers leave nothing in flow), so the next
// chapter prints over the overflowing art. Reflow repair (deLayer/repairSection) cannot fix a
// fixed page; it must be rendered faithfully as a scaled page. pageDims/isFxl/fxlRender do that.
// pageDims: the fixed page size. Authoritative from a sibling agent's data-folio-fxl="W:H" tag
// (parsed from the EPUB's per-page <meta name="viewport">); else a heuristic bounding extent of
// the root's element children measured relative to the host's top-left corner.
function pageDims(sec,root){
  var host=sec,tag=host.getAttribute('data-folio-fxl');
  if(tag){var m=/^(\d+):(\d+)${'$'}/.exec(tag);if(m)return {w:parseInt(m[1],10),h:parseInt(m[2],10)};}
  var hr=host.getBoundingClientRect(),hostLeft=hr.left,hostTop=hr.top;
  var all=root.querySelectorAll('*'),i,r,maxRight=0,maxBottom=0;
  for(i=0;i<all.length;i++){r=all[i].getBoundingClientRect();var rr=r.right-hostLeft,bb=r.bottom-hostTop;if(rr>maxRight)maxRight=rr;if(bb>maxBottom)maxBottom=bb;}
  return {w:maxRight,h:maxBottom};
}
// isFxl: tagged as FXL, OR (heuristic) the host collapses (<8px) while holding real content
// whose measured page height exceeds it, with at least one absolutely/fixed-positioned element
// in the root — the signature of the .w absolute-layer pages, detected without OPF metadata.
function isFxl(sec,root){
  try{
    var host=sec;
    if(host.getAttribute('data-folio-fxl'))return true;
    if(host.offsetHeight>=8)return false;
    var d=pageDims(sec,root);
    if(!(d.h>host.offsetHeight+8))return false;
    var all=root.querySelectorAll('*'),i,cs;
    for(i=0;i<all.length;i++){cs=getComputedStyle(all[i]);if(cs&&(cs.position==='absolute'||cs.position==='fixed'))return true;}
    return false;
  }catch(e){return false;}
}
// fxlRender: wrap the page's content in a fixed-size .folio-fxl-page scaler, scale it to fit the
// host width, and give the host a real (scaled) height so following chapters flow below it. Marks
// the host so deLayer/flatten and reflow repair leave it alone, and stores W/H for rescale.
// Idempotent: an already-wrapped page is only re-fit (scale/height), never re-wrapped.
function fxlRender(sec,root){
  try{
    var host=sec,page=root.querySelector('.folio-fxl-page'),W,H;
    if(page){
      W=parseFloat(host.getAttribute('data-folio-fxlw'))||host.clientWidth||1;
      H=parseFloat(host.getAttribute('data-folio-fxlh'))||W;
    }else{
      var d=pageDims(sec,root);W=d.w||host.clientWidth||1;H=d.h||W;
      page=document.createElement('div');page.className='folio-fxl-page';
      page.style.cssText='position:relative;width:'+W+'px;height:'+H+'px;transform-origin:top left;overflow:hidden;';
      while(root.firstChild){page.appendChild(root.firstChild);}
      root.appendChild(page);
    }
    var cw=host.clientWidth||host.getBoundingClientRect().width||W,s=cw/W;
    page.style.transform='scale('+s+')';
    host.style.setProperty('display','block','important');
    host.style.setProperty('height',Math.ceil(H*s)+'px','important');
    host.style.setProperty('overflow','hidden','important');
    host.setAttribute('data-folio-dl','1');host.setAttribute('data-folio-fxlrendered','1');
    host.setAttribute('data-folio-fxlw',W);host.setAttribute('data-folio-fxlh',H);
    try{console.log('FOLIO-FXL sp='+host.getAttribute('data-folio-spine')+' W='+W+' H='+H+' cw='+cw+' scale='+s.toFixed(3));}catch(e){}
  }catch(e){}
}
// Re-fit every rendered FXL page to the current host width (viewport resize / rotation).
window.__folioFxlRescale=function(){try{var a=secs(),i;for(i=0;i<a.length;i++){var h=a[i];if(!h.getAttribute('data-folio-fxlrendered'))continue;var root=h.shadowRoot;if(!root)continue;var page=root.querySelector('.folio-fxl-page');if(!page)continue;var W=parseFloat(h.getAttribute('data-folio-fxlw'))||1;var H=parseFloat(h.getAttribute('data-folio-fxlh'))||W;var cw=h.clientWidth||W;var s=cw/W;page.style.transform='scale('+s+')';h.style.setProperty('height',Math.ceil(H*s)+'px','important');}}catch(e){}};
function topOf(el){return el.getBoundingClientRect().top+(scroller().scrollTop||window.scrollY||0);}
function vh(){var s=scroller();return Math.max(1,s.clientHeight||window.innerHeight||1);}
// The visible section is the one the viewport's centre sits in; at the very
// bottom it is always the last, so the final chapter reports 100%.
function visibleSection(){
  var all=secs();if(!all.length)return null;
  var s=scroller(),center=(s.scrollTop||0)+vh()*0.5,i;
  for(i=all.length-1;i>=0;i--){if(topOf(all[i])<=center)return all[i];}
  return all[0];
}
function atBottom(){var s=scroller();return (s.scrollTop||0)+s.clientHeight>=s.scrollHeight-2;}
function atTop(){var s=scroller();return (s.scrollTop||0)<=1;}
// Own the scroll position across DOM growth instead of leaving it to the browser.
// Chromium SUSPENDS its scroll anchoring during an active fling, which is exactly
// when a backward window extension lands — so a fast up-scroll used to be dumped at
// the TOP of the freshly-prepended chapter ("jumps to the chapter's beginning
// instead of its end"). We turn anchoring off and re-anchor explicitly on prepend
// (below), which is deterministic and cannot double-count the way manual + browser
// anchoring once did.
function noAnchor(){try{document.documentElement.style.overflowAnchor='none';if(document.body)document.body.style.overflowAnchor='none';var r=document.getElementById('folio-continuous-root');if(r)r.style.overflowAnchor='none';}catch(e){}}
// Restore a remembered element to the viewport offset it held before content was
// inserted above it, so the reader does not move. Short-lived (200ms) so it can be
// re-fired after late stamping/de-layer/image-decode without ever fighting a later
// deliberate scroll.
window.__folioReanchor=function(){var a=window.__folioPendingAnchor;if(!a||!a.el)return;if(Date.now()-(window.__folioAnchorAt||0)>200)return;var s=scroller(),d=a.el.getBoundingClientRect().top-a.top;if(Math.abs(d)>0.5)s.scrollTop=(s.scrollTop||0)+d;};
function markSection(sec){
  var s=document.createElement('section');
  s.className='folio-chapter';
  s.setAttribute('data-folio-spine',String(sec.spine));
  s.setAttribute('data-folio-chapter',String(sec.chapter));
  s.innerHTML=sec.html;
  shadowify(s);
  return s;
}
// A publisher cover is built from absolutely positioned layers, which take the artwork and
// its title text OUT of flow. The section wrapping them then measures nothing, so the next
// chapter starts at the same y and prints its copyright block across the frontispiece (the
// `folio-ovl` report on The Sun Eater: a 318x492 image at t50 with text from t68), and the
// document's scroll height ends a page short. Only short sections are examined — that is the
// shape of page furniture (cover, half-title, copyright), and it keeps the walk off the long
// prose chapters, where nothing is positioned and the cost would be real.
function repairSection(sec){
  if(!sec)return;
  // An FXL page is rendered as a scaled fixed-size page with an explicit host height; the
  // reflow min-height repair must never fight it (mirrors the data-folio-dl deLayer guard).
  if(sec.getAttribute&&sec.getAttribute('data-folio-fxlrendered'))return;
  // Content lives in the shadow root once shadowify has run, so inspect the content
  // root (== sec when shadow is off) for size/length; geometry stays on the HOST box.
  var c=contentRoot(sec);
  var len=(c.innerHTML? c.innerHTML.length : (sec.textContent||'').length);
  if(len>6000||c.querySelectorAll('*').length>12)return;
  sec.style.minHeight='';
  var base=sec.getBoundingClientRect().top,h=sec.offsetHeight;
  var all=c.querySelectorAll('*'),i,r,max=0;
  for(i=0;i<all.length;i++){
    r=all[i].getBoundingClientRect();
    if(r.bottom-base>max){max=r.bottom-base;if(max>h+2)break;}
  }
  if(max>h+2)sec.style.minHeight=Math.ceil(max)+'px';
}
// Window extension, answered by the host with append/prepend. Three viewports of lead
// keeps content under the reader's finger while the next chapter loads — figure-per-chapter
// books cross a boundary in a single flick, and two viewports was outrun before the plate
// arrived, which is what made artwork appear one page at a time.
// A window may legitimately hold a SINGLE section (the seed chapter, when the
// next one failed to resolve) — the old `length<2` guard made the first forward
// extension impossible there, and the reader stuck on the title page forever.
var lastFwd=0,lastBwd=0,lastExtend=0;
// Remove every section carrying a given spine (defends against a duplicate the host
// may have raced in). Element.remove() never throws the "not a child" error the old
// parentNode.removeChild path hit when the tree had already moved under it.
function dropSpine(sp){var a=secs(),i;for(i=a.length-1;i>=0;i--){if(parseInt(a[i].getAttribute('data-folio-spine'),10)===sp)a[i].remove();}}
// Self-heal any duplicate-spine sections. Keep the copy that carries CONTENT, not the tall
// one: a duplicate can also be a nested pair (the host once handed over an already-wrapped
// fragment), and the outer half of a pair is always at least as tall as the inner — so a
// height contest deletes the section holding the text and leaves a zero-height shell that the
// reader can neither scroll into nor read. A nested pair is now simply left alone.
function dedupe(){
  var a=secs(),kept={},i,sp,el,c,k,removed=0;
  for(i=0;i<a.length;i++){
    el=a[i];sp=el.getAttribute('data-folio-spine');
    if(!kept[sp]){kept[sp]=el;continue;}
    if(kept[sp].contains(el)||el.contains(kept[sp]))continue;
    c=(el.textContent||'').trim().length;k=(kept[sp].textContent||'').trim().length;
    if(c>k){removed++;if(DIAG)console.log('folio-dedupe sp='+sp+' dropped h'+kept[sp].offsetHeight+' len'+(k));kept[sp].remove();kept[sp]=el;}
    else{removed++;if(DIAG)console.log('folio-dedupe sp='+sp+' dropped h'+el.offsetHeight+' len'+(c));el.remove();}
  }
}
function maybeExtend(){
  dedupe();
  var all=secs();if(!all.length)return;
  // One extension at a time, and never both directions at once. A short window of
  // tiny sections (light-novel inserts) otherwise reports at BOTH edges every frame
  // and the host ping-pongs prepend/append, thrashing the scroll position.
  if(Date.now()-lastExtend<350)return;
  var s=scroller(),st=s.scrollTop||0,lastEl=all[all.length-1];
  var endEdge=topOf(lastEl)+lastEl.offsetHeight;
  var distStart=st,distEnd=endEdge-(st+vh());
  var wantStart=distStart<=vh()*3,wantEnd=distEnd<=vh()*3;
  if(wantStart&&wantEnd){if(distStart<=distEnd)wantEnd=false;else wantStart=false;}
  if(wantStart&&Date.now()-lastBwd>500){lastBwd=lastExtend=Date.now();document.title='folio-extend:bwd:'+(++nonce);}
  else if(wantEnd&&Date.now()-lastFwd>500){lastFwd=lastExtend=Date.now();document.title='folio-extend:fwd:'+(++nonce);}
}
window.__folioAppend=function(spine,chapter,html){
  var root=document.getElementById('folio-continuous-root');if(!root)return;
  // Idempotent: drop any existing copy (replayed op / raced duplicate) before adding.
  dropSpine(spine);
  var el=markSection({spine:spine,chapter:chapter,html:html});
  root.appendChild(el);
  repairSection(el);
  lastFwd=Date.now();schedule();
};
// Prepending grows the document ABOVE the viewport. Browser scroll anchoring is
// off (noAnchor), so we hold the reader's place ourselves: remember the section
// currently at the top of the stack and its viewport offset, insert above it, then
// pull scrollTop down by however much taller the stack got — measured off the
// element, so it stays correct no matter how much the new section's images or a
// de-layer pass change its height afterwards. __folioStampImgs re-fires the
// re-anchor once it has stamped/flattened the injected chapter. A replayed prepend
// is a plain no-op.
window.__folioPrepend=function(spine,chapter,html){
  var root=document.getElementById('folio-continuous-root');if(!root)return;
  if(secBySpine(spine))return;
  var aEl=root.firstElementChild;
  window.__folioPendingAnchor=aEl?{el:aEl,top:aEl.getBoundingClientRect().top}:null;
  window.__folioAnchorAt=Date.now();
  var el=markSection({spine:spine,chapter:chapter,html:html});
  root.insertBefore(el,root.firstChild);
  repairSection(el);
  window.__folioReanchor();
  requestAnimationFrame(window.__folioReanchor);
  lastBwd=Date.now();schedule();
};
// Trim removes sections outside [from,to]; only removals above the viewport are
// compensated, so tail trims never move the page.
window.__folioTrim=function(from,to){
  var root=document.getElementById('folio-continuous-root');if(!root)return;
  var s=scroller(),all=secs(),i,removedAbove=0,viewTop=(s.scrollTop||0);
  for(i=0;i<all.length;i++){
    var sp=parseInt(all[i].getAttribute('data-folio-spine'),10);
    if(sp>=from&&sp<=to)continue;
    var t=topOf(all[i]);
    if(t<viewTop)removedAbove+=all[i].offsetHeight;
    root.removeChild(all[i]);
  }
  if(removedAbove>0)s.scrollTop=Math.max(0,(s.scrollTop||0)-removedAbove);
  schedule();
};
window.__folioSeek=function(f){
  stickClear();
  var v=Math.min(1,Math.max(0,f||0)),sec=visibleSection();
  if(!sec){var s0=scroller();s0.scrollTop=Math.max(0,s0.scrollHeight-s0.clientHeight)*v;schedule();return;}
  var s=scroller(),top=topOf(sec),h=Math.max(0,sec.offsetHeight-vh());
  s.scrollTop=top+h*v;restorePending=false;schedule();
};
// A jump's target, held while the layout settles. Right after a landing the document still
// grows under the reader — an image decodes, the real web font swaps in, repairSection raises
// a collapsed chapter — and a one-shot scrollIntoView leaves them looking at whatever now sits
// at that scroll offset. The delta is applied in VIEWPORT space (the same trick
// __folioReanchor uses) so it survives content inserted anywhere above the target.
var stick=null;
function stickClear(){stick=null;}
function stickSet(el,target){stick={el:el,top:el.getBoundingClientRect().top,target:target,until:Date.now()+2200,tries:0,stable:0,lastTop:-1,pending:0};stickArm(90);}
function stickArm(d){if(!stick||stick.pending)return;stick.pending=1;setTimeout(stickTick,d);}
function stickTick(){
  var a=stick;if(!a)return;a.pending=0;
  if(Date.now()>a.until||a.tries++>6){stickClear();return;}
  if(!a.el.isConnected){
    // The node was replaced under us (a highlight re-wrap, a window trim that dropped the
    // chapter). Re-resolve the same target once; a target that cannot be resolved anymore is
    // reported rather than quietly held to a stale position.
    var again=seekResolve(a.target);
    if(!again||!again.el){seekMiss(a.target);stickClear();return;}
    a.el=again.el;a.top=a.el.getBoundingClientRect().top;
  }
  var s=scroller(),d2=a.el.getBoundingClientRect().top-a.top;
  if(Math.abs(d2)>0.5)s.scrollTop=Math.max(0,(s.scrollTop||0)+d2);
  var y=topOf(a.el);
  if(a.lastTop>=0&&Math.abs(y-a.lastTop)<1)a.stable++;else a.stable=0;
  a.lastTop=y;
  if(a.stable>=2){stickClear();schedule();return;}
  stickArm(320);schedule();
}
// Called ONLY from layout-change hooks, never from the scroll listener: yanking the page back
// during the reader's own fling is worse than the drift this fixes.
function stickLayout(){if(stick)stickTick();}
function seekMiss(t){try{document.title='folio-seekmiss:'+(++nonce)+':'+encodeURIComponent(String(t||''));}catch(e){}}
// Target grammar: "c:<chapterId>|<rest>" scopes the lookup to one section;
// <rest> is the usual "h:<markId>[:<para>[:<frac>]]" or "p:<para>[:<frac>]".
// Returns {el}, {frac} or null — null means MISS, and a miss must never move the reader.
function seekResolve(t){
  var str=String(t),scope=null,scoped=false,rest=str;
  if(str.indexOf('c:')===0){
    var bar=str.indexOf('|');
    if(bar>0){scoped=true;scope=secByChapter(str.substring(2,bar));rest=str.substring(bar+1);}
  }
  // A scope that names no section on screen is a miss. Numbering the whole window instead is
  // what put a jump "a few chapters behind": the ordinal is relative to one chapter.
  if(scoped&&!scope)return null;
  var parts=rest.split(':'),isH=parts[0]==='h',el=null;
  var id=isH?(parts[1]||''):'';
  if(id){
    try{
      if(scope){el=contentRoot(scope).querySelector('[data-folio-hl="'+id+'"]');}
      else{var hr=folioRoots(),hi;for(hi=0;hi<hr.length&&!el;hi++){if(hr[hi].querySelector)el=hr[hi].querySelector('[data-folio-hl="'+id+'"]');}}
    }catch(e){el=null;}
    return el?{el:el}:null;
  }
  var pi=isH?parts[2]:parts[1];
  if(pi===undefined||pi===''){var f0=parseFloat(isH?parts[3]:parts[2]);return isNaN(f0)?null:{frac:f0};}
  var i=parseInt(pi,10);if(isNaN(i))return null;
  var scopeRoot=null,all=secs();
  if(scope)scopeRoot=contentRoot(scope);
  else if(all.length>1){var vis=visibleSection();if(!vis)return null;scopeRoot=contentRoot(vis);}
  if(!scopeRoot)return null;
  var ps=scopeRoot.querySelectorAll('p');
  // A section with no paragraphs at all (a page-break stub holding only a running title, which
  // some converters point every Contents entry at) has no ordinal that can ever resolve. Land
  // on the section itself rather than refusing: it is still where the book says the chapter
  // starts, and a tap that does nothing reads as a broken reader.
  if(!ps.length)return scope?{el:scope}:{el:visibleSection()};
  var clamped=(i<0||i>=ps.length);
  return {el:ps[Math.min(Math.max(0,i),ps.length-1)],clamped:clamped};
}
window.__folioSeekTo=function(t){
  var r=seekResolve(t);
  if(!r){seekMiss(t);return;}
  if(r.frac!==undefined){window.__folioSeek(r.frac);return;}
  if(r.clamped)seekMiss(t);
  stickSet(r.el,String(t));
  r.el.scrollIntoView({block:'start'});restorePending=false;schedule();
};
window.__folioSeekPara=function(i){window.__folioSeekTo('p:'+i);};
// Reflow-safe anchor: the paragraph holding the viewport's centre, plus how far
// into it the eye sits. A font change reflows the text, so a scroll FRACTION
// (or pixel offset) lands somewhere else — this anchor keeps the same words
// under the reader's eye across the swap.
//
// Both halves must work in DOCUMENT space. `cy` is a document coordinate
// (scrollTop + half a viewport), so the paragraph that holds it has to be found
// with topOf(). Comparing it against a viewport-space rect.top instead — as this
// did — made the effective target `2*scrollTop + vh/2`: the reader was thrown
// forward by their own scroll offset, and past roughly the middle of a chapter
// nothing matched at all, the fallback below picked the section's LAST
// paragraph, and a font or theme change landed at the end of the chapter. That
// is the "changing fonts / themes jumps to the end of the chapter" report, and
// it fired on theme changes too, which do not even reflow — __folioRestyle runs
// this pair unconditionally. [__folioAnchorRestore] always worked in document
// space; only this half disagreed with it.
window.__folioAnchorSave=function(){
  var s=scroller(),cy=(s.scrollTop||0)+vh()*0.5,sec=visibleSection();
  if(!sec)return '';
  var spine=parseInt(sec.getAttribute('data-folio-spine'),10);if(isNaN(spine))spine=-1;
  var ps=contentRoot(sec).querySelectorAll('p');if(!ps.length)return '';
  var i=0,el=null,top=0,h=0;
  for(i=0;i<ps.length;i++){
    top=topOf(ps[i]);h=ps[i].offsetHeight;
    if(top<=cy&&top+h>=cy){el=ps[i];break;}
    if(top>cy){el=ps[i];break;}
  }
  if(!el){el=ps[ps.length-1];i=ps.length-1;top=topOf(el);h=el.offsetHeight;}
  var fr=h>0?Math.min(1,Math.max(0,(cy-top)/h)):0;
  return spine+':'+i+':'+fr.toFixed(4);
};
window.__folioAnchorRestore=function(a){
  var p=String(a||'').split(':');if(p.length<3)return;
  var sp=parseInt(p[0],10),idx=parseInt(p[1],10),fr=parseFloat(p[2]);
  if(isNaN(sp)||isNaN(idx)||isNaN(fr))return;
  var sec=secBySpine(sp)||visibleSection();if(!sec)return;
  var ps=contentRoot(sec).querySelectorAll('p');if(!ps.length)return;
  var el=ps[Math.min(Math.max(0,idx),ps.length-1)];
  var s=scroller();
  s.scrollTop=Math.max(0,topOf(el)+el.offsetHeight*fr-vh()*0.5);
  restorePending=false;schedule();
};
// In-place stylesheet swap for typography/theme changes: anchor, swap, wait for
// the fonts and reflow to settle, then put the same words back under the eye.
window.__folioRestyle=function(fc,sc,shadowCss){
  var held=!!stick,a=held?null:window.__folioAnchorSave();
  var f=document.getElementById('folio-fonts');if(f&&fc)f.textContent=fc;
  var st=document.getElementById('folio-reader-style');if(st)st.textContent=sc;
  if(window.__folioShadowSheet&&shadowCss){try{window.__folioShadowSheet.replaceSync(shadowCss);}catch(e){}}
  else if(shadowCss){var roots=folioRoots(),i;for(i=0;i<roots.length;i++){var st2=roots[i].querySelector&&roots[i].querySelector('style.folio-sd-reader');if(st2)st2.textContent=shadowCss;}}
  // A jump still settling keeps its own target: re-arm the hold rather than running the
  // anchor dance, or the two fight over which paragraph is on screen.
  var done=function(){if(held){stickArm(90);return;}window.__folioAnchorRestore(a);};
  if(document.fonts&&document.fonts.ready)document.fonts.ready.then(function(){setTimeout(done,80);});
  setTimeout(done,120);setTimeout(done,500);
};
function restore(){
  var s=scroller();
  var sec=secBySpine(SEED_SPINE)||secs()[0];
  if(sec){var top=topOf(sec),h=Math.max(0,sec.offsetHeight-vh());s.scrollTop=top+h*SEED_FRAC;}
  else{s.scrollTop=Math.max(0,s.scrollHeight-s.clientHeight)*SEED_FRAC;}
  if((s.scrollTop||0)>0)restorePending=false;
  if(document.body)document.body.style.opacity='1';
}
${PageEngine.emptyHideJs}
function report(){
  scheduled=false;
  var now=Date.now();
  if(now-last<100){schedule();return;}
  last=now;
  var s=scroller();
  if(restorePending)restore();
  var sec=visibleSection();
  var f=0,page=1,total=1,spine=-1;
  if(sec){
    spine=parseInt(sec.getAttribute('data-folio-spine'),10);
    if(isNaN(spine))spine=-1;
    var top=topOf(sec),h=Math.max(1,sec.offsetHeight),center=(s.scrollTop||0)+vh()*0.5;
    f=Math.min(1,Math.max(0,(center-top)/h));
    total=Math.max(1,Math.ceil(h/vh()));
    page=Math.min(total,Math.max(1,Math.floor((center-top)/vh())+1));
  }else{
    var docH=s.scrollHeight,range=Math.max(0,docH-vh());
    f=range>0?Math.min(1,(s.scrollTop||0)/range):0;
    total=Math.max(1,Math.ceil(docH/vh()));
    page=Math.min(total,Math.floor((s.scrollTop||0)/vh())+1);
  }
  if(atBottom()){
    var all=secs();
    if(all.length){
      var sp2=parseInt(all[all.length-1].getAttribute('data-folio-spine'),10);
      if(!isNaN(sp2))spine=sp2;
    }
    f=1;
  }
  var sig=f.toFixed(3)+':'+page+':'+total+':'+spine;
  if(sig!==lastSig){lastSig=sig;document.title='folio-progress:'+f.toFixed(4)+':'+page+':'+total+':'+false+':'+spine;}
  maybeExtend();
}
var scheduled=false,last=0,lastSig='',maxTotal=1;
function schedule(){if(!scheduled){scheduled=true;requestAnimationFrame(report);}}
var lastEdgeHop=0,lastY=0;
function edge(which){
  var now=Date.now();
  if(now-lastEdgeHop<600)return;
  lastEdgeHop=now;
  document.title='folio-edge:'+which+':'+(++nonce);
}
window.addEventListener('touchstart',function(e){stickClear();var t=e.touches&&e.touches[0];lastY=t?t.clientY:0;},{passive:true});
window.addEventListener('touchmove',function(e){stickClear();var t=e.touches&&e.touches[0];if(!t)return;var dy=t.clientY-lastY;lastY=t.clientY;if(dy>16&&atTop())edge('start');if(dy<-16&&atBottom())edge('end');restorePending=false;},{passive:true});
window.addEventListener('wheel',function(e){stickClear();if(e.target&&e.target.closest&&e.target.closest('#folio-overlay-root,#folio-selbtn'))return;var d=e.deltaY||0;if(d<0&&atTop())edge('start');if(d>0&&atBottom())edge('end');restorePending=false;},{passive:true});
window.addEventListener('scroll',function(){schedule();clearTimeout(window.__folioSettleT);window.__folioSettleT=setTimeout(schedule,180);},{passive:true});
window.addEventListener('resize',schedule);
window.addEventListener('load',schedule);
// Growing the window is only ever asked for by report(), and report() only runs on a scroll,
// resize or image event. A reader pinned at the end of the loaded window produces none of those
// — pushing further cannot scroll anything — so one dropped extend request strands them at the
// boundary for the rest of the session. The paged engine already handled its equivalent by
// emitting the hop from the gesture rather than the report; this is the window's version of it.
var edgeCount=0,edgeTries=0;
setInterval(function(){
  // Position-only check first: the interval must not touch the DOM while the reader is mid-page.
  if(!atBottom()&&!atTop()){edgeCount=0;edgeTries=0;return;}
  var n=secs().length;if(!n)return;
  // The count moving means the host answered: start counting again.
  if(n!==edgeCount){edgeCount=n;edgeTries=0;}
  if(edgeTries++>3)return;
  maybeExtend();
},400);
if(DESKTOP){
  document.addEventListener('click',function(ev){
    var el=ev.target;
    var a=(el&&el.closest)?el.closest('a[href]'):null;
    if(a){
      var href=a.getAttribute('href')||'';
      if(href&&href.charAt(0)!=='#'){ev.preventDefault();document.title='folio-link:'+(++nonce)+':'+encodeURIComponent(href);}
    }
  },true);
  var downX=0,downY=0,downT=0;
  document.addEventListener('pointerdown',function(e){stickClear();downX=e.clientX;downY=e.clientY;downT=Date.now();},true);
  document.addEventListener('pointerup',function(e){
    if(e.target&&e.target.closest&&e.target.closest('#folio-overlay-root,#folio-selbtn'))return;
    if(Date.now()-downT<350&&Math.hypot(e.clientX-downX,e.clientY-downY)<24){
      var w=Math.max(1,window.innerWidth),h=Math.max(1,window.innerHeight);
      if(e.clientX>w*0.3&&e.clientX<w*0.7&&e.clientY>h*0.25&&e.clientY<h*0.75){document.title='folio-tap:'+(++nonce);}
    }
  },true);
}
function folioReportSel(){
  // Shadow-aware: window.getSelection() does not reach inside a shadow root, so each
  // chapter host is asked for its own shadowRoot.getSelection() (supported on the
  // Chromium WebView); the first non-collapsed one wins. Non-shadow falls back to the
  // window selection, and the section is then found by walking up from the node.
  var sel=null,sec=null;
  if(SHADOW){
    var sa=secs(),si;
    for(si=0;si<sa.length;si++){
      try{
        if(sa[si].shadowRoot&&sa[si].shadowRoot.getSelection){
          var ss=sa[si].shadowRoot.getSelection();
          if(ss&&!ss.isCollapsed&&(ss.toString()||'').trim().length>=3){sel=ss;sec=sa[si];break;}
        }
      }catch(e){}
    }
  }
  if(!sel)sel=window.getSelection();
  var text=sel?(sel.toString()||'').trim():'';
  if(!sel||sel.isCollapsed||text.length<3){document.title='folio-selclear:'+(++nonce);return;}
  var sc0=null;try{sc0=sel.getRangeAt(0).startContainer;}catch(e){return;}
  var node=sc0?(sc0.nodeType===1?sc0:sc0.parentElement):null;
  if(!node){document.title='folio-selclear:'+(++nonce);return;}
  // Paragraph index within its own section: sections renumber from zero, so a
  // highlight in chapter N lands on the paragraph it visually sits on.
  if(!sec){for(var q=node;q;q=q.parentElement){if(q.tagName==='SECTION'&&q.hasAttribute('data-folio-spine')){sec=q;break;}}}
  var scope=sec?contentRoot(sec):document.body;
  var idx=0;
  var p=null;for(var q2=node;q2;q2=q2.parentElement){if(q2.tagName==='P'){p=q2;break;}}
  var ps=scope.querySelectorAll('p'),i;
  if(p){for(i=0;i<ps.length;i++){if(ps[i]===p){idx=i;break;}}}
  else{var kids=scope.children||[],blk=node;while(blk&&blk.parentElement&&blk.parentElement!==scope)blk=blk.parentElement;for(i=0;i<kids.length;i++){if(kids[i]===blk){idx=i;break;}}}
  var spine=-1;if(sec){spine=parseInt(sec.getAttribute('data-folio-spine'),10);if(isNaN(spine))spine=-1;}
  document.title='folio-sel2:'+spine+':'+idx+':'+encodeURIComponent(text.slice(0,500))+':'+(++nonce);
}
window.__folioClearSel=function(){try{window.getSelection().removeAllRanges();}catch(e){}var a=secs(),i;for(i=0;i<a.length;i++){try{if(a[i].shadowRoot&&a[i].shadowRoot.getSelection)a[i].shadowRoot.getSelection().removeAllRanges();}catch(e){}}};
document.addEventListener('selectionchange',function(){clearTimeout(window.__folioSelT);window.__folioSelT=setTimeout(folioReportSel,220);});
if(window.ResizeObserver){var _folioRO=function(){try{window.__folioFxlRescale();}catch(e){}stickLayout();schedule();};new ResizeObserver(_folioRO).observe(document.documentElement);if(document.body)new ResizeObserver(_folioRO).observe(document.body);}
if(document.fonts&&document.fonts.ready)document.fonts.ready.then(function(){stickLayout();schedule();setTimeout(function(){stickLayout();schedule();},150);});
function sectionOf(el){if(!el)return null;var s=el.closest?el.closest('section[data-folio-spine]'):null;if(s)return s;var rn=el.getRootNode&&el.getRootNode(),host=rn&&rn.host;return (host&&host.closest)?host.closest('section[data-folio-spine]'):null;}
secs().forEach(repairSection);
(function(){var _r=folioRoots(),_i;for(_i=0;_i<_r.length;_i++){_r[_i].querySelectorAll('img').forEach(function(img){
  // The cover's art decodes after this script runs, and the collapsed section it leaves
  // behind is only measurable once the image has a size. Attaching per shadow root
  // reaches images that were moved out of light DOM into a chapter's shadow tree.
  img.addEventListener('load',function(){var hh=sectionOf(img);repairSection(hh);if(hh&&hh.shadowRoot&&!hh.getAttribute('data-folio-fxlrendered')&&isFxl(hh,hh.shadowRoot))fxlRender(hh,hh.shadowRoot);stickLayout();schedule();});
  img.addEventListener('error',function(){stickLayout();schedule();});
});}})();
// Diagnostics only (READER_DEBUG_LOG): measure section/image geometry and detect any image box
// that overlaps a text paragraph, so the continuous-mode "text on top of art" report can be read
// off logcat (folio-cgeom / folio-ovl) instead of guessed at.
// Report the nearest ancestor that is out of normal flow (this is the real "layer"
// that drags the art and its caption onto each other), so the log names the exact
// element/CSS to neutralise instead of us guessing the mechanism.
function folioLayer(el){
  try{for(var n=el&&el.parentElement;n;n=n.parentElement){
    var cs=getComputedStyle(n),fl=cs.cssFloat||cs.styleFloat;
    if(cs.position==='absolute'||cs.position==='fixed'||(fl&&fl!=='none')||(cs.transform&&cs.transform!=='none'))
      return n.tagName+'.'+(n.className||'')+'{pos='+cs.position+' float='+fl+' tf='+(cs.transform==='none'?'0':'1')+' dl='+(n.getAttribute('data-folio-dl')||'0')+'}';
    if(n.tagName==='SECTION')break;
  }}catch(e){}
  return 'none';
}
function folioDiag(tag){
  if(!DIAG)return;
  try{
    var secs=document.querySelectorAll('section[data-folio-spine]'),msg='folio-cgeom['+tag+'] secs='+secs.length;
    for(var i=0;i<secs.length;i++){var r=secs[i].getBoundingClientRect();var cr=contentRoot(secs[i]);var kid=(cr&&cr.firstElementChild)||secs[i].firstElementChild;var clen=(cr&&cr!==secs[i])?cr.querySelectorAll('*').length:secs[i].innerHTML.length;var cim=(cr||secs[i]).querySelectorAll('img').length;msg+=' [sp'+secs[i].getAttribute('data-folio-spine')+' t'+Math.round(r.top)+' h'+Math.round(r.height)+' len'+clen+' imgs'+cim+' kid'+(kid?kid.tagName:'-')+']';}
    // Gather images/paragraphs across every content root (shadow roots in
    // continuous mode, else the document) so the overlap probe still sees content.
    var imgs=[],ps=[],_R=folioRoots(),_ri;for(_ri=0;_ri<_R.length;_ri++){var _rr=_R[_ri];var _im=_rr.querySelectorAll('img'),_p=_rr.querySelectorAll('p'),_q;for(_q=0;_q<_im.length;_q++)imgs.push(_im[_q]);for(_q=0;_q<_p.length;_q++)ps.push(_p[_q]);}
    var ov=0;
    for(var k=0;k<imgs.length;k++){var a=imgs[k].getBoundingClientRect();if(a.width<2||a.height<2)continue;
      var cs=getComputedStyle(imgs[k]);
      for(var j=0;j<ps.length;j++){if(imgs[k].contains(ps[j])||ps[j].contains(imgs[k]))continue;
        var b=ps[j].getBoundingClientRect();
        var oy=Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top),ox=Math.min(a.right,b.right)-Math.max(a.left,b.left);
        if(oy>4&&ox>6){ov++;if(ov<=6)console.log('folio-ovl['+tag+'] imgNat='+imgs[k].naturalWidth+'x'+imgs[k].naturalHeight+' box='+Math.round(a.width)+'x'+Math.round(a.height)+' cssH='+cs.height+' maxH='+cs.maxHeight+' float='+cs.cssFloat+' pos='+cs.position+' par='+(imgs[k].parentElement?imgs[k].parentElement.tagName+'.'+(imgs[k].parentElement.className||''):'-')+' imgLayer='+folioLayer(imgs[k])+' txtLayer='+folioLayer(ps[j])+' imgT='+Math.round(a.top)+' txtT='+Math.round(b.top)+' "'+(ps[j].textContent||'').trim().slice(0,28)+'"');}
      }
    }
    console.log(msg+' overlaps='+ov+' complete='+(function(){var n=0,t=0,q;for(q=0;q<imgs.length;q++){t++;if(!imgs[q].complete)n++;}return t+'-'+n+'pending';})());
    // Per-image dump: distinguishes a broken load (nat=0x0) from a CSS collapse
    // (nat=WxH but box h=0) for the "can't scroll to/past long image" sections.
    var im2=imgs,dump='folio-img['+tag+']';
    for(var m=0;m<im2.length;m++){var rr=im2[m].getBoundingClientRect(),c2=getComputedStyle(im2[m]);var sc=im2[m].closest?im2[m].closest('section[data-folio-spine]'):null;if(!sc){var rn=im2[m].getRootNode&&im2[m].getRootNode();sc=(rn&&rn.host)||null;}
      dump+=' {sp'+(sc?sc.getAttribute('data-folio-spine'):'?')+' nat'+im2[m].naturalWidth+'x'+im2[m].naturalHeight+' box'+Math.round(rr.width)+'x'+Math.round(rr.height)+' cH'+c2.height+' mH'+c2.maxHeight+' disp'+c2.display+' cmpl'+im2[m].complete+'}';}
    console.log(dump);
  }catch(e){console.log('folio-diag-err '+e);}
}
if(DIAG){document.addEventListener('load',function(e){if(e.target&&e.target.tagName==='IMG')folioDiag('imgload');},true);}
noAnchor();initShadowSheet();shadowifyAll();
// Post-shadow remediation MUST run after shadowify turns each chapter's publisher
// CSS on: the earlier repairSection/stamp passes ran while that CSS was still inert,
// so they saw neither the collapsed cover section nor the overlap. Re-run image
// stamping (de-layer) + section repair now, and again as fonts/images settle.
function folioPostShadow(){try{if(window.__folioStampImgs)window.__folioStampImgs();secs().forEach(repairSection);}catch(e){}}
folioPostShadow();restore();schedule();setTimeout(function(){folioPostShadow();stickLayout();schedule();},150);setTimeout(function(){window.__folioFxlRescale();stickLayout();schedule();},250);setTimeout(function(){folioPostShadow();stickLayout();schedule();},700);setTimeout(stickLayout,1200);setTimeout(schedule,1500);
// Reflowable-document first-paint signal. A non-windowed document (DOCX/HTML) keeps its
// host cover held until the text is genuinely stable. restore() has already revealed the
// body, but it does so UNDER the opaque host cover, so the raw fallback-font frame and the
// web-font swap reflow are both hidden; only once document.fonts.ready has settled do we
// tell the host to dissolve the cover — onto final, non-reflowing text. Sent through the
// same document.title channel as every other signal, with a timeout backstop in case
// fonts.ready never resolves (a document with no web font). Windowed EPUB leaves
// SIGNAL_PAINT off and keeps paint-on-finish.
if(SIGNAL_PAINT){var __folioPainted=function(){try{document.title='folio-painted:'+(++nonce);}catch(e){}};if(document.fonts&&document.fonts.ready){document.fonts.ready.then(function(){requestAnimationFrame(__folioPainted);});}else{requestAnimationFrame(__folioPainted);}setTimeout(__folioPainted,1500);}
if(DIAG){setTimeout(function(){folioDiag('t300');},300);setTimeout(function(){folioDiag('t1500');},1500);setTimeout(function(){folioDiag('t3000');},3000);}
})();
"""

    /** Escapes a Kotlin string into a JS double-quoted string literal for inlining in the IIFE. */
    private fun jsStr(s: String) = "\"" + s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ").replace("\r"," ") + "\""
}
