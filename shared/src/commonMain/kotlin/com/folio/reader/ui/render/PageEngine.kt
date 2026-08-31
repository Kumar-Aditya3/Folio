package com.folio.reader.ui.render

/**
 * Book-like pagination engine shared by the Android WebView and desktop JCEF
 * surfaces. Lays the chapter out as discrete viewport pages (one per screen in
 * PAGINATED, a two-page spread in TWO_COLUMN) by absolutely positioning content
 * blocks into page columns — deliberately NOT CSS multicol, whose overflow
 * painting is unreliable in embedded Chromium (JCEF 132). Pages turn with a
 * lightweight sheet sweep instead of scrolling, the way a physical book behaves.
 *
 * The engine talks to the app through the same document.title protocol as the
 * continuous bridge: folio-progress:<fraction>:<current>:<total>:<crossed> and
 * folio-link / folio-tap messages.
 */
object PageEngine {

    /** Readable line-width cap in px for paged modes, mirroring the Compose readerWidth breakpoints. */
    fun measurePx(textWidth: com.folio.reader.settings.TextWidth): Int = when (textWidth) {
        com.folio.reader.settings.TextWidth.NARROW -> 560
        com.folio.reader.settings.TextWidth.MEDIUM -> 720
        com.folio.reader.settings.TextWidth.WIDE -> 960
        com.folio.reader.settings.TextWidth.CUSTOM -> 1200
        com.folio.reader.settings.TextWidth.FULL -> 0
    }

    /** Pages per screen for a layout mode; 0 = continuous scroll (engine off). */
    fun colsFor(layoutMode: com.folio.reader.settings.LayoutMode): Int = when (layoutMode) {
        com.folio.reader.settings.LayoutMode.PAGINATED -> 1
        com.folio.reader.settings.LayoutMode.TWO_COLUMN -> 2
        else -> 0
    }

    /** Layout CSS for paged modes. [cols] = pages per screen (1 or 2). */
    fun css(cols: Int, marginTop: Float, marginBottom: Float, themeBg: String): String {
        // !important on geometry: publisher sheets set html/body heights that
        // collapse the page box, and the whole engine measures off clientHeight.
        return "html{height:100%!important;overflow:hidden!important;overflow-anchor:none;}" +
                "html::-webkit-scrollbar,body::-webkit-scrollbar{display:none;}" +
                "#folio-stage{position:fixed;inset:0;perspective:1600px;pointer-events:none;z-index:2147483000;}" +
                "body{height:100vh!important;overflow-x:auto!important;overflow-y:hidden!important;position:relative;opacity:0;transition:opacity .15s ease;" +
                "padding:${marginTop.toInt()}px 0 ${marginBottom.toInt()}px 0 !important;}" +
                "body img{max-width:100%;height:auto;}" +
                ".folio-sheet{position:absolute;inset:0;pointer-events:none;will-change:transform;" +
                "transition:transform .38s cubic-bezier(.4,.1,.2,1);" +
                "background:linear-gradient(to right,rgba(0,0,0,.28),rgba(0,0,0,0) 12%,rgba(0,0,0,0) 88%,rgba(0,0,0,.28));" +
                "background-color:$themeBg;}"
    }

    /**
     * Selection watcher. The Highlight action used to float beside the selection,
     * which is exactly where the OS selection toolbar lands — covered and untappable
     * on phones — and it read as a foreign element on the page. The reader's own
     * chrome owns the action now, so the page only reports the selection:
     * folio-sel:<paragraphIndex>:<encodedText>:<nonce>, or folio-selclear:<nonce>.
     * Assumes a `nonce` var in the surrounding IIFE; embedded by all three bridges
     * (paged engine, desktop continuous, Android continuous).
     */
    val selectionWatchJs: String = """
function folioReportSel(){
  var sel=window.getSelection();
  var text=sel?(sel.toString()||'').trim():'';
  if(!sel||sel.isCollapsed||text.length<3){document.title='folio-selclear:'+(++nonce);return;}
  // Read the paragraph now: a later tap collapses the selection and the anchor is gone.
  var sc0=null;try{sc0=sel.getRangeAt(0).startContainer;}catch(e){return;}
  var node=sc0?(sc0.nodeType===1?sc0:sc0.parentElement):null;
  var idx=0,ps=document.querySelectorAll('p'),i;
  if(node){
    var p=null;for(var q=node;q;q=q.parentElement){if(q.tagName==='P'){p=q;break;}}
    if(p){for(i=0;i<ps.length;i++){if(ps[i]===p){idx=i;break;}}}
    else{var kids=document.body?document.body.children:[],blk=node;
      while(blk&&blk.parentElement&&blk.parentElement!==document.body)blk=blk.parentElement;
      for(i=0;i<kids.length;i++){if(kids[i]===blk){idx=i;break;}}}
  }
  document.title='folio-sel:'+idx+':'+encodeURIComponent(text.slice(0,500))+':'+(++nonce);
}
window.__folioClearSel=function(){try{window.getSelection().removeAllRanges();}catch(e){}};
document.addEventListener('selectionchange',function(){clearTimeout(window.__folioSelT);window.__folioSelT=setTimeout(folioReportSel,220);});
"""

    /** Pager + block layout + flip + input JS. [fraction] = saved 0..1 position, [cols] = pages per screen, [measure] = readable line width cap (0 = none). */
    fun js(fraction: Float, cols: Int, gutter: Float, measure: Int): String = """
(function(){
if(window.__folioEngine)return;window.__folioEngine=true;
var COLS=$cols,G=$gutter,MEASURE=$measure,frac=$fraction;
var body=document.body,docEl=document.documentElement;
var page=0,animating=false,userActed=false,nonce=0,posFrac=frac;
var totalCols=1,kids=[],dirty=true;
function vw(){return Math.max(1, body.clientWidth || window.innerWidth);}
function colW(){return vw()/COLS;}
function pageH(){return Math.max(1, body.clientHeight);}
function maxPage(){return Math.max(0,Math.ceil(totalCols/COLS)-1);}
var strip=document.getElementById('folio-strip');
if(!strip){
  strip=document.createElement('div');strip.id='folio-strip';
  while(body.firstChild)strip.appendChild(body.firstChild);
  body.appendChild(strip);
}
function widthize(el,cw){
  el.style.position='absolute';
  el.style.boxSizing='border-box';
  el.style.width=cw+'px';
  var pad=G;
  if(MEASURE>0&&cw>MEASURE){pad=Math.floor((cw-MEASURE)/2);}
  el.style.paddingLeft=pad+'px';
  el.style.paddingRight=pad+'px';
  el.style.margin='0';
  el.style.textIndent='0';
  el.style.left='0px';
  el.style.top='0px';
}
function flatten(el,out,cw,H){
  widthize(el,cw);
  if(el.offsetHeight>H*1.5&&el.children.length>0){
    var par=el.parentNode,child;
    while(el.children.length>0){
      child=el.children[0];
      par.insertBefore(child,el);
      flatten(child,out,cw,H);
    }
    if(par)par.removeChild(el);
  } else {
    out.push(el);
  }
}
var layoutTries=0;
function layout(){
  var cw=colW(),H=pageH();
  if(H<50||cw<50){
    // A collapsed page box means every block would land in its own off-screen
    // column — the historical "blank page". Retry, then reveal + report.
    if(layoutTries++<25){setTimeout(function(){dirty=true;relayout();},150);return;}
    document.title='folio-engdiag:collapse:'+H+':'+cw+':'+(++nonce);
    body.style.opacity='1';
    return;
  }
  strip.style.position='relative';
  strip.style.height=H+'px';
  if(!kids.length){
    var seed=[].slice.call(strip.children);
    for(var s=0;s<seed.length;s++)flatten(seed[s],kids,cw,H);
  }
  if(!kids.length){totalCols=1;return;}
  var i,k,cs;
  for(i=0;i<kids.length;i++)widthize(kids[i],cw);
  var hs=[];
  for(i=0;i<kids.length;i++){
    cs=getComputedStyle(kids[i]);
    hs.push(kids[i].offsetHeight+parseFloat(cs.marginTop)+parseFloat(cs.marginBottom));
  }
  var col=0,y=0;
  for(i=0;i<kids.length;i++){
    if(y>0&&y+hs[i]>H){col++;y=0;}
    kids[i].style.left=Math.round(col*cw)+'px';
    kids[i].style.top=Math.round(y)+'px';
    y+=hs[i];
  }
  totalCols=col+1;
  strip.style.width=Math.round(totalCols*cw)+'px';
  dirty=false;
}
function report(){
  var total=maxPage()+1;
  var p=maxPage()>0?page/maxPage():0;
  document.title='folio-progress:'+p.toFixed(4)+':'+(page+1)+':'+total+':false';
}
function setScroll(){body.scrollLeft=page*vw();}
// Both edges work identically: pushing past the page you are on emits a token from
// the gesture itself. The forward hop used to ride on the progress report, which only
// reached the host if a scroll event happened to follow — and scrolling past the last
// page produces none, so it silently never registered.
var lastEdgeHop=0;
function edge(which){
  var now=Date.now();
  if(now-lastEdgeHop<600)return;
  lastEdgeHop=now;
  document.title='folio-edge:'+which+':'+(++nonce);
}
function goTo(p,instant){
  if(p<0&&page<=0){edge('start');return;}
  if(p>maxPage()&&page>=maxPage()){edge('end');return;}
  p=Math.max(0,Math.min(maxPage(),p));
  if(p===page){setScroll();report();return;}
  if(animating)return;
  var dir=p>page?1:-1;
  page=p;
  posFrac=maxPage()>0?page/maxPage():0;
  if(instant||window.matchMedia('(prefers-reduced-motion: reduce)').matches){setScroll();report();return;}
  flip(dir);
}
var stage=document.getElementById('folio-stage');
if(!stage){stage=document.createElement('div');stage.id='folio-stage';docEl.appendChild(stage);}
function flip(dir){
  animating=true;
  var sheet=document.createElement('div');sheet.className='folio-sheet';
  sheet.style.transform='translateX('+(dir>0?104:-104)+'%)';
  stage.appendChild(sheet);
  var switched=false,done=false;
  function mid(){if(switched)return;switched=true;setScroll();}
  function finish(){if(done)return;done=true;sheet.parentNode&&sheet.parentNode.removeChild(sheet);animating=false;report();}
  sheet.addEventListener('transitionend',finish);
  setTimeout(finish,650);
  requestAnimationFrame(function(){requestAnimationFrame(function(){
    sheet.style.transform='translateX(0%)';
    setTimeout(mid,180);
    setTimeout(function(){sheet.style.transform='translateX('+(dir>0?-104:104)+'%)';},200);
  });});
  report();
}
function relayout(){
  if(dirty)layout();
  page=Math.round(posFrac*maxPage());
  setScroll();
  body.style.opacity='1';
  report();
}
window.__folioRelayout=function(){dirty=true;relayout();};
window.__folioSeek=function(f){var v=Math.min(1,Math.max(0,f||0));posFrac=v;page=Math.round(v*maxPage());setScroll();body.style.opacity='1';report();};
// Target grammar: "h:<markId>[:<paragraph>[:<fraction>]]" lands on a painted
// highlight, "p:<paragraph>" on the Nth paragraph. A missing mark must never
// degrade to paragraph 0 — that reads as "the jump took me to the chapter top".
function folioTargetEl(t){
  var parts=String(t).split(':'),isH=parts[0]==='h',el=null;
  var id=isH?(parts[1]||''):'';
  if(id){try{el=document.querySelector('[data-folio-hl="'+id+'"]');}catch(e){el=null;}}
  if(!el){
    var pi=isH?parts[2]:parts[1];
    if(pi===undefined||pi==='')return null;
    var i=parseInt(pi,10);if(isNaN(i))return null;
    var ps=document.querySelectorAll('p');if(!ps.length)return null;
    el=ps[Math.min(Math.max(0,i),ps.length-1)];
  }
  return el;
}
function folioLandEl(el){
  if(dirty)layout();
  var x=el.getBoundingClientRect().left+(body.scrollLeft||0);
  var col=Math.floor((x+8)/colW());
  var y=el.getBoundingClientRect().top;
  if(y<0||y>pageH())col+=Math.floor(Math.abs(y)/pageH())*(y<0?-1:1);
  var tt=Math.min(maxPage(),Math.floor(col/COLS));
  posFrac=maxPage()>0?tt/maxPage():0;page=tt;setScroll();body.style.opacity='1';report();
}
window.__folioSeekTo=function(t){
  var parts=String(t).split(':'),isH=parts[0]==='h',f=parseFloat(isH?parts[3]:parts[2]);
  var el=folioTargetEl(t);
  if(el){folioLandEl(el);return;}
  if(!isNaN(f))window.__folioSeek(f);
};
window.__folioSeekPara=function(i){window.__folioSeekTo('p:'+i);};

window.addEventListener('resize',function(){dirty=true;relayout();});
window.addEventListener('load',function(){dirty=true;relayout();});
if(document.fonts&&document.fonts.ready)document.fonts.ready.then(function(){dirty=true;relayout();});
document.querySelectorAll('img').forEach(function(i){i.addEventListener('load',function(){dirty=true;relayout();});i.addEventListener('error',function(){dirty=true;relayout();});});
window.addEventListener('wheel',function(e){
  if(e.target&&e.target.closest&&e.target.closest('#folio-overlay-root,#folio-selbtn'))return;
  e.preventDefault();userActed=true;
  var d=Math.abs(e.deltaX)>Math.abs(e.deltaY)?e.deltaX:e.deltaY;
  if(animating)return;
  goTo(page+(d>0?1:-1));
},{passive:false});
document.addEventListener('keydown',function(e){
  var k=e.key;
  if(k==='ArrowRight'||k==='PageDown'||k===' '||k==='ArrowDown'){e.preventDefault();userActed=true;goTo(page+1);}
  else if(k==='ArrowLeft'||k==='PageUp'||k==='ArrowUp'){e.preventDefault();userActed=true;goTo(page-1);}
},true);
var tX=0,tY=0,tT=0;
document.addEventListener('touchstart',function(e){var t=e.touches[0];tX=t.clientX;tY=t.clientY;tT=Date.now();},{passive:true});
document.addEventListener('touchend',function(e){
  if(e.target&&e.target.closest&&e.target.closest('#folio-overlay-root,#folio-selbtn'))return;
  var t=e.changedTouches[0];
  var dx=t.clientX-tX,dy=t.clientY-tY;
  if(Math.abs(dx)>48&&Math.abs(dx)>Math.abs(dy)*1.4){userActed=true;goTo(page+(dx<0?1:-1));return;}
  handleTap(t.clientX,t.clientY,Math.hypot(dx,dy),Date.now()-tT);
},{passive:true});
function handleTap(x,y,moved,ms){
  var el=document.elementFromPoint(x,y);
  var a=el&&el.closest?el.closest('a[href]'):null;
  if(a){var href=a.getAttribute('href')||'';
    if(href&&href.charAt(0)!=='#'){document.title='folio-link:'+(++nonce)+':'+encodeURIComponent(href);}
    return;}
  if(moved>24||ms>350)return;
  var w=vw();
  if(x>w*0.66){userActed=true;goTo(page+1);}
  else if(x<w*0.33){userActed=true;goTo(page-1);}
  else document.title='folio-tap:'+(++nonce);
}
document.addEventListener('click',function(ev){
  var el=ev.target;
  var a=el&&el.closest?el.closest('a[href]'):null;
  if(a){var h=a.getAttribute('href')||'';if(h&&h.charAt(0)!=='#')ev.preventDefault();}
},true);
document.addEventListener('mouseup',function(e){
  if(e.button!==0)return;
  if(e.target&&e.target.closest&&e.target.closest('#folio-overlay-root,#folio-selbtn'))return;
  var sel=window.getSelection();
  if(sel&&!sel.isCollapsed)return;
  handleTap(e.clientX,e.clientY,0,0);
});
try{
layout();
$selectionWatchJs
page=Math.round(posFrac*maxPage());
setScroll();
body.style.opacity='1';
document.title='folio-engdiag:ok:'+pageH()+':'+vw()+':'+totalCols+':'+kids.length+':'+(++nonce);
setTimeout(function(){dirty=true;relayout();},250);
setTimeout(function(){dirty=true;relayout();},700);
setTimeout(function(){dirty=true;relayout();},1500);
report();
}catch(e){
body.style.opacity='1';
var m=e&&e.message?String(e.message).replace(/[:;,]/g,' ').slice(0,90):'err';
document.title='folio-engdiag:throw:'+m+':'+(++nonce);
}
})();
"""
}
