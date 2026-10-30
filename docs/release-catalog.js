/* Release parsing shared by the website's install controls and offline tests. */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.BruciaReleases = factory();
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  function parse(release) {
    if (!release || !/^[vV]?\d+\.\d+(?:\.\d+)?$/.test(release.tag_name) ||
        (release.draft !== undefined && release.draft !== false) ||
        (release.prerelease !== undefined && release.prerelease !== false)) {
      throw new Error("No stable release is available");
    }
    var boards = Object.create(null);
    (Array.isArray(release.assets) ? release.assets : []).forEach(function (asset) {
      if (!asset || typeof asset.name !== "string") return;
      var match = /^(?:Bruce-|brucia-)([A-Za-z0-9][A-Za-z0-9_-]*)\.bin$/.exec(asset.name);
      if (!match) return;
      var url;
      try { url = new URL(asset.browser_download_url); } catch (_) { return; }
      if (url.protocol !== "https:" || url.hostname !== "github.com" || url.username || url.password ||
          (url.port && url.port !== "443") || url.search || url.hash ||
          url.pathname !== "/1vcian/BruciaDev/releases/download/" + release.tag_name + "/" + asset.name) return;
      var env = match[1] === "t-embed-cc1101" ? "lilygo-t-embed-cc1101" : match[1];
      if (boards[env]) throw new Error("Ambiguous firmware assets for " + env);
      boards[env] = url.href;
    });
    return { tag: release.tag_name, boards: boards };
  }
  function manifest(release, environment, chip) {
    if (!release.boards[environment]) return null;
    return {
      name: "Brucia", version: release.tag, new_install_prompt_erase: true,
      builds: [{ chipFamily: chip, parts: [{ path: release.boards[environment], offset: 0 }] }]
    };
  }
  return { parse: parse, manifest: manifest };
});
