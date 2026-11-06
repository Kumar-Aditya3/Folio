// Exercises the generated reader engine in Chromium using its native DevTools protocol.
// Called by DesktopPaginationBrowserTest; Node 22+ and a Chromium executable are sufficient.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { spawn } = require('node:child_process');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

async function main() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-render-'));
  const browser = spawn(process.argv[2], ['--headless=new', '--no-first-run', '--disable-extensions',
    '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { windowsHide: true });
  let socket;
  try {
    const endpoint = await new Promise((resolve, reject) => {
      let output = '';
      const timeout = setTimeout(() => reject(new Error(`Browser startup timeout: ${output}`)), 15000);
      browser.once('error', reject);
      browser.stderr.on('data', data => {
        output += data;
        const match = output.match(/DevTools listening on (ws:\/\/[^\s]+)/);
        if (match) { clearTimeout(timeout); resolve(match[1]); }
      });
    });
    const targets = await (await fetch(`http://${new URL(endpoint).host}/json/list`)).json();
    socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
    await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
    let serial = 0;
    const pending = new Map(), errors = [];
    socket.onmessage = event => {
      const message = JSON.parse(event.data);
      if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails);
      const call = pending.get(message.id);
      if (call) { pending.delete(message.id); message.error ? call.reject(message.error) : call.resolve(message.result); }
    };
    const send = (method, params = {}) => new Promise((resolve, reject) => {
      const id = ++serial; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params }));
    });
    const evaluate = async expression => {
      const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      assert.ok(!result.exceptionDetails, JSON.stringify(result.exceptionDetails));
      return result.result.value;
    };
    const until = async expression => {
      for (let attempt = 0; attempt < 80; attempt++) { if (await evaluate(expression)) return; await pause(50); }
      throw new Error(`Reader condition timed out: ${expression}`);
    };
    const visible = `Array.from(document.querySelectorAll('[data-folio-page]')).filter(p=>getComputedStyle(p).display!=='none')`;
    const pageNumber = `Number((${visible})[0].getAttribute('data-folio-page'))`;
    await send('Runtime.enable');
    await send('Page.enable');
    await send('Emulation.setDeviceMetricsOverride', { width: 1200, height: 800, deviceScaleFactor: 1, mobile: false });
    for (const file of process.argv.slice(3)) {
      const url = pathToFileURL(file).href;
      await send('Page.navigate', { url });
      await until(`location.href===${JSON.stringify(url)} && window.__folioLayoutReady===true && (${visible}).length===1`);
      await pause(120);
      await until(`window.__folioLayoutReady===true`);
      const count = await evaluate(`document.querySelectorAll('[data-folio-page]').length`);
      assert.ok(count > 40, `Expected a long chapter, got ${count} pages`);
      assert.equal(await evaluate(`document.getElementById('folio-strip').getBoundingClientRect().width`), 1200);
      const clippedLines = await evaluate(`(()=>{
        let clipped=[];
        for(const slice of Array.from(document.querySelectorAll('.folio-slice')).slice(0,24)){
          const group=slice.closest('[data-folio-page]'),display=group.style.display,visibility=group.style.visibility;
          group.style.visibility='hidden';group.style.display='block';
          const clip=slice.getBoundingClientRect(),range=document.createRange();range.selectNodeContents(slice.firstChild);
          for(const rect of range.getClientRects())if(rect.height>1&&
            (rect.top<clip.top-1&&rect.bottom>clip.top+1||rect.top<clip.bottom-1&&rect.bottom>clip.bottom+1))
              clipped.push({block:slice.getAttribute('data-folio-block'),offset:slice.firstChild.style.top,clip:[clip.top,clip.bottom],line:[rect.top,rect.bottom]});
          group.style.display=display;group.style.visibility=visibility;
        }
        return clipped;
      })()`);
      assert.equal(clippedLines.length, 0, 'Continuation slices must cut between text lines: '+JSON.stringify(clippedLines));
      if (await evaluate(`typeof window.__folioPaintHighlights==='function'`)) {
        assert.ok(await evaluate(`Array.from(document.querySelectorAll('[data-folio-hl="kept-passage"]')).every(m=>m.closest('[data-folio-paragraph]').getAttribute('data-folio-paragraph')==='200')`),
          'Repeated text must stay highlighted only in its source paragraph');
        assert.ok(await evaluate(`document.querySelectorAll('[data-folio-hl="kept-passage"]').length>0`));
      }
      const start = await evaluate(pageNumber);
      assert.ok(Math.abs(start / (count - 1) - .5) < .02, 'Resume fraction must survive initial pagination');
      await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 5, y: 100, button: 'left', buttons: 1, clickCount: 1 });
      await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 5, y: 100, button: 'left', buttons: 0, clickCount: 1 });
      await until(`(${visible}).length===1 && ${pageNumber}===${start-1}`);
      await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 1195, y: 100, button: 'left', buttons: 1, clickCount: 1 });
      for (const x of [1100, 1000, 900]) await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y: 100, buttons: 1 });
      await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 900, y: 100, button: 'left', buttons: 0, clickCount: 1 });
      await until(`(${visible}).length===1 && ${pageNumber}===${start}`);
      assert.ok(await evaluate(`window.getSelection().isCollapsed`), 'Gutter drags must not create a text selection');
      await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'f', code: 'KeyF', windowsVirtualKeyCode: 70 });
      await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'f', code: 'KeyF', windowsVirtualKeyCode: 70 });
      assert.match(await evaluate('document.title'), /^folio-ovl:reader:toggle-controls:/, 'F must reach the host from Chromium focus');
      await until(`window.__folioLayoutReady===true && window.__folioTurn(1)===true`);
      assert.equal(await evaluate(`(${visible}).length`), 2, 'Only incoming and outgoing surfaces paint during a turn');
      assert.ok(await evaluate(`document.getAnimations().every(a=>a.effect.target.getBoundingClientRect().width<=innerWidth)`));
      await until(`(${visible}).length===1`);
      assert.equal(await evaluate(pageNumber), start + 1);
      await evaluate(`window.__folioSeekTo('p:200')`);
      assert.ok(await evaluate(`document.getElementById('paragraph-200').getClientRects().length>0`), 'Hidden paragraph jumps must use the source paragraph index');
      await evaluate(`window.__folioSeekTo('n:passage200')`);
      const anchor = await evaluate(`window.__folioAnchorSave()`);
      assert.ok(await evaluate(`document.getElementById('paragraph-200').getClientRects().length>0`), 'Hidden paragraph jumps must reveal the destination');
      await evaluate(`window.__folioRestyle('',document.getElementById('folio-reader-style').textContent+'p{font-size:23px}')`);
      await until(`window.__folioLayoutReady===true && (${visible}).length===1`);
      assert.ok(await evaluate(`(${visible})[0].querySelector('[data-folio-block="${anchor.split(':')[1]}"]')!==null`),
        'Typography changes must keep the anchored reading block on the page');
      assert.ok(anchor.startsWith('b:'));
      await evaluate(`window.__folioReduceMotion=true;window.__folioTurn(-1)`);
      assert.equal(await evaluate(`document.getAnimations().length`), 0, 'Reduced motion must turn immediately');
      await pause(80);
      await evaluate(`window.__folioSeekTo('p:80')`);
      for (let turn = 0; turn < 3; turn++) { await evaluate(`window.__folioTurn(1)`); await pause(80); }
      const longAnchor = (await evaluate(`window.__folioAnchorSave()`)).split(':');
      assert.equal(longAnchor[1], '80', 'A continuation page must anchor to its source block');
      assert.ok(Number(longAnchor[2]) > .05, 'Continuation offsets must survive reflow');
      await evaluate(`window.__folioRestyle('',document.getElementById('folio-reader-style').textContent+'p{font-size:26px}')`);
      await until(`window.__folioLayoutReady===true && (${visible}).length===1`);
      const reflowAnchor = (await evaluate(`window.__folioAnchorSave()`)).split(':');
      assert.equal(reflowAnchor[1], longAnchor[1]);
      assert.ok(await evaluate(`(()=>{
        const original=window.__folioParagraphAt(80),group=original.closest('[data-folio-page]');
        const display=group.style.display,visibility=group.style.visibility;group.style.visibility='hidden';group.style.display='block';
        const height=original.getBoundingClientRect().height;group.style.display=display;group.style.visibility=visibility;
        const target=${Number(longAnchor[2])}*height;
        return Array.from((${visible})[0].children).some(piece=>{
          if(piece.getAttribute('data-folio-block')!=='80')return false;
          const start=piece.classList.contains('folio-slice')?-(parseFloat(piece.firstChild.style.top)||0):0;
          return target>=start-1&&target<=start+(parseFloat(piece.style.height)||height)+1;
        });
      })()`), 'Long paragraph reflow must keep its continuation visible: '+JSON.stringify({longAnchor,reflowAnchor,debug:await evaluate('window.__folioDebugRestore')}));
      await send('Emulation.setDeviceMetricsOverride', { width: 900, height: 640, deviceScaleFactor: 1, mobile: false });
      await until(`window.__folioLayoutReady===true && document.getElementById('folio-strip').getBoundingClientRect().width===900`);
      assert.equal(await evaluate(`(${visible}).length`), 1);
      await evaluate(`window.__folioSeek(1)`);
      assert.ok(await evaluate(`(${visible})[0].textContent.includes('Paragraph 239.')`),
        'The last paragraph must remain reachable: '+await evaluate(`JSON.stringify({title:document.title,page:(${visible})[0].getAttribute('data-folio-page'),text:(${visible})[0].textContent.slice(0,100),count:document.querySelectorAll('[data-folio-page]').length})`));
      await evaluate(`window.__folioSeek(0)`);
      assert.equal(await evaluate(pageNumber), 0);
      console.log(`${path.basename(file)}: ${count} pages; bounded animation, gutter click/drag, focus shortcut, resume, hidden jumps, reflow, resize, reduced motion and both edges passed`);
      await send('Emulation.setDeviceMetricsOverride', { width: 1200, height: 800, deviceScaleFactor: 1, mobile: false });
    }
    assert.deepEqual(errors, [], 'Reader scripts must not throw');
    await Promise.race([send('Browser.close'), pause(2000)]);
  } finally {
    if (socket) socket.close();
    browser.kill();
    // The owned temporary browser can take a moment to release its profile on Windows.
    for (let i = 0; i < 10; i++) {
      try { fs.rmSync(profile, { recursive: true, force: true }); break; } catch { await pause(100); }
    }
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
