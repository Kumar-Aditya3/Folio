package com.folio.reader.narration

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

object NarrationDom {
    val visibleScript: String = """(function(){
      var roots=[document],sections=document.querySelectorAll('section[data-folio-spine]'),best=null,score=Infinity;
      for(var i=0;i<sections.length;i++)if(sections[i].shadowRoot)roots.push(sections[i].shadowRoot);
      for(var j=0;j<roots.length;j++){var a=roots[j].querySelectorAll('[data-folio-narration]');
        for(var k=0;k<a.length;k++){var r=a[k].getBoundingClientRect();
          if(r.bottom<=0||r.top>=innerHeight||r.right<=0||r.left>=innerWidth||!r.width||!r.height)continue;
          var s=Math.max(0,r.top-innerHeight*.4,innerHeight*.4-r.bottom);
          if(s<score){var host=a[k].closest('section[data-folio-spine]')||a[k].getRootNode().host;
            best=(host?host.getAttribute('data-folio-spine'):'-1')+'|'+a[k].getAttribute('data-folio-narration');score=s;}
        }
      }return best;
    })();"""
    fun script(spine: Int?, chapterId: String?, blockId: String?, follow: Boolean): String {
        val block = Json.encodeToString(blockId.orEmpty())
        val chapter = Json.encodeToString(chapterId.orEmpty())
        return """(function(){
          var roots=[document],sections=document.querySelectorAll('section[data-folio-spine]');
          for(var i=0;i<sections.length;i++)if(sections[i].shadowRoot)roots.push(sections[i].shadowRoot);
          var target=null;
          for(var j=0;j<roots.length;j++){
            var previous=roots[j].querySelectorAll('[data-folio-speaking]');
            for(var k=0;k<previous.length;k++){
              previous[k].style.removeProperty('outline');previous[k].style.removeProperty('outline-offset');
              previous[k].removeAttribute('data-folio-speaking');
            }
            var all=roots[j].querySelectorAll('[data-folio-narration]');
            for(var n=0;n<all.length;n++)if(all[n].getAttribute('data-folio-narration')===$block){
              var scope=all[n].closest('section[data-folio-spine]');
              if(!scope&&all[n].getRootNode().host)scope=all[n].getRootNode().host;
              if(!scope||scope.getAttribute('data-folio-spine')==='${spine ?: -1}')target=all[n];
            }
          }
          if(target){target.setAttribute('data-folio-speaking','1');target.style.outline='1px solid currentColor';
            target.style.outlineOffset='3px';
            if($follow&&window.__folioSeekTo)window.__folioSeekTo('c:'+$chapter+'|n:'+$block);
          }
        })();"""
    }
}
