/* Release parsing shared by the website's install controls and offline tests.
   Mirrors android/.../update/ReleaseCatalog.kt: same tag rule, exact canonical asset URL string, same aliases,
   same size/digest rules as AssetIntegrity.kt:
   - size/digest absent or null: allowed (older releases), reported as unverified;
   - present but malformed (size not an integer in 1..limit, digest not exactly
     "sha256:" + 64 lowercase hex): the asset is not offered (it still counts for ambiguity);
   - mismatch after download: hard failure, nothing reaches the flasher.
   Repository links come from brand.js (generated from brand.json); assets are accepted from the
   current repository and any other names in accepted_release_repos (used only across a rename). */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory(require("./brand.js"));
  else root.BruciaReleases = factory(root.BruciaBrand);
})(typeof globalThis !== "undefined" ? globalThis : this, function (brand) {
  "use strict";
  if (!brand || !Array.isArray(brand.accepted_download_prefixes)) throw new Error("brand.js must load before release-catalog.js");
  var DOWNLOAD_PREFIXES = brand.accepted_download_prefixes.slice();
  var APK_MAX_BYTES = 128 * 1024 * 1024;
  var FIRMWARE_MAX_BYTES = 16 * 1024 * 1024;
  /* Returns null when a present value is malformed. */
  function integrity(asset, limit) {
    var size = asset.size === undefined ? null : asset.size;
    var digest = asset.digest === undefined ? null : asset.digest;
    if (size !== null && (typeof size !== "number" || !Number.isInteger(size) || size < 1 || size > limit)) return null;
    if (digest !== null && (typeof digest !== "string" || !/^sha256:[0-9a-f]{64}$/.test(digest))) return null;
    return { size: size, sha256: digest === null ? null : digest.slice(7) };
  }
  function parse(release) {
    if (!release || typeof release.tag_name !== "string" ||
        !/^[vV]?[0-9]{1,9}(?:\.[0-9]{1,9}){1,2}$/.test(release.tag_name) ||
        (release.draft !== undefined && release.draft !== false) ||
        (release.prerelease !== undefined && release.prerelease !== false)) {
      throw new Error("No stable release is available");
    }
    var boards = Object.create(null);
    var seenEnvs = Object.create(null);
    var apk = null;
    var apkSeen = false;
    (Array.isArray(release.assets) ? release.assets : []).forEach(function (asset) {
      if (!asset || typeof asset.name !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(asset.name)) return;
      // No URL normalization: only the exact spelling GitHub publishes is accepted.
      var url = asset.browser_download_url;
      var suffix = release.tag_name + "/" + asset.name;
      if (typeof url !== "string" || !DOWNLOAD_PREFIXES.some(function (prefix) { return url === prefix + suffix; })) return;
      if (/\.apk$/i.test(asset.name)) {
        if (apkSeen) throw new Error("Ambiguous APK assets");
        apkSeen = true;
        var apkIntegrity = integrity(asset, APK_MAX_BYTES);
        if (apkIntegrity) apk = { url: url, size: apkIntegrity.size, sha256: apkIntegrity.sha256 };
      }
      var match = /^(?:Bruce-|brucia-)([A-Za-z0-9][A-Za-z0-9_-]*)\.bin$/.exec(asset.name);
      if (!match) return;
      var env = match[1] === "t-embed-cc1101" ? "lilygo-t-embed-cc1101" : match[1];
      if (seenEnvs[env]) throw new Error("Ambiguous firmware assets for " + env);
      seenEnvs[env] = true;
      var checked = integrity(asset, FIRMWARE_MAX_BYTES);
      if (checked) boards[env] = { url: url, size: checked.size, sha256: checked.sha256 };
    });
    return { tag: release.tag_name, boards: boards, apk: apk };
  }
  /* path: the image the flasher loads; the page passes a blob: URL of verified bytes. */
  function manifest(release, environment, chip, path) {
    var board = release.boards[environment];
    if (!board) return null;
    return {
      name: "Brucia", version: release.tag, new_install_prompt_erase: true,
      builds: [{ chipFamily: chip, parts: [{ path: path || board.url, offset: 0 }] }]
    };
  }
  function hex(buffer) {
    return Array.prototype.map.call(new Uint8Array(buffer), function (b) { return (b < 16 ? "0" : "") + b.toString(16); }).join("");
  }
  /* Resolves with the bytes only when the listed size and SHA-256 (if present) match. */
  function verify(buffer, asset, subtle) {
    if (asset.size !== null && buffer.byteLength !== asset.size) {
      return Promise.reject(new Error("Size mismatch: downloaded " + buffer.byteLength + " bytes, release lists " + asset.size));
    }
    if (asset.sha256 === null) return Promise.resolve(buffer);
    return subtle.digest("SHA-256", buffer).then(function (digest) {
      var actual = hex(digest);
      if (actual !== asset.sha256) throw new Error("SHA-256 mismatch: release lists " + asset.sha256 + ", downloaded " + actual);
      return buffer;
    });
  }
  /* Fetch a firmware asset and verify it before anything is handed to the flasher. */
  function fetchVerified(fetchFn, subtle, asset) {
    return fetchFn(asset.url).then(function (response) {
      if (!response.ok) throw new Error("Firmware download failed (HTTP " + response.status + ")");
      var length = response.headers && response.headers.get ? response.headers.get("Content-Length") : null;
      if (length !== null && Number(length) > FIRMWARE_MAX_BYTES) throw new Error("Firmware image exceeds size limit");
      return response.arrayBuffer();
    }).then(function (buffer) {
      if (buffer.byteLength === 0 || buffer.byteLength > FIRMWARE_MAX_BYTES) throw new Error("Firmware image is empty or exceeds size limit");
      return verify(buffer, asset, subtle);
    });
  }
  function label(asset) {
    return asset.sha256 ? "SHA-256 verified" : "unverified (release lists no SHA-256 digest)";
  }
  return { parse: parse, manifest: manifest, verify: verify, fetchVerified: fetchVerified, label: label,
    APK_MAX_BYTES: APK_MAX_BYTES, FIRMWARE_MAX_BYTES: FIRMWARE_MAX_BYTES,
    RELEASES_API: brand.releases_api, DOWNLOAD_PREFIXES: DOWNLOAD_PREFIXES };
});
