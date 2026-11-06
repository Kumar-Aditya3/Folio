package com.folio.reader.ui.render

/** Keep Chromium's animated surfaces bounded by the viewport, even for very long chapters. */
internal object DesktopPageViewport {
    val js = """
var desktopPages=[],desktopShown=-1,desktopAnimations=[],desktopVisible=[];
function desktopStopAnimation(){
  desktopAnimations.forEach(function(a){a.cancel();});desktopAnimations=[];
}
function desktopUnpack(){
  desktopStopAnimation();
  if(growWatch)growWatch.disconnect();
  desktopPages.forEach(function(group,index){
    if(!group)return;
    while(group.firstChild){
      var child=group.firstChild;
      child.style.left=((parseFloat(child.style.left)||0)+index*vw())+'px';
      strip.appendChild(child);
    }
    group.remove();
  });
  desktopPages=[];desktopShown=-1;desktopVisible=[];
}
function desktopPack(){
  var width=vw(),height=pageH(),children=[].slice.call(strip.children);
  kids.forEach(function(child,index){child.setAttribute('data-folio-block',index);});
  children.forEach(function(child){
    var x=parseFloat(child.style.left)||0,index=Math.max(0,Math.floor((x+.5)/width));
    var group=desktopPages[index];
    if(!group){
      group=document.createElement('div');group.setAttribute('data-folio-page',index);
      group.style.cssText='position:absolute;left:0;top:0;overflow:hidden;contain:layout paint;display:none;';
      group.style.width=width+'px';group.style.height=height+'px';
      desktopPages[index]=group;strip.appendChild(group);
    }
    child.style.left=(x-index*width)+'px';group.appendChild(child);
  });
  strip.style.width=width+'px';strip.style.transform='none';strip.style.transition='none';
}
function desktopPageOf(el){
  for(var node=el;node&&node!==strip;node=node.parentElement){
    if(node.hasAttribute('data-folio-page'))return parseInt(node.getAttribute('data-folio-page'),10);
  }
  return -1;
}
function desktopTargetCopy(copies){
  for(var i=0;i<copies.length;i++){
    var node=copies[i],group=desktopPages[desktopPageOf(node)];if(!group)continue;
    var display=group.style.display,visibility=group.style.visibility;
    group.style.visibility='hidden';group.style.display='block';
    var bounds=group.getBoundingClientRect(),rect=node.getBoundingClientRect();
    group.style.display=display;group.style.visibility=visibility;
    if(rect.bottom>bounds.top&&rect.top<bounds.bottom)return node;
  }
  return copies[0]||null;
}
function desktopAnchor(){
  var group=desktopPages[page];if(!group)return '';
  var pieces=[].slice.call(group.children).sort(function(a,b){return (parseFloat(a.style.top)||0)-(parseFloat(b.style.top)||0);});
  if(!pieces.length)return '';
  var piece=pieces[0],index=parseInt(piece.getAttribute('data-folio-block'),10);
  if(isNaN(index))return '';
  var offset=piece.classList.contains('folio-slice')?-(parseFloat(piece.firstChild.style.top)||0):0;
  return 'b:'+index+':'+(offset/Math.max(1,measuredHs[index]||1)).toFixed(5);
}
function desktopRestoreBlock(index,fraction){
  var target=null,offset=fraction*Math.max(1,measuredHs[index]||1);
  desktopPages.forEach(function(group){if(!group)return;
    [].slice.call(group.children).forEach(function(piece){
      if(parseInt(piece.getAttribute('data-folio-block'),10)!==index)return;
      var start=piece.classList.contains('folio-slice')?-(parseFloat(piece.firstChild.style.top)||0):0;
      var height=parseFloat(piece.style.height)||measuredHs[index]||1;
      if(target===null||offset>=start&&offset<start+height)target=piece;
    });
  });
  if(target){page=desktopPageOf(target);posFrac=maxPage()>0?page/maxPage():0;setScroll(false);body.style.opacity='1';report();}
}
function desktopPresent(animate){
  desktopStopAnimation();
  var previous=desktopShown,current=desktopPages[page],old=desktopPages[previous];
  desktopVisible.forEach(function(group){group.style.display='none';});desktopVisible=[];
  if(current){current.style.display='block';desktopVisible.push(current);}
  desktopShown=page;
  if(!animate||!current||!old||previous===page)return;
  old.style.display='block';
  desktopVisible.push(old);
  var distance=(page>previous?1:-1)*vw();
  var timing={duration:220,easing:'cubic-bezier(.2,.7,.2,1)',fill:'both'};
  desktopAnimations=[
    current.animate([{transform:'translateX('+distance+'px)'},{transform:'translateX(0)'}],timing),
    old.animate([{transform:'translateX(0)'},{transform:'translateX('+(-distance)+'px)'}],timing)
  ];
}
function desktopFinish(){
  desktopStopAnimation();
  desktopVisible.forEach(function(group){group.style.display='none';});desktopVisible=[];
  var current=desktopPages[page];if(current){current.style.display='block';desktopVisible.push(current);}
}
"""
}
