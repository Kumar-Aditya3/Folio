package com.folio.reader.ui.render

/**
 * Injected JS that reserves each image's box *before* the reader paginates, by
 * stamping intrinsic `width`/`height` attributes parsed from the file header
 * (see [com.folio.reader.epub.ImageDimensions]).
 *
 * This is the fix for the "text prints in front of the image" race and the
 * reflow jump on image decode: an `<img>` with no dimensions measures as a
 * zero-height box, so the packer (paged) or the flow (continuous) puts the
 * following text where the art will land. foliate-js and Readium rely on the
 * browser's own reservation from `width`/`height`; that only works when those
 * attributes exist, which publisher markup routinely omits — so we fill them.
 *
 * The map is `{ "<canonical epub path>": [w, h] }`. Matching tolerates the
 * WebView returning a resolved/percent-encoded `src`: it strips the synthetic
 * base and query, decodes, and falls back to a path-segment suffix match.
 * [stampJs] can be re-run after a window append/prepend via
 * `window.__folioStampImgs(moreMap)`.
 */
object ImageReserve {

    fun stampJs(dimsJson: String): String = """
(function(){
try{
  function merge(m){ if(!window.__folioDims)window.__folioDims={}; if(m){for(var k in m){if(m.hasOwnProperty(k))window.__folioDims[k]=m[k];}} }
  function strip(s){
    s=(s||'').split('#')[0].split('?')[0];
    s=s.replace(/^file:\/\/\/folio\//,'').replace(/^file:\/\//,'');
    try{s=decodeURIComponent(s);}catch(e){}
    return s;
  }
  function segEnds(long,short){
    if(long===short)return true;
    if(long.length<=short.length)return false;
    return long.slice(-short.length)===short && long.charAt(long.length-short.length-1)==='/';
  }
  function lookup(k){
    var D=window.__folioDims||{};
    if(D[k])return D[k];
    for(var kk in D){ if(!D.hasOwnProperty(kk))continue; if(segEnds(kk,k)||segEnds(k,kk))return D[kk]; }
    return null;
  }
  function stamp(img){
    if(!img||img.getAttribute('data-folio-dim'))return;
    var k=strip(img.getAttribute('src'));
    if(!k)return;
    var d=lookup(k);
    if(d&&d[0]>0&&d[1]>0){
      if(!img.getAttribute('width'))img.setAttribute('width',d[0]);
      if(!img.getAttribute('height'))img.setAttribute('height',d[1]);
      img.setAttribute('data-folio-dim','1');
    }
  }
  // --- Reflow publisher "fixed-layout" front matter ----------------------------
  // Cover, copyright, half-title, dedication and epigraph pages are frequently
  // built as ABSOLUTELY POSITIONED LAYERS: an <img> layer and a text layer placed
  // at the same coordinates. A fixed-layout viewer renders that as designed, but
  // Folio reflows to the reader's font/theme/margins, so those layers collapse
  // their section to a few px and the text prints straight over the art (the "text
  // on top of the cover / alien-script plate" report; confirmed by folio-ovl where
  // BOTH the image and the text read pos=static while the section is ~4px tall).
  // Neither the <img> nor the text leaf is itself positioned — the LAYER wrapping
  // them is — so this keys off the whole scope, not the leaf. Only short "furniture"
  // scopes are flattened; long prose chapters and their in-flow illustrations are
  // never touched, which is why big in-content images keep the publisher's layout.
  function oflow(el){
    var cs;try{cs=getComputedStyle(el);}catch(e){return false;}
    if(cs.position==='absolute'||cs.position==='fixed')return true;
    var fl=cs.cssFloat||cs.styleFloat;if(fl&&fl!=='none')return true;
    if(cs.transform&&cs.transform!=='none')return true;
    return false;
  }
  function flat(el){
    el.style.setProperty('position','static','important');
    el.style.setProperty('top','auto','important');
    el.style.setProperty('left','auto','important');
    el.style.setProperty('right','auto','important');
    el.style.setProperty('bottom','auto','important');
    el.style.setProperty('transform','none','important');
    el.style.setProperty('float','none','important');
  }
  function hitBox(a,b){return b.left<a.right-2&&b.right>a.left+2&&b.top<a.bottom-2&&b.bottom>a.top+2;}
  // Does any image box geometrically overlap a separate text box in this scope?
  // This is the mechanism-agnostic symptom: once chapters are CSS-isolated (shadow
  // DOM), the front-matter collisions are no longer caused by positioning at all
  // (imgLayer=none in folio-ovl) but by negative margins / fixed-height wrappers the
  // image overflows. Positioning checks miss those; a rect test does not.
  function scopeOverlap(scope){
    try{
      var im=scope.querySelectorAll('img'),tx=scope.querySelectorAll('p,span,div,h1,h2,h3,h4,h5,h6,li,figcaption,dd,dt'),x,y;
      for(x=0;x<im.length;x++){var ir=im[x].getBoundingClientRect();if(ir.width<8||ir.height<8)continue;
        for(y=0;y<tx.length;y++){var el=tx[y];if(el===im[x]||el.contains(im[x])||im[x].contains(el))continue;if(!(el.textContent||'').trim().length)continue;var er=el.getBoundingClientRect();if(er.width<1||er.height<1)continue;if(hitBox(ir,er))return true;}}
    }catch(e){}
    return false;
  }
  // A scope may be an Element (paged / [document] fallback: a <section> or
  // <body>) OR a ShadowRoot (continuous mode, one per chapter host). A
  // ShadowRoot has no getAttribute, so the idempotency marker + spine label
  // live on the HOST element (scope.host); content is queried on the scope
  // itself. querySelectorAll works on both, unlike getElementsByTagName which
  // a ShadowRoot does not expose.
  function deLayer(scope){
    try{
      if(!scope)return;
      var mark=(scope.host||scope);
      if(mark.getAttribute('data-folio-dl'))return;
      if(scope.querySelectorAll('img').length===0)return;
      if((scope.textContent||'').trim().length>5000)return;
      var all=scope.querySelectorAll('*'),i,any=false;
      for(i=0;i<all.length;i++){if(oflow(all[i])){any=true;break;}}
      // Positioning is only ONE cause. After CSS isolation the front-matter
      // collisions are plain geometric overlaps with no positioning at all, so also
      // act whenever an image box actually overlaps text in this scope.
      if(!any)any=scopeOverlap(scope);
      // ...and the cover section is the opposite shape: its host box COLLAPSES to a
      // few px while the jacket image (out of the host's flow) overflows down onto
      // the next chapter's copyright text. Detect that: a single content box far
      // taller than the whole section means the section is not accounting for it.
      if(!any){
        var hostH=((scope.host||scope).offsetHeight)||0,mx=0,rh;
        for(i=0;i<all.length;i++){rh=all[i].getBoundingClientRect().height;if(rh>mx)mx=rh;}
        if(mx>hostH+4)any=true;
      }
      if(!any)return;
      mark.setAttribute('data-folio-dl','1');
      // Force a clean vertical block flow: neutralise positioning/float/transform,
      // zero every margin (kills the negative margins that pull text onto the art),
      // and drop fixed heights (kills the short wrappers the image overflows) so each
      // element takes only the room its own content needs and nothing can stack on
      // the picture — whatever mechanism the publisher used.
      for(i=0;i<all.length;i++){
        var el=all[i];
        if(el.tagName==='IMG')continue;
        flat(el);
        el.style.setProperty('margin','0','important');
        el.style.setProperty('height','auto','important');
        el.style.setProperty('min-height','0','important');
        el.style.setProperty('max-height','none','important');
      }
      var im=scope.querySelectorAll('img'),j;
      for(j=0;j<im.length;j++){var g=im[j];flat(g);g.style.setProperty('display','block','important');g.style.setProperty('margin','8px auto','important');g.style.setProperty('height','auto','important');g.style.setProperty('max-width','100%','important');}
      try{var sp=mark.getAttribute&&mark.getAttribute('data-folio-spine');console.log('FOLIO-DELAYER sp='+(sp||'body')+' flattened='+all.length);}catch(e){}
    }catch(e){}
  }
  // Returns the array of per-chapter shadow roots when continuous mode has
  // installed them, else [document] (paged / non-shadow). Defined locally so it
  // never depends on global load order.
  function folioRoots(){var out=[],s=document.querySelectorAll('section[data-folio-spine]'),i,any=false;for(i=0;i<s.length;i++){if(s[i].shadowRoot){out.push(s[i].shadowRoot);any=true;}}return any?out:[document];}
  function runDeLayer(){
    var roots=folioRoots(),r;
    for(r=0;r<roots.length;r++){
      var root=roots[r];
      if(root===document){
        var secs=document.querySelectorAll('section[data-folio-spine]'),i;
        if(secs.length){for(i=0;i<secs.length;i++)deLayer(secs[i]);}
        else if(document.body)deLayer(document.body);
      }else{
        deLayer(root);
      }
    }
  }
  try{console.log('FOLIO-BUILD stamp v9');}catch(e){}
  window.__folioStampImgs=function(m){ merge(m); var roots=folioRoots(),r; for(r=0;r<roots.length;r++){var im=roots[r].querySelectorAll('img'),i;for(i=0;i<im.length;i++)stamp(im[i]);} runDeLayer(); if(window.__folioReanchor)window.__folioReanchor(); };
  window.__folioStampImgs($dimsJson);
}catch(e){}
})();
"""
}
