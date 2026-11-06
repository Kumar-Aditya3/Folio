package com.folio.reader.ui.render

import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.concurrent.TimeUnit

class DesktopReaderInputTest {
    @Test fun unsettledPaginationCannotPublishTheFirstPageAsAResumePosition() {
        val script = PageEngine.js(1f, 1, 24f, 720)
        val report = script.substring(script.indexOf("function report(){"), script.indexOf("function setScroll("))
        val restyle = script.substring(script.indexOf("window.__folioRestyle=function"), script.indexOf("window.addEventListener('resize'"))
        val checkJs = """
const vm=require('node:vm'),assert=require('node:assert/strict');
let titles=[],timers=[],restores=0;const context={DESKTOP:false,imagesSettled:true,dirty:true,page:0,posFrac:1,maxPage:()=>0,
 userActed:false,navigationVersion:0,window:{},relayout:()=>{context.dirty=false;},
 setTimeout:fn=>timers.push(fn),document:{getElementById:()=>null,set title(v){titles.push(v);}}};
context.window.__folioAnchorSave=()=> 'b:0';
context.window.__folioAnchorRestore=()=>{restores++;context.posFrac=0;};
vm.createContext(context);vm.runInContext(require('node:fs').readFileSync(0,'utf8'),context);
context.report();assert.equal(titles.length,0,'unmeasured layout must preserve the saved resume position');
context.dirty=false;context.report();assert.equal(titles.pop(),'folio-progress:1.0000:1:1:false');
context.dirty=false;context.page=60;context.maxPage=()=>60;context.report();
assert.equal(titles[0],'folio-progress:1.0000:61:61:false');
context.window.__folioRestyle('','');timers.splice(0).forEach(fn=>fn());
assert.equal(context.posFrac,1,'startup restyling must not restore a provisional first-page anchor');
assert.equal(restores,0);
context.userActed=true;context.window.__folioRestyle('','');
context.navigationVersion++;context.posFrac=0.5;timers.splice(0).forEach(fn=>fn());
assert.equal(context.posFrac,0.5,'late font callbacks must not undo newer navigation');
assert.equal(restores,0);
context.window.__folioRestyle('','');timers.splice(0).forEach(fn=>fn());
assert.equal(restores,2,'ordinary typography changes still restore the reading anchor');
"""
        val process = ProcessBuilder("node", "-e", checkJs).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(report + restyle) }
        check(process.waitFor(15, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
    }
    @Test fun bothPlatformsGenerateValidPagedAndContinuousScripts() {
        for (desktop in listOf(false, true)) {
            for (script in listOf(
                PageEngine.js(0f, 1, 24f, 720, desktopEvents = desktop),
                ContinuousEngine.js(0, 0f, desktopEvents = desktop),
            )) {
                val process = ProcessBuilder("node", "--check").redirectErrorStream(true).start()
                process.outputStream.bufferedWriter().use { it.write(script) }
                check(process.waitFor(15, TimeUnit.SECONDS)) { "Reader script syntax check timed out" }
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), "desktop=$desktop: $output")
            }
        }
    }

    @Test fun desktopGesturesKeepChromeSelectionAndPageTurnsSeparate() {
        val process = ProcessBuilder("node", "-e", harness).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(DesktopReaderInput.js(paged = true)) }
        check(process.waitFor(15, TimeUnit.SECONDS)) { "Reader input test timed out" }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
    }

    private val harness = """
const vm=require('node:vm'),assert=require('node:assert/strict');
const source=require('node:fs').readFileSync(0,'utf8');
let now=1000,id=0,selection=false,shadowSelection=false,busy=false,turns=[],titles=[],timers=new Map(),listeners={};
const plain={matches:()=>false};
const doc={querySelectorAll:()=>[{shadowRoot:{getSelection:()=>({isCollapsed:!shadowSelection})}}],
 addEventListener:(name,fn)=>(listeners[name]??=[]).push(fn)};
Object.defineProperty(doc,'title',{set:v=>titles.push(v)});
const win={innerWidth:1200,innerHeight:800,getSelection:()=>({isCollapsed:!selection}),addEventListener:doc.addEventListener};
vm.runInNewContext(source,{document:doc,window:win,nonce:0,page:5,maxPage:()=>10,userActed:false,
 goTo:p=>{if(busy)return false;turns.push(p);return true;},Date:{now:()=>now},Math,
 setTimeout:(fn,ms)=>{timers.set(++id,{fn,at:now+ms});return id;},clearTimeout:i=>timers.delete(i)});
function advance(ms){now+=ms;for(const [i,t] of [...timers])if(t.at<=now){timers.delete(i);t.fn();}}
function event(name,extra={}){const e={target:plain,button:0,detail:1,isPrimary:true,pointerId:1,
 clientX:600,clientY:400,deltaX:0,deltaY:0,deltaMode:0,key:'',preventDefault(){this.prevented=true;},...extra};
 for(const fn of listeners[name]??[])fn(e);return e;}
function click(x=600,extra={}){event('pointerdown',{clientX:x,...extra});event('click',{clientX:x,...extra});advance(250);}
for(const x of [5,600,1195])click(x);
assert.equal(titles.filter(t=>t.startsWith('folio-tap:')).length,0,'reading clicks never toggle chrome');
assert.deepEqual(turns,[4,6],'only empty outer gutters turn pages');turns.length=0;
const paragraph={matches:s=>s.includes('p,span')};
doc.caretRangeFromPoint=()=>({startContainer:{nodeType:3,textContent:'Text'},startOffset:0});
doc.createRange=()=>({setStart(){},setEnd(){},getClientRects:()=>[{left:40,right:50,top:390,bottom:420}]});
click(5,{target:paragraph});assert.deepEqual(turns,[4],'paragraph padding is a usable empty gutter');turns.length=0;
click(45,{target:paragraph});assert.deepEqual(turns,[],'glyphs inside the gutter remain selectable');
delete doc.caretRangeFromPoint;delete doc.createRange;
assert.equal(event('pointerdown',{clientX:1195}).prevented,true,'gutter drags must not begin browser text selection');
event('pointercancel');
event('pointerdown',{clientX:1195});event('pointermove',{clientX:900});event('pointerup',{clientX:900});
event('click',{clientX:900});assert.deepEqual(turns,[6],'gutter drags turn once without a compatibility click');turns.length=0;
event('pointerdown',{clientX:600});event('pointermove',{clientX:300});event('pointerup',{clientX:300});
event('click',{clientX:300});assert.deepEqual(turns,[],'dragging prose remains text selection');
let count=titles.length;
event('click');advance(250);
click(600,{button:2});click(600,{ctrlKey:true});
event('pointerdown');event('pointermove',{clientX:750});event('pointermove');event('click');advance(250);
event('pointerdown');advance(500);event('click');advance(250);
event('pointerdown');event('pointercancel');event('click');advance(250);
selection=true;click();selection=false;
shadowSelection=true;click();shadowSelection=false;
event('pointerdown');event('click');event('pointerdown');event('click',{detail:2});selection=true;advance(250);selection=false;
assert.equal(titles.length,count,'drag, long press, selection, double click and secondary clicks leave chrome alone');
const link={matches:s=>s.includes('a'),getAttribute:()=> 'chapter2.xhtml'};
click(600,{target:link});assert.equal(titles.length,++count);assert.match(titles.at(-1),/^folio-link:/);
const field={matches:s=>s.includes('input')};
click(600,{target:field});event('keydown',{key:'ArrowRight',target:field});
assert.equal(titles.length,count);assert.deepEqual(turns,[]);
for(let i=0;i<20;i++){event('wheel',{deltaY:12});advance(15);}
assert.deepEqual(turns,[6],'trackpad inertia produces one turn');
advance(250);event('wheel',{deltaY:-120});assert.deepEqual(turns,[6,4]);
event('wheel',{deltaY:120,ctrlKey:true});assert.equal(turns.length,2,'zoom is not a turn');
event('keydown',{key:'ArrowRight'});event('keydown',{key:'PageUp'});
event('keydown',{key:'ArrowRight',ctrlKey:true});assert.deepEqual(turns,[6,4,6,4]);
event('keydown',{key:'Home'});event('keydown',{key:'End'});assert.deepEqual(turns,[6,4,6,4,0,10]);
assert.equal(event('keydown',{key:'f',ctrlKey:true}).prevented,true);
assert.match(titles.at(-1),/^folio-ovl:reader:search:/);
let beforeFocus=titles.length;
assert.equal(event('keydown',{key:'f'}).prevented,true);
event('keydown',{key:'f',repeat:true});
assert.equal(titles.length,beforeFocus+1,'holding F must toggle focus mode once');
assert.match(titles.at(-1),/^folio-ovl:reader:toggle-controls:/);
event('keydown',{key:'f',target:field});
assert.equal(titles.length,beforeFocus+1,'typing F in a field must not toggle focus mode');
assert.equal(event('keydown',{key:'Escape'}).prevented,true);
assert.match(titles.at(-1),/^folio-ovl:reader:close:/);
event('keydown',{key:' ',shiftKey:true});assert.equal(turns.at(-1),4,'Shift+Space moves backwards');
advance(250);turns.length=0;busy=true;event('wheel',{deltaY:120});
assert.deepEqual(turns,[]);advance(60);busy=false;event('wheel',{deltaY:120});
assert.deepEqual(turns,[6],'a gesture rejected mid-animation can retry after the animation');
turns.length=0;win.__folioInputEnabled=false;
event('keydown',{key:'ArrowRight'});click(5);event('wheel',{deltaY:120});
assert.deepEqual(turns,[],'disabled native input cannot navigate');
win.__folioInputEnabled=true;doc.hidden=true;event('keydown',{key:'ArrowRight'});
assert.deepEqual(turns,[],'hidden documents cannot navigate');
console.log('Desktop reader input: click, selection, links, keyboard and wheel checks passed');
"""
}
