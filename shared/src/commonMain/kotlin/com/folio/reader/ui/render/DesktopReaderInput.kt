package com.folio.reader.ui.render

/** Desktop gestures are deliberately separate from Android's touch page zones. */
internal object DesktopReaderInput {
    fun js(paged: Boolean = false, beforeInput: String = ""): String = """
(function(){
  var down=null,wheelTimer=0,wheelSum=0,wheelUsed=false,dragTurned=false;
  function textPath(e){var prose=(e.composedPath?e.composedPath():[e.target]).some(function(n){
    return n&&n.matches&&n.matches('p,span,h1,h2,h3,h4,h5,h6,blockquote,pre,li,td');
  });
    if(!prose||!document.caretRangeFromPoint)return prose;
    // A paragraph's padding is still part of its hit box. Test the actual glyph
    // beneath the pointer so blank gutters work without stealing clicks on prose.
    try{
      var caret=document.caretRangeFromPoint(e.clientX,e.clientY),node=caret&&caret.startContainer;
      if(!node||node.nodeType!==3)return false;
      var length=(node.textContent||'').length,at=caret.startOffset,range=document.createRange();
      for(var i=Math.max(0,at-1);i<Math.min(length,at+1);i++){
        range.setStart(node,i);range.setEnd(node,i+1);
        var rects=range.getClientRects();for(var j=0;j<rects.length;j++){
          var r=rects[j];if(e.clientX>=r.left-1&&e.clientX<=r.right+1&&e.clientY>=r.top&&e.clientY<=r.bottom)return true;
        }
      }
      return false;
    }catch(ignore){return prose;}
  }
  function gutter(x){var width=Math.max(32,window.innerWidth*.06);return x<width?-1:x>window.innerWidth-width?1:0;}
  function blocked(e){return (e.composedPath?e.composedPath():[e.target]).some(function(n){
    return n&&n.matches&&n.matches('a,button,input,textarea,select,[contenteditable],#folio-overlay-root,#folio-selbtn');
  });}
  function selected(){
    var s=window.getSelection();if(s&&!s.isCollapsed)return true;
    var roots=document.querySelectorAll('[data-folio-spine]');
    for(var i=0;i<roots.length;i++){var r=roots[i].shadowRoot;
      if(r&&r.getSelection){s=r.getSelection();if(s&&!s.isCollapsed)return true;}}
    return false;
  }
  function modified(e){return e.ctrlKey||e.metaKey||e.altKey||e.shiftKey;}
  function active(){return window.__folioInputEnabled!==false&&!document.hidden;}
  document.addEventListener('pointerdown',function(e){
    down=null;dragTurned=false;
    if(!active()||e.button!==0||e.isPrimary===false||modified(e)||blocked(e))return;
    $beforeInput
    down={x:e.clientX,y:e.clientY,t:Date.now(),id:e.pointerId,moved:false,selected:selected(),gutter:gutter(e.clientX)&&!textPath(e)};
    ${if (paged) "if(down.gutter&&!down.selected)e.preventDefault();" else ""}
  },true);
  document.addEventListener('pointermove',function(e){if(down&&Math.hypot(e.clientX-down.x,e.clientY-down.y)>8)down.moved=true;},true);
  document.addEventListener('pointercancel',function(){down=null;},true);
  ${if (paged) """
  document.addEventListener('pointerup',function(e){
    if(!active()||!down||down.id!==e.pointerId||!down.gutter||down.selected||selected()||blocked(e)||modified(e))return;
    var dx=e.clientX-down.x,dy=e.clientY-down.y;
    if(Math.abs(dx)<48||Math.abs(dx)<Math.abs(dy)*1.5)return;
    down=null;dragTurned=true;userActed=true;e.preventDefault();
    if(window.__folioTurn)window.__folioTurn(dx<0?1:-1);else goTo(page+(dx<0?1:-1));
  },true);
  """ else ""}
  document.addEventListener('click',function(e){
    var path=e.composedPath?e.composedPath():[e.target];
    var a=path.find(function(n){return n&&n.matches&&n.matches('a[href]');});
    if(a){var href=a.getAttribute('href')||'';if(href&&href.charAt(0)!=='#'){
      e.preventDefault();if(e.button===0&&!modified(e))document.title='folio-link:'+(++nonce)+':'+encodeURIComponent(href);
    }down=null;return;}
    var press=down;down=null;if(dragTurned){dragTurned=false;return;}
    if(!press||e.button!==0||e.detail>1||modified(e)||blocked(e)||press.moved||press.selected||
      Date.now()-press.t>350||Math.hypot(e.clientX-press.x,e.clientY-press.y)>8||selected())return;
    // Text clicks belong to selection. Only empty outer gutters are navigation targets.
    ${if (paged) """
    if(!active()||textPath(e))return;
    var direction=gutter(e.clientX);
    if(direction){userActed=true;if(window.__folioTurn)window.__folioTurn(direction);else goTo(page+direction);}
    """ else ""}
  },true);
  document.addEventListener('keydown',function(e){
    if(!active())return;
    if(e.key==='Escape'&&!modified(e)){
      e.preventDefault();document.title='folio-ovl:reader:close:'+(++nonce);return;
    }
    if((e.ctrlKey||e.metaKey)&&!e.altKey&&e.key.toLowerCase()==='f'){
      e.preventDefault();document.title='folio-ovl:reader:search:'+(++nonce);return;
    }
    if(blocked(e)||e.ctrlKey||e.metaKey||e.altKey)return;
    // The native Chromium child can receive keys without passing through AWT.
    if(e.key.toLowerCase()==='f'&&!e.shiftKey){
      e.preventDefault();if(!e.repeat)document.title='folio-ovl:reader:toggle-controls:'+(++nonce);return;
    }
    if(selected())return;
    if(e.shiftKey&&e.key!==' ')return;
    ${if (paged) """
    if(e.key==='Home'||e.key==='End'){
      e.preventDefault();userActed=true;goTo(e.key==='Home'?0:maxPage());return;
    }
    var direction=e.key===' '&&e.shiftKey?-1:(e.key==='ArrowRight'||e.key==='PageDown'||e.key===' '||e.key==='ArrowDown')?1:
      (e.key==='ArrowLeft'||e.key==='PageUp'||e.key==='ArrowUp')?-1:0;
    if(direction){e.preventDefault();userActed=true;if(window.__folioTurn)window.__folioTurn(direction);else goTo(page+direction);}
    """ else """
    var direction=(e.key==='ArrowRight'||e.key==='PageDown'||e.key===' '&&!e.shiftKey)?1:
      (e.key==='ArrowLeft'||e.key==='PageUp'||e.key===' '&&e.shiftKey)?-1:0;
    if(direction&&window.__folioTurn){e.preventDefault();window.__folioTurn(direction);}
    """}
  },true);
  ${if (paged) """
  window.addEventListener('wheel',function(e){
    if(!active()||blocked(e)||e.ctrlKey||e.metaKey||e.altKey||selected())return;
    e.preventDefault();clearTimeout(wheelTimer);
    wheelTimer=setTimeout(function(){wheelSum=0;wheelUsed=false;},200);
    if(wheelUsed)return;
    var d=Math.abs(e.deltaX)>Math.abs(e.deltaY)?e.deltaX:e.deltaY;
    d*=e.deltaMode===1?16:e.deltaMode===2?window.innerHeight:1;
    if(wheelSum*d<0)wheelSum=0;wheelSum+=d;
    if(Math.abs(wheelSum)>=40){userActed=true;var direction=wheelSum>0?1:-1;
      var accepted=window.__folioTurn?window.__folioTurn(direction):goTo(page+direction);wheelUsed=accepted!==false;}
  },{passive:false});
  """ else ""}
})();
"""
}
