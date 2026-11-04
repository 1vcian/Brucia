const test = require('node:test');
const assert = require('node:assert/strict');
const catalog = require('../docs/release-catalog.js');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { setImmediate: settle } = require('node:timers/promises');

// SHA-256 runs on the libuv thread pool, so its completion is not bound to a fixed number of
// event-loop turns (it slows down under CPU load). Track pending digests and wait for them.
const pendingDigests = new Set();
const trackedCrypto = { subtle: { digest(...args) {
  const p = globalThis.crypto.subtle.digest(...args);
  pendingDigests.add(p); p.then(() => pendingDigests.delete(p), () => pendingDigests.delete(p));
  return p;
} } };
async function idle() {
  for (let round = 0; round < 100; round++) {
    await Promise.allSettled([...pendingDigests]);
    for (let i = 0; i < 5; i++) await settle();
    if (pendingDigests.size === 0) return;
  }
  throw new Error('page script did not become idle');
}

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

const ABC = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad';
function binary(text) { return new TextEncoder().encode(text).buffer; }
function imageResponse(text) {
  return { ok: true, status: 200, headers: { get() { return null; } }, arrayBuffer: async () => binary(text) };
}

// response: the release API reply; images: asset URL -> image reply.
async function page(response, images = {}) {
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
    BruciaReleases: catalog, URL: ObjectURL, Blob, crypto: trackedCrypto, navigator: { serial: {} },
    fetch: async (url) => url.startsWith('https://api.github.com/') ? response
      : (images[url] || { ok: false, status: 404 })
  });
  await idle();
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

function releaseReply(fields = {}) {
  return { ok: true, json: async () => ({ tag_name: 'v0.2',
    assets: [{ ...asset('brucia-t-embed-cc1101.bin'), size: 3, digest: `sha256:${ABC}`, ...fields }] }) };
}
const IMAGE_URL = asset('brucia-t-embed-cc1101.bin').browser_download_url;

test('actual page script selects exact release assets and never reuses a missing-board manifest', async () => {
  const { nodes, selection, listeners, blobs } = await page(releaseReply(), { [IMAGE_URL]: imageResponse('abc') });
  assert.equal(nodes.boardInstall.activate.disabled, false);
  assert.match(nodes.releaseStatus.textContent, /v0\.2: image SHA-256 verified/);
  const url = nodes.boardInstall.attributes.manifest;
  const manifest = JSON.parse(await blobs.get(url).text());
  assert.equal(manifest.version, 'v0.2');
  // The flasher receives the verified bytes, never the remote URL.
  const part = manifest.builds[0].parts[0].path;
  assert.match(part, /^blob:/);
  assert.equal(await blobs.get(part).text(), 'abc');
  selection.selectedIndex = 1; listeners.change(); await idle();
  assert.equal(nodes.boardInstall.activate.disabled, true);
  assert.equal(nodes.boardInstall.attributes.manifest, undefined);
  assert.equal(blobs.has(url), false);
  assert.equal(nodes.quickInstall.activate.disabled, false);
  listeners.pagehide(); await idle(); assert.equal(blobs.size, 0);
  listeners.pageshow(); await idle();
  assert.equal(blobs.size, 2); // hero manifest and its verified image recreated after bfcache restore
});

test('actual page script never hands a mismatching image to the flasher', async () => {
  for (const [fields, body, reason] of [[{}, 'abd', /SHA-256 mismatch/], [{}, 'abcd', /Size mismatch/],
      [{ size: 4 }, 'abc', /Size mismatch/]]) {
    const { nodes, blobs } = await page(releaseReply(fields), { [IMAGE_URL]: imageResponse(body) });
    for (const button of [nodes.boardInstall, nodes.quickInstall]) {
      assert.equal(button.activate.disabled, true);
      assert.equal(button.attributes.manifest, undefined);
    }
    assert.match(nodes.releaseStatus.textContent, reason);
    assert.match(nodes.releaseStatus.textContent, /Nothing was sent to the flasher/);
    assert.equal(blobs.size, 0);
  }
});

test('actual page script allows a digest-less release but labels it unverified', async () => {
  const { nodes } = await page(releaseReply({ digest: null }), { [IMAGE_URL]: imageResponse('abc') });
  assert.equal(nodes.boardInstall.activate.disabled, false);
  assert.match(nodes.releaseStatus.textContent, /unverified \(release lists no SHA-256 digest\)/);
  const malformed = await page(releaseReply({ digest: `sha256:${ABC.toUpperCase()}` }), { [IMAGE_URL]: imageResponse('abc') });
  assert.equal(malformed.nodes.boardInstall.activate.disabled, true);
  assert.match(malformed.nodes.releaseStatus.textContent, /No firmware asset/);
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

// Same inputs as ReleaseCatalogTest: app and website accept exactly one URL spelling.
test('asset URL must equal the canonical string; tags are bounded strings; APKs unambiguous', () => {
  const good = asset('Bruce-lilygo-t-embed-cc1101.bin');
  const canonical = good.browser_download_url;
  for (const url of [canonical.replace('github.com', 'GitHub.com'), canonical.replace('https:', 'HTTPS:'),
      canonical.replace('github.com', 'github.com:443'), canonical + '?', canonical + '#', ' ' + canonical,
      canonical.replace('v0.2/', 'v0.2/x/../'), canonical.replace('v0.2/', 'v0.2/x/%2e%2e/'),
      canonical.replace('github', 'git\nhub'), canonical.replace(/\//g, '\\'), new URL(canonical)]) {
    assert.deepEqual(Object.keys(catalog.parse({ tag_name: 'v0.2', assets: [
      { ...good, browser_download_url: url }] }).boards), [], String(url));
  }
  const variant = { ...good, browser_download_url: canonical.replace('github.com', 'GITHUB.COM') };
  assert.deepEqual({ ...catalog.parse({ tag_name: 'v0.2', assets: [asset('brucia-t-embed-cc1101.bin'), variant] }).boards },
    { 'lilygo-t-embed-cc1101': { url: asset('brucia-t-embed-cc1101.bin').browser_download_url, size: null, sha256: null } });
  for (const tag of [0.2, ['v0.2'], 'v0.1.1234567890']) {
    assert.throws(() => catalog.parse({ tag_name: tag, assets: [] }), undefined, String(tag));
  }
  assert.throws(() => catalog.parse({ tag_name: 'v0.2', assets: [asset('first.apk'), asset('second.APK')] }));
  assert.deepEqual(Object.keys(catalog.parse({ tag_name: 'v0.2', assets: [asset('../other.apk'), asset('one.apk')] }).boards), []);
});

// Same fixtures as ReleaseIntegrityTest.kt: identical size/digest rules on app and website.
const FIRMWARE = 'brucia-t-embed-cc1101.bin';
const OMIT = Symbol('omit'); // key absent from the asset JSON
function sized(name, size = 3, digest = `sha256:${ABC}`) {
  const json = asset(name);
  if (size !== OMIT) json.size = size;
  if (digest !== OMIT) json.digest = digest;
  return json;
}
function parseAssets(...assets) { return catalog.parse({ tag_name: 'v0.2', assets }); }
const subtle = globalThis.crypto.subtle;

test('integrity: matching size and digest are parsed and verified', async () => {
  const release = parseAssets(sized('Brucia.apk'), sized(FIRMWARE));
  const board = release.boards['lilygo-t-embed-cc1101'];
  assert.deepEqual({ size: board.size, sha256: board.sha256 }, { size: 3, sha256: ABC });
  assert.deepEqual({ size: release.apk.size, sha256: release.apk.sha256 }, { size: 3, sha256: ABC });
  assert.equal(catalog.label(board), 'SHA-256 verified');
  assert.equal(new TextDecoder().decode(await catalog.verify(binary('abc'), board, subtle)), 'abc');
  const fetched = await catalog.fetchVerified(async (url) => { assert.equal(url, board.url); return imageResponse('abc'); }, subtle, board);
  assert.equal(fetched.byteLength, 3);
});

test('integrity: size mismatch is a hard failure', async () => {
  const board = parseAssets(sized(FIRMWARE, 4)).boards['lilygo-t-embed-cc1101'];
  await assert.rejects(catalog.verify(binary('abc'), board, subtle), /^Error: Size mismatch/);
  await assert.rejects(catalog.fetchVerified(async () => imageResponse('abc'), subtle, board), /Size mismatch/);
});

test('integrity: digest mismatch is a hard failure', async () => {
  const board = parseAssets(sized(FIRMWARE, 3, 'sha256:' + '0'.repeat(64))).boards['lilygo-t-embed-cc1101'];
  await assert.rejects(catalog.verify(binary('abc'), board, subtle), /^Error: SHA-256 mismatch/);
  const good = parseAssets(sized(FIRMWARE)).boards['lilygo-t-embed-cc1101'];
  await assert.rejects(catalog.verify(binary('abd'), good, subtle), /SHA-256 mismatch/);
  await assert.rejects(catalog.fetchVerified(async () => ({ ok: false, status: 404 }), subtle, good), /HTTP 404/);
});

test('integrity: absent digest or size is allowed but unverified', async () => {
  for (const fixture of [sized(FIRMWARE, 3, OMIT), sized(FIRMWARE, 3, null),
      sized(FIRMWARE, OMIT, OMIT), sized(FIRMWARE, null, null)]) {
    const board = parseAssets(fixture).boards['lilygo-t-embed-cc1101'];
    assert.equal(board.sha256, null);
    assert.match(catalog.label(board), /^unverified/);
    assert.equal((await catalog.verify(binary('abc'), board, subtle)).byteLength, 3);
  }
  const sizedOnly = parseAssets(sized(FIRMWARE, 3, null)).boards['lilygo-t-embed-cc1101'];
  await assert.rejects(catalog.verify(binary('abcd'), sizedOnly, subtle), /Size mismatch/);
});

test('integrity: malformed digest or size rejects the asset instead of downgrading', () => {
  for (const digest of [`sha256:${ABC.toUpperCase()}`, `SHA256:${ABC}`, `sha256:${ABC.slice(0, -1)}`,
      `sha256:${ABC}0`, `sha512:${ABC}`, ABC, ` sha256:${ABC}`, `sha256:${ABC}\n`, '', 42, [`sha256:${ABC}`]]) {
    const release = parseAssets(sized('Brucia.apk', 3, digest), sized(FIRMWARE, 3, digest));
    assert.equal(release.apk, null, String(digest));
    assert.deepEqual(Object.keys(release.boards), [], String(digest));
  }
  for (const size of [0, -3, '3', true, catalog.FIRMWARE_MAX_BYTES + 1]) {
    assert.deepEqual(Object.keys(parseAssets(sized(FIRMWARE, size)).boards), [], String(size));
  }
  // Raw number spellings: same decision as org.json in the app test.
  for (const [text, accepted] of [['1.5', false], ['1e400', false], ['-0', false], ['3.0', true], ['0.3e1', true]]) {
    const raw = JSON.parse(`{"tag_name":"v0.2","assets":[${JSON.stringify(sized(FIRMWARE, OMIT)).slice(0, -1)},"size":${text}}]}`);
    assert.equal(Object.keys(catalog.parse(raw).boards).length === 1, accepted, text);
  }
  assert.equal(parseAssets(sized('Brucia.apk', catalog.FIRMWARE_MAX_BYTES + 1, null)).apk.size, catalog.FIRMWARE_MAX_BYTES + 1);
  assert.equal(parseAssets(sized('Brucia.apk', catalog.APK_MAX_BYTES + 1)).apk, null);
  assert.throws(() => parseAssets(sized(FIRMWARE, 3, 'md5:x'), asset('Bruce-lilygo-t-embed-cc1101.bin')), /Ambiguous/);
  assert.throws(() => parseAssets(sized('a.apk', 0), asset('b.apk')), /Ambiguous/);
});
