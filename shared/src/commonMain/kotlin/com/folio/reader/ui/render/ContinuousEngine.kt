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
    fun js(seedSpine: Int, seedFraction: Float, desktopEvents: Boolean): String = """
(function(){
if(window.__folioBridgeInstalled)return;window.__folioBridgeInstalled=true;
var SEED_SPINE=$seedSpine,SEED_FRAC=$seedFraction,DESKTOP=$desktopEvents;
var nonce=0,restorePending=SEED_FRAC>0.001;
function scroller(){return document.scrollingElement||document.documentElement;}
function secs(){return document.querySelectorAll('section[data-folio-spine]');}
function secBySpine(sp){var all=secs(),i;for(i=0;i<all.length;i++){if(parseInt(all[i].getAttribute('data-folio-spine'),10)===sp)return all[i];}return null;}
function secByChapter(id){var all=secs(),i;for(i=0;i<all.length;i++){if(all[i].getAttribute('data-folio-chapter')===id)return all[i];}return null;}
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
function markSection(sec){
  var s=document.createElement('section');
  s.className='folio-chapter';
  s.setAttribute('data-folio-spine',String(sec.spine));
  s.setAttribute('data-folio-chapter',String(sec.chapter));
  s.innerHTML=sec.html;
  return s;
}
// Window extension, answered by the host with append/prepend. Two viewports of
// lead keeps content under the reader's finger while the next chapter loads.
// A window may legitimately hold a SINGLE section (the seed chapter, when the
// next one failed to resolve) — the old `length<2` guard made the first forward
// extension impossible there, and the reader stuck on the title page forever.
var lastFwd=0,lastBwd=0;
function maybeExtend(){
  var all=secs();if(!all.length)return;
  var s=scroller(),st=s.scrollTop||0;
  if(Date.now()-lastFwd>600&&st+vh()*2>=topOf(all[all.length-1])+all[all.length-1].offsetHeight){
    lastFwd=Date.now();document.title='folio-extend:fwd:'+(++nonce);
  }
  if(Date.now()-lastBwd>600&&st<=vh()*2){
    lastBwd=Date.now();document.title='folio-extend:bwd:'+(++nonce);
  }
}
window.__folioAppend=function(spine,chapter,html){
  var root=document.getElementById('folio-continuous-root');if(!root)return;
  // Idempotent: a replayed op (recomposition, restored state) must not splice a
  // duplicate of a section that is already on screen.
  var old=secBySpine(spine);if(old&&old.parentNode)old.parentNode.removeChild(old);
  root.appendChild(markSection({spine:spine,chapter:chapter,html:html}));
  lastFwd=0;schedule();
};
// Prepending grows the document above the viewport, so the scroll offset is
// corrected by exactly the inserted height — twice, because late image layout
// can still shift it. A replayed prepend is a plain no-op.
window.__folioPrepend=function(spine,chapter,html){
  var root=document.getElementById('folio-continuous-root');if(!root)return;
  if(secBySpine(spine))return;
  var s=scroller(),before=s.scrollHeight;
  var el=markSection({spine:spine,chapter:chapter,html:html});
  root.insertBefore(el,root.firstChild);
  var delta=s.scrollHeight-before;
  if(delta>0){s.scrollTop+=delta;setTimeout(function(){var d2=s.scrollHeight-(before+el.offsetHeight);if(d2>0)s.scrollTop+=d2;},300);}
  lastBwd=0;schedule();
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
  var v=Math.min(1,Math.max(0,f||0)),sec=visibleSection();
  if(!sec){var s0=scroller();s0.scrollTop=Math.max(0,s0.scrollHeight-s0.clientHeight)*v;schedule();return;}
  var s=scroller(),top=topOf(sec),h=Math.max(0,sec.offsetHeight-vh());
  s.scrollTop=top+h*v;restorePending=false;schedule();
};
// Target grammar: "c:<chapterId>|<rest>" scopes the lookup to one section;
// <rest> is the usual "h:<markId>[:<para>[:<frac>]]" or "p:<para>[:<frac>]".
window.__folioSeekTo=function(t){
  var str=String(t),scope=null,rest=str;
  if(str.indexOf('c:')===0){var bar=str.indexOf('|');if(bar>0){scope=secByChapter(str.substring(2,bar));rest=str.substring(bar+1);}}
  var parts=rest.split(':'),isH=parts[0]==='h',el=null;
  var id=isH?(parts[1]||''):'';
  if(id){try{el=(scope||document).querySelector('[data-folio-hl="'+id+'"]');}catch(e){el=null;}}
  if(!el){
    var pi=isH?parts[2]:parts[1];
    if(pi===undefined||pi===''){var f0=parseFloat(isH?parts[3]:parts[2]);if(!isNaN(f0))window.__folioSeek(f0);return;}
    var i=parseInt(pi,10);if(isNaN(i))i=0;
    var ps=(scope||document).querySelectorAll('p');if(!ps.length)return;
    el=ps[Math.min(Math.max(0,i),ps.length-1)];
  }
  if(el){el.scrollIntoView({block:'start'});restorePending=false;schedule();}
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
  var ps=sec.querySelectorAll('p');if(!ps.length)return '';
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
  var ps=sec.querySelectorAll('p');if(!ps.length)return;
  var el=ps[Math.min(Math.max(0,idx),ps.length-1)];
  var s=scroller();
  s.scrollTop=Math.max(0,topOf(el)+el.offsetHeight*fr-vh()*0.5);
  restorePending=false;schedule();
};
// In-place stylesheet swap for typography/theme changes: anchor, swap, wait for
// the fonts and reflow to settle, then put the same words back under the eye.
window.__folioRestyle=function(fc,sc){
  var a=window.__folioAnchorSave();
  var f=document.getElementById('folio-fonts');if(f&&fc)f.textContent=fc;
  var st=document.getElementById('folio-reader-style');if(st)st.textContent=sc;
  var done=function(){window.__folioAnchorRestore(a);};
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
window.addEventListener('touchstart',function(e){var t=e.touches&&e.touches[0];lastY=t?t.clientY:0;},{passive:true});
window.addEventListener('touchmove',function(e){var t=e.touches&&e.touches[0];if(!t)return;var dy=t.clientY-lastY;lastY=t.clientY;if(dy>16&&atTop())edge('start');if(dy<-16&&atBottom())edge('end');restorePending=false;},{passive:true});
window.addEventListener('wheel',function(e){if(e.target&&e.target.closest&&e.target.closest('#folio-overlay-root,#folio-selbtn'))return;var d=e.deltaY||0;if(d<0&&atTop())edge('start');if(d>0&&atBottom())edge('end');restorePending=false;},{passive:true});
window.addEventListener('scroll',function(){schedule();clearTimeout(window.__folioSettleT);window.__folioSettleT=setTimeout(schedule,180);},{passive:true});
window.addEventListener('resize',schedule);
window.addEventListener('load',schedule);
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
  document.addEventListener('pointerdown',function(e){downX=e.clientX;downY=e.clientY;downT=Date.now();},true);
  document.addEventListener('pointerup',function(e){
    if(e.target&&e.target.closest&&e.target.closest('#folio-overlay-root,#folio-selbtn'))return;
    if(Date.now()-downT<350&&Math.hypot(e.clientX-downX,e.clientY-downY)<24){
      var w=Math.max(1,window.innerWidth),h=Math.max(1,window.innerHeight);
      if(e.clientX>w*0.3&&e.clientX<w*0.7&&e.clientY>h*0.25&&e.clientY<h*0.75){document.title='folio-tap:'+(++nonce);}
    }
  },true);
}
function folioReportSel(){
  var sel=window.getSelection();
  var text=sel?(sel.toString()||'').trim():'';
  if(!sel||sel.isCollapsed||text.length<3){document.title='folio-selclear:'+(++nonce);return;}
  var sc0=null;try{sc0=sel.getRangeAt(0).startContainer;}catch(e){return;}
  var node=sc0?(sc0.nodeType===1?sc0:sc0.parentElement):null;
  if(!node){document.title='folio-selclear:'+(++nonce);return;}
  // Paragraph index within its own section: sections renumber from zero, so a
  // highlight in chapter N lands on the paragraph it visually sits on.
  var sec=null;for(var q=node;q;q=q.parentElement){if(q.tagName==='SECTION'&&q.hasAttribute('data-folio-spine')){sec=q;break;}}
  var scope=sec||document.body;
  var idx=0;
  var p=null;for(var q2=node;q2;q2=q2.parentElement){if(q2.tagName==='P'){p=q2;break;}}
  var ps=scope.querySelectorAll('p'),i;
  if(p){for(i=0;i<ps.length;i++){if(ps[i]===p){idx=i;break;}}}
  else{var kids=scope.children,blk=node;while(blk&&blk.parentElement&&blk.parentElement!==scope)blk=blk.parentElement;for(i=0;i<kids.length;i++){if(kids[i]===blk){idx=i;break;}}}
  var spine=-1;if(sec){spine=parseInt(sec.getAttribute('data-folio-spine'),10);if(isNaN(spine))spine=-1;}
  document.title='folio-sel2:'+spine+':'+idx+':'+encodeURIComponent(text.slice(0,500))+':'+(++nonce);
}
window.__folioClearSel=function(){try{window.getSelection().removeAllRanges();}catch(e){}};
document.addEventListener('selectionchange',function(){clearTimeout(window.__folioSelT);window.__folioSelT=setTimeout(folioReportSel,220);});
if(window.ResizeObserver){new ResizeObserver(schedule).observe(document.documentElement);if(document.body)new ResizeObserver(schedule).observe(document.body);}
if(document.fonts&&document.fonts.ready)document.fonts.ready.then(function(){schedule();setTimeout(schedule,150);});
document.querySelectorAll('img').forEach(function(img){img.addEventListener('load',schedule);img.addEventListener('error',schedule);});
restore();schedule();setTimeout(schedule,250);setTimeout(schedule,700);setTimeout(schedule,1500);
})();
"""
}
