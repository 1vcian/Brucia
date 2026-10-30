const test = require('node:test');
const assert = require('node:assert/strict');
const catalog = require('../docs/release-catalog.js');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { setImmediate: settle } = require('node:timers/promises');

function asset(name, tag = 'v0.2', host = 'github.com') {
  return { name, browser_download_url: `https://${host}/1vcian/BruciaDev/releases/download/${tag}/${name}` };
}
test('same-release assets map to the exact board and canonical T-Embed alias', () => {
  const release = catalog.parse({ tag_name: 'v0.2', assets: [
    asset('brucia-t-embed-cc1101.bin'), asset('Bruce-CYD-2432S028.bin'),
    asset('Bruce-old.bin', 'v0.1'), asset('Bruce-untrusted.bin', 'v0.2', 'example.org')
  ] });
  assert.deepEqual(Object.keys(release.boards).sort(), ['CYD-2432S028', 'lilygo-t-embed-cc1101']);
  const manifest = catalog.manifest(release, 'lilygo-t-embed-cc1101', 'ESP32-S3');
  assert.equal(manifest.version, 'v0.2');
  assert.equal(manifest.builds[0].parts[0].path, asset('brucia-t-embed-cc1101.bin').browser_download_url);
  assert.equal(catalog.manifest(release, 'missing-board', 'ESP32'), null);
});
test('unavailable or private release has no implicit historical fallback', () => {
  assert.throws(() => catalog.parse({ message: 'Not Found' }));
  const release = catalog.parse({ tag_name: 'v0.2', assets: [] });
  assert.equal(catalog.manifest(release, 'lilygo-t-embed-cc1101', 'ESP32-S3'), null);
});
test('draft, prerelease and malformed flags are rejected', () => {
  for (const flags of [{ draft: true }, { prerelease: true }, { draft: 'false' }]) {
    assert.throws(() => catalog.parse({ tag_name: 'v0.2', ...flags }));
  }
});
test('only HTTPS credential-free matching assets can be selected', () => {
  const bad = asset('Bruce-test.bin');
  const release = catalog.parse({ tag_name: 'v0.2', assets: [
    { ...bad, browser_download_url: bad.browser_download_url.replace('https:', 'http:') },
    { ...bad, browser_download_url: bad.browser_download_url.replace('github.com', 'secret@github.com') }
  ] });
  assert.deepEqual(Object.keys(release.boards), []);
});

async function page(response) {
  function element() {
    return { attributes: {}, activate: { disabled: true },
      setAttribute(name, value) { this.attributes[name] = value; },
      removeAttribute(name) { delete this.attributes[name]; },
      querySelector() { return this.activate; } };
  }
  const listeners = {};
  const selection = { selectedIndex: 0, options: [
    { value: 'lilygo-t-embed-cc1101', getAttribute() { return 'ESP32-S3'; } },
    { value: 'missing-board', getAttribute() { return 'ESP32'; } }
  ], addEventListener(name, callback) { listeners[name] = callback; } };
  const nodes = { boardSelect: selection, boardInstall: element(), quickInstall: element(),
    chipFamily: {}, cliEnv: {}, releaseStatus: {} };
  const blobs = new Map(); let counter = 0;
  class ObjectURL extends URL {
    static createObjectURL(blob) { const key = `blob:fixture-${++counter}`; blobs.set(key, blob); return key; }
    static revokeObjectURL(key) { blobs.delete(key); }
  }
  const html = fs.readFileSync(path.join(__dirname, '../docs/index.html'), 'utf8');
  const marker = html.indexOf('/* ---------- multi-board web flasher');
  const start = html.indexOf('    (function () {', marker);
  const end = html.indexOf('    })();', start) + '    })();'.length;
  assert.ok(marker >= 0 && start >= 0 && end > start);
  vm.runInNewContext(html.slice(start, end), {
    document: { getElementById(id) { return nodes[id]; } },
    window: { addEventListener(name, callback) { listeners[name] = callback; } },
    BruciaReleases: catalog, URL: ObjectURL, Blob, fetch: async () => response
  });
  await settle();
  return { nodes, selection, listeners, blobs };
}

test('actual page script leaves both install controls disabled on private/404 release', async () => {
  const { nodes } = await page({ ok: false, status: 404 });
  for (const button of [nodes.boardInstall, nodes.quickInstall]) {
    assert.equal(button.activate.disabled, true);
    assert.equal(button.attributes.manifest, undefined);
  }
  assert.match(nodes.releaseStatus.textContent, /private/);
});

test('actual page script selects exact release assets and never reuses a missing-board manifest', async () => {
  const { nodes, selection, listeners, blobs } = await page({ ok: true,
    json: async () => ({ tag_name: 'v0.2', assets: [asset('brucia-t-embed-cc1101.bin')] }) });
  assert.equal(nodes.boardInstall.activate.disabled, false);
  const url = nodes.boardInstall.attributes.manifest;
  const manifest = JSON.parse(await blobs.get(url).text());
  assert.equal(manifest.version, 'v0.2');
  assert.equal(manifest.builds[0].parts[0].path, asset('brucia-t-embed-cc1101.bin').browser_download_url);
  selection.selectedIndex = 1; listeners.change();
  assert.equal(nodes.boardInstall.activate.disabled, true);
  assert.equal(nodes.boardInstall.attributes.manifest, undefined);
  assert.equal(blobs.has(url), false);
  assert.equal(nodes.quickInstall.activate.disabled, false);
  listeners.pagehide(); assert.equal(blobs.size, 0);
  listeners.pageshow(); assert.equal(blobs.size, 1); // recreate the hero manifest after bfcache restore
});

test('asset identity is exact and duplicate canonical boards fail closed', () => {
  const good = asset('Bruce-lilygo-t-embed-cc1101.bin');
  for (const url of [good.browser_download_url.replace(good.name, 'Bruce-other.bin'),
      good.browser_download_url + '?x=1', good.browser_download_url + '#other',
      good.browser_download_url.replace('github.com', 'github.com:8443')]) {
    assert.deepEqual(Object.keys(catalog.parse({tag_name: 'v0.2', assets: [
      {...good, browser_download_url: url}]}).boards), []);
  }
  assert.throws(() => catalog.parse({tag_name: 'v0.2', assets: [good, asset('brucia-t-embed-cc1101.bin')]}));
});
