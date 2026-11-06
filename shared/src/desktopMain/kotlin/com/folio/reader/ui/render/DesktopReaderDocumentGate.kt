package com.folio.reader.ui.render

/** The CEF URL argument is source metadata, so both delivery and JS need a generation guard. */
internal class DesktopReaderDocumentGate {
    @Volatile var generation = 0L
        private set
    @Volatile var url: String? = null
        private set
    @Volatile var ready = false
        private set
    @Volatile var active = true
    @Volatile var disposed = false
        private set

    @Synchronized fun begin(url: String): Long {
        check(!disposed)
        generation++
        this.url = url
        ready = false
        return generation
    }

    @Synchronized fun invalidate() { generation++; url = null; ready = false }
    fun dispose() { disposed = true; ready = false }
    fun matches(generation: Long, browserUrl: String?): Boolean = !disposed &&
        this.generation == generation && url != null &&
        url?.substringBefore('#')?.trimEnd('/') == browserUrl?.substringBefore('#')?.trimEnd('/')

    fun message(title: String?, browserUrl: String?): String? {
        if (!matches(generation, browserUrl)) return null
        val prefix = "folio-generation:$generation|"
        return title?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    fun acknowledge(generation: Long, browserUrl: String?): Boolean {
        if (!matches(generation, browserUrl)) return false
        ready = true
        return true
    }
}

internal fun desktopReaderBootstrap(generation: Long, active: Boolean, reduced: Boolean, engine: String, url: String): String = """
(function(){
if(String(window.location.href).split('#')[0]!==${ReaderWindowAssembler.jsStringLiteral(url)}.split('#')[0])return;
(function(){
  window.__folioGeneration=$generation;
  window.__folioInputEnabled=$active;
  window.__folioReduceMotion=$reduced;
  var proto=document,descriptor;
  while(proto&&!descriptor){descriptor=Object.getOwnPropertyDescriptor(proto,'title');proto=Object.getPrototypeOf(proto);}
  if(!descriptor||!descriptor.set)throw new Error('Reader message channel unavailable');
  Object.defineProperty(document,'title',{configurable:true,get:function(){return descriptor.get.call(document);},set:function(value){
    value=String(value);if(value.indexOf('folio-progress:')===0)window.__folioLastProgress=value;
    descriptor.set.call(document,'folio-generation:$generation|'+value);
  }});
  ['pointerdown','pointerup','click','dblclick','keydown','wheel','touchstart','touchmove'].forEach(function(type){
    window.addEventListener(type,function(event){if(window.__folioInputEnabled===false){
      if(event.cancelable)event.preventDefault();event.stopImmediatePropagation();
    }},{capture:true,passive:false});
  });
})();
$engine
(function(){
  var timer=0,attempts=0,closed=false;
  function ready(){
    if(closed)return;
    if(!(window.__folioBridgeInstalled||window.__folioEngine)||window.__folioLayoutReady===false||!document.body||getComputedStyle(document.body).opacity==='0'||
      (document.fonts&&document.fonts.status!=='loaded')){
      if(++attempts>250){document.title='folio-error:Reading layout did not become ready';return;}
      timer=setTimeout(ready,40);return;
    }
    requestAnimationFrame(function(){requestAnimationFrame(function(){if(!closed)
      document.title='folio-ready:$generation|'+(window.__folioLastProgress||'');
    });});
  }
  var previousDispose=window.__folioDispose;
  window.__folioDispose=function(){closed=true;clearTimeout(timer);if(previousDispose)previousDispose();};
  ready();
})();
})();
"""
