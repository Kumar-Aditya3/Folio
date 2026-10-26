package com.folio.reader.ui.render

/** Coordinates relative to the browser viewport, independent of platform density and reader insets. */
data class ReaderSelectionBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal data class ReaderSelectionMessage(
    val spine: Int,
    val paragraph: Int,
    val encodedText: String,
    val bounds: ReaderSelectionBounds,
)

internal fun parseReaderSelectionMessage(title: String): ReaderSelectionMessage? {
    if (!title.startsWith("folio-sel3:")) return null
    val parts = title.removePrefix("folio-sel3:").split(':', limit = 8)
    if (parts.size != 8) return null
    val spine = parts[0].toIntOrNull() ?: return null
    val paragraph = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val rect = (2..5).map { parts[it].toFloatOrNull()?.takeIf(Float::isFinite) ?: return null }
    if (rect[2] <= rect[0] || rect[3] <= rect[1]) return null
    return ReaderSelectionMessage(spine, paragraph, parts[6], ReaderSelectionBounds(rect[0], rect[1], rect[2], rect[3]))
}

internal object ReaderSelectionGeometry {
    val js = """
window.__folioAnimateSelTools=function(t,shown){
  var motion=t.__folioHost&&t.__folioHost.__folioSelectionMotion||{},duration=Math.max(0,Number(motion.duration)||0);
  var scale=Math.max(.9,Math.min(1,Number(motion.scale)||1)),previous=t.__folioAnimation;
  var style=t.ownerDocument.defaultView.getComputedStyle(t);
  var fromOpacity=previous?style.opacity:(shown?0:1),fromTransform=previous?style.transform:(shown?'scale('+scale+')':'scale(1)');
  if(previous)previous.cancel();
  var version=t.__folioAnimationVersion=(t.__folioAnimationVersion||0)+1;
  t.__folioVisible=shown;t.style.pointerEvents=shown?'auto':'none';t.style.display='flex';
  t.style.opacity=shown?'1':'0';t.style.transform=shown?'scale(1)':'scale('+scale+')';
  if(duration===0||typeof t.animate!=='function'){t.__folioAnimation=null;if(!shown)t.style.display='none';return;}
  var animation=t.animate([{opacity:fromOpacity,transform:fromTransform},
    {opacity:shown?1:0,transform:shown?'scale(1)':'scale('+scale+')'}],
    {duration:duration,easing:shown?'cubic-bezier(.2,0,0,1)':'cubic-bezier(.4,0,1,1)',fill:'both'});
  t.__folioAnimation=animation;
  animation.onfinish=function(){if(t.__folioAnimationVersion!==version)return;
    animation.cancel();t.__folioAnimation=null;if(!shown)t.style.display='none';};
};
window.__folioHideSelTools=function(){var t=window.__folioSelectionToolsRef||document.getElementById('folio-selection-tools');
  if(t&&t.__folioVisible!==false)window.__folioAnimateSelTools(t,false);};
window.folioSelectionMessage=function(spine,paragraph,text,sel,n){
  var encoded=encodeURIComponent(text);
  try{
    var range=sel.getRangeAt(0),source=range.startContainer.ownerDocument||document,sourceWindow=source.defaultView||window;
    var r=range.getBoundingClientRect(),l=r.left,t=r.top,b=r.bottom,rr=r.right,w=sourceWindow;
    var local={left:l,top:t,right:rr,bottom:b};
    while(w.parent!==w&&w.frameElement){var f=w.frameElement.getBoundingClientRect();
      var sx=f.width/Math.max(1,w.innerWidth),sy=f.height/Math.max(1,w.innerHeight);
      l=f.left+l*sx;rr=f.left+rr*sx;t=f.top+t*sy;b=f.top+b*sy;w=w.parent;}
    var W=Math.max(1,w.innerWidth),H=Math.max(1,w.innerHeight);
    if(w.__folioNativeSelectionTools){
      var tools=source.getElementById('folio-selection-tools');
      if(!tools){tools=source.createElement('div');tools.id='folio-selection-tools';
        tools.style.cssText='position:fixed;z-index:2147483646;display:flex;border-radius:10px;box-shadow:0 2px 12px #0004;padding:2px;font:13px system-ui;';
        ['Keep','Note','Export','Copy'].forEach(function(label){var btn=source.createElement('button');btn.textContent=label;
          btn.style.cssText='min-width:56px;min-height:48px;border:0;border-radius:8px;background:transparent;color:inherit;font:inherit;cursor:pointer;';
          btn.onmousedown=function(e){e.preventDefault();e.stopPropagation();};
          btn.onclick=function(e){e.preventDefault();e.stopPropagation();window.__folioHideSelTools();
            w.document.title='folio-ovl:sel:'+label.toLowerCase()+':'+Date.now();};tools.appendChild(btn);});source.documentElement.appendChild(tools);}
      window.__folioSelectionToolsRef=tools;
      tools.__folioHost=w;
      var style=sourceWindow.getComputedStyle(source.body);tools.style.background=style.backgroundColor;tools.style.color=style.color;tools.style.display='flex';
      var tw=tools.offsetWidth,th=tools.offsetHeight,vw=sourceWindow.innerWidth,vh=sourceWindow.innerHeight;
      tools.style.left=Math.max(8,Math.min(vw-tw-8,(local.left+local.right-tw)/2))+'px';
      var y=local.top-th-12;if(y<8)y=local.bottom+12;
      tools.style.top=Math.max(8,Math.min(vh-th-8,y))+'px';
      tools.style.transformOrigin='50% '+(y<local.top?'100%':'0%');
      if(tools.__folioVisible!==true)window.__folioAnimateSelTools(tools,true);
    }
    if(rr>l&&b>t)return 'folio-sel3:'+spine+':'+paragraph+':'+(l/W).toFixed(5)+':'+(t/H).toFixed(5)+':'+(rr/W).toFixed(5)+':'+(b/H).toFixed(5)+':'+encoded+':'+n;
  }catch(e){}
  return 'folio-sel2:'+spine+':'+paragraph+':'+encoded+':'+n;
};
""".trimIndent()
}
