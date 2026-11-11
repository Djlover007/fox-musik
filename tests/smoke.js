// Automatischer Kurztest der Web-Oberflaeche. Laeuft bei jedem Build vor dem APK-Bau.
// Schlaegt ein Schritt fehl, bricht der Build ab und es wird keine kaputte APK veroeffentlicht.
const path = require('path');
const zlib = require('zlib');
const { chromium } = require('playwright');

function png(r, g, b) {
  const w = 32, h = 32, row = Buffer.concat([Buffer.from([0]), Buffer.alloc(w * 3)]);
  for (let i = 0; i < w; i++) { row[1 + i * 3] = r; row[2 + i * 3] = g; row[3 + i * 3] = b; }
  const raw = Buffer.concat(Array(h).fill(row));
  const crcT = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcT[n] = c >>> 0; }
  const crc = (buf) => { let c = 0xffffffff; for (const x of buf) c = crcT[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (t, d) => { const len = Buffer.alloc(4); len.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const cr = Buffer.alloc(4); cr.writeUInt32BE(crc(td)); return Buffer.concat([len, td, cr]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

(async () => {
  const url = 'file://' + path.resolve(__dirname, '..', 'index.html');
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const p = await browser.newPage({ viewport: { width: 390, height: 844 } });
  const errs = [];
  p.on('pageerror', (e) => errs.push(e.message));
  await p.route(/^https?:\/\//, (r) => r.abort()); // kein Internet im Test: Ergebnisse bleiben gleich
  let step = '';
  const ok = (name) => console.log('  ok  ' + name);
  const check = (cond, msg) => { if (!cond) throw new Error(msg); };
  const text = async (sel) => (await p.locator(sel).first().innerText()).trim();
  const closeSheet = async () => { await p.mouse.click(195, 20); await p.waitForTimeout(350); };
  const miniColor = () => p.evaluate(async () => {
    const el = document.querySelector('#miniCov [style*="background-image"]');
    const m = el && getComputedStyle(el).backgroundImage.match(/url\("(.+)"\)/);
    if (!m) return '';
    const bm = await createImageBitmap(await (await fetch(m[1])).blob());
    const c = new OffscreenCanvas(1, 1), x = c.getContext('2d'); x.drawImage(bm, 0, 0, 1, 1);
    return Array.from(x.getImageData(0, 0, 1, 1).data.slice(0, 3)).join(',');
  });
  try {
    step = 'App startet';
    await p.goto(url); await p.waitForTimeout(2500);
    await p.evaluate(async () => {
      const sr = 8000, n = sr * 30, buf = new ArrayBuffer(44 + n * 2), v = new DataView(buf);
      const w = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
      w(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); w(8, 'WAVEfmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
      v.setUint32(24, sr, true); v.setUint32(28, sr * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true); w(36, 'data'); v.setUint32(40, n * 2, true);
      const db = await new Promise((r) => { const q = indexedDB.open('songbox'); q.onsuccess = () => r(q.result); });
      const bl = new Blob([buf], { type: 'audio/wav' });
      const list = [['a', 'Erster Titel', 'Alpha'], ['b', 'Zweiter Titel', 'Beta'], ['c', 'Dritter Titel', 'Gamma'], ['d', 'Vierter Titel', 'Delta']];
      for (const [id, t, a] of list) {
        await new Promise((r) => { const tx = db.transaction('songs', 'readwrite'); tx.objectStore('songs').put({ id, key: id, title: t, artist: a, album: 'Test', duration: 30, blob: bl, cover: null, added: Date.now() }); tx.oncomplete = r; });
      }
    });
    await p.reload(); await p.waitForTimeout(2500);
    check(await p.locator('.nb[data-n="lib"]').count() === 1, 'Navigation fehlt');
    ok(step);

    step = 'Bibliothek zeigt Titel';
    await p.click('.nb[data-n="lib"]'); await p.waitForTimeout(300);
    await p.click('[data-tab="tracks"]'); await p.waitForTimeout(300);
    check((await text('#main')).includes('Vierter Titel'), 'Titel fehlen in der Liste');
    ok(step);

    step = 'Titel abspielen';
    await p.click('[data-act="play"] >> nth=0'); await p.waitForTimeout(1500);
    const playing = await text('#miniT');
    check(playing.length > 0, 'Mini-Player zeigt keinen Titel');
    ok(step + ' (' + playing + ')');

    step = 'Eigenes Cover zweimal wechseln';
    for (const [name, rgb] of [['rot', [220, 30, 30]], ['blau', [30, 60, 220]]]) {
      await p.click('.row.now [data-act="more"], .row [data-act="more"] >> nth=0'); await p.waitForTimeout(300);
      await p.click('[data-sa="cvsearch"]'); await p.waitForSelector('[data-sa="cvfile"]', { timeout: 8000 });
      const [fc] = await Promise.all([p.waitForEvent('filechooser'), p.click('[data-sa="cvfile"]')]);
      await fc.setFiles({ name: name + '.png', mimeType: 'image/png', buffer: png(...rgb) }); await p.waitForTimeout(900);
    }
    ok(step);

    step = 'Player, Warteschlange und Tempo';
    await p.click('[data-act="openplayer"]'); await p.waitForTimeout(400);
    check(await p.locator('#pSpd').isVisible(), 'Tempo-Knopf fehlt');
    await p.click('[data-act="queue"]'); await p.waitForTimeout(400);
    check((await text('#sheet')).includes('Warteschlange'), 'Warteschlange öffnet nicht');
    await closeSheet();
    await p.evaluate(() => { const s = document.querySelector('#seek'); s.value = 500; s.dispatchEvent(new Event('input', { bubbles: true })); s.dispatchEvent(new Event('change', { bubbles: true })); });
    await p.waitForTimeout(1200); await p.click('#pPP'); await p.waitForTimeout(600);
    const pos = await text('#tCur');
    await p.click('[data-act="closeplayer"]').catch(() => {}); await p.waitForTimeout(300);
    ok(step);

    step = 'Einstellungen mit Suche';
    await p.evaluate(() => document.querySelector('#player').classList.remove('open'));
    await p.click('[data-act="settings"]'); await p.waitForTimeout(400);
    check(await p.locator('#sheet .sg h4').count() >= 5, 'Gruppen fehlen');
    await p.fill('#setq', 'party'); await p.waitForTimeout(200);
    check(await p.locator('#sheet .srow:visible').count() === 1, 'Suche filtert nicht');
    await p.fill('#setq', ''); await p.waitForTimeout(200);
    ok(step);

    step = 'Equalizer und Klang-Effekte';
    await p.click('[data-sa="eq"]'); await p.waitForTimeout(300);
    await p.click('[data-sa="fx"]'); await p.waitForTimeout(300);
    check((await text('#sheet')).includes('Raumklang'), 'Klang-Effekte fehlen');
    await p.click('[data-sa="fxsp"][data-v="1"]'); await p.waitForTimeout(300);
    await p.click('[data-sa="fxsp"][data-v="0"]'); await p.waitForTimeout(300);
    await closeSheet();
    ok(step);

    step = 'Wecker, Duplikate, Party';
    for (const [sa, word] of [['alarm', 'Wecker'], ['dups', 'Doppelte'], ['party', 'Party']]) {
      await p.click('[data-act="settings"]'); await p.waitForTimeout(350);
      await p.click('[data-sa="' + sa + '"]'); await p.waitForTimeout(350);
      check((await text('#sheet')).includes(word), sa + ' öffnet nicht');
      await closeSheet();
    }
    ok(step);

    step = 'Startseite anpassen';
    await p.click('[data-act="settings"]'); await p.waitForTimeout(350);
    await p.click('[data-sa="homeedit"]'); await p.waitForTimeout(350);
    check(await p.locator('#sheet [data-sa="homedown"]').count() >= 5, 'Liste der Bereiche fehlt');
    await p.click('#sheet [data-sa="homedown"] >> nth=0'); await p.waitForTimeout(300);
    await closeSheet();
    ok(step);

    step = 'Suche für alles';
    await p.click('[data-n="search"] >> nth=0'); await p.waitForTimeout(400);
    await p.fill('#q', 'Alpha'); await p.waitForTimeout(500);
    const found = await text('#main');
    check(found.includes('Erster Titel') && found.includes('Interpreten'), 'Suche zeigt Titel und Interpreten nicht: ' + found.slice(0, 120));
    await p.fill('#q', ''); await p.waitForTimeout(300);
    await p.click('.nb[data-n="lib"]'); await p.waitForTimeout(300);
    ok(step);

    step = 'Podcasts, Playlists, Ordner';
    for (const tab of ['podcasts', 'playlists', 'albums', 'artists', 'folders']) {
      await p.click('[data-tab="' + tab + '"]'); await p.waitForTimeout(300);
      check((await text('#main')).length > 0, 'Bereich ' + tab + ' ist leer');
    }
    ok(step);

    step = 'Nach Neustart: Titel, Stelle und Cover sind wieder da';
    await p.reload(); await p.waitForTimeout(3000);
    check(await text('#miniT') === playing, 'Letzter Titel fehlt nach Neustart');
    check(await miniColor() === '30,60,220', 'Neues Cover wurde nicht übernommen: ' + await miniColor());
    await p.click('[data-act="openplayer"]'); await p.waitForTimeout(400);
    check(await text('#tCur') === pos, 'Stelle nicht wiederhergestellt: ' + await text('#tCur') + ' statt ' + pos);
    ok(step);

    step = 'Keine Skriptfehler';
    check(errs.length === 0, errs.join(' | '));
    ok(step);
    console.log('Alle Tests bestanden.');
    await browser.close();
  } catch (e) {
    console.error('FEHLER bei „' + step + '“: ' + e.message);
    if (errs.length) console.error('Skriptfehler: ' + errs.join(' | '));
    await browser.close();
    process.exit(1);
  }
})();
