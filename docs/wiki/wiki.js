/* ==========================================================================
   Brucia Wiki - client-side markdown renderer
   Source of truth: wiki/*.md in the repo. Fetched live from raw.githubusercontent
   (GitHub Pages serves only docs/, so same-origin /wiki/ is not available).
   Libraries: marked 12.0.2 + DOMPurify 3.1.6 (pinned, loaded in index.html).
   ========================================================================== */
(function () {
  "use strict";

  // Repository links come from ../brand.js (generated from brand.json, see docs/LAUNCH.md).
  var RAW_BASE = window.BruciaBrand.raw_wiki_base;
  var WIKI_BASE = window.BruciaBrand.github_wiki_url;

  // route slug -> { title, file, wiki (github wiki page name) }
  // nav order follows wiki/_Sidebar.md
  var PAGES = [
    { slug: "home",            title: "Home",            file: "Home.md",            wiki: "Home" },
    { slug: "getting-started", title: "Getting Started", file: "Getting-Started.md", wiki: "Getting-Started" },
    { slug: "features",        title: "Features",        file: "Features.md",        wiki: "Features" },
    { slug: "architecture",    title: "Architecture",    file: "Architecture.md",    wiki: "Architecture" },
    { slug: "protocol",        title: "Protocol",        file: "Protocol.md",        wiki: "Protocol" },
    { slug: "building",        title: "Building",        file: "Building.md",        wiki: "Building" },
    { slug: "contributing",    title: "Contributing",    file: "Contributing.md",    wiki: "Contributing" },
    { slug: "faq",             title: "FAQ",             file: "FAQ.md",             wiki: "FAQ" }
  ];

  var BY_SLUG = {};
  PAGES.forEach(function (p) { BY_SLUG[p.slug] = p; });

  var DEFAULT_SLUG = "home";
  var cache = {}; // slug -> markdown text

  // Emoji / pictographic ranges to strip from rendered output (sources have none).
  var EMOJI_RE = new RegExp(
    "[\\u{1F000}-\\u{1FAFF}\\u{2600}-\\u{27BF}\\u{1F1E6}-\\u{1F1FF}" +
    "\\u{2B00}-\\u{2BFF}\\u{FE00}-\\u{FE0F}\\u{2300}-\\u{23FF}\\u{2190}-\\u{21FF}]",
    "gu"
  );

  // Normalise any page reference (name, Name.md, Getting Started) to a known slug.
  function toSlug(name) {
    if (!name) return null;
    var s = String(name).trim();
    s = s.split("#")[0];
    s = s.replace(/\.md$/i, "");
    s = s.toLowerCase().replace(/[_\s]+/g, "-");
    return BY_SLUG.hasOwnProperty(s) ? s : null;
  }

  function currentSlug() {
    var h = (location.hash || "").replace(/^#\/?/, "").trim();
    var slug = h.split("#")[0].toLowerCase();
    return BY_SLUG.hasOwnProperty(slug) ? slug : DEFAULT_SLUG;
  }

  // Pre-process raw markdown: convert [[Page]] / [[Page|label]] wiki links.
  function preprocess(md) {
    return md.replace(/\[\[([^\]|]+?)(?:\|([^\]]+))?\]\]/g, function (m, target, label) {
      var slug = toSlug(target);
      var text = (label || target).trim();
      return slug ? "[" + text + "](#/" + slug + ")" : text;
    });
  }

  // Post-render: rewrite internal links, open external links in a new tab.
  function rewriteLinks(container) {
    var links = container.querySelectorAll("a[href]");
    Array.prototype.forEach.call(links, function (a) {
      var href = a.getAttribute("href");
      if (!href) return;

      // already a local hash route (from [[ ]] preprocessing or an in-doc anchor)
      if (href.indexOf("#/") === 0) return;
      if (href.charAt(0) === "#") return;
      if (/^mailto:/i.test(href)) return;

      if (/^https?:\/\//i.test(href)) {
        // main site / web flasher -> local main page
        if (/^https?:\/\/1vcian\.me\/Brucia\/?$/i.test(href)) {
          a.setAttribute("href", "../index.html");
          a.removeAttribute("target");
          a.removeAttribute("rel");
          return;
        }
        a.setAttribute("target", "_blank");
        a.setAttribute("rel", "noopener");
        return;
      }

      // site-relative links (kept, e.g. ../index.html, ./img/x.png)
      if (href.indexOf("../") === 0 || href.indexOf("./") === 0 || href.charAt(0) === "/") {
        return;
      }

      // bare relative wiki page reference: "Features" or "Features.md[#anchor]"
      var anchor = "";
      if (href.indexOf("#") > -1) anchor = "#" + href.split("#").slice(1).join("#");
      var slug = toSlug(href);
      if (slug) {
        a.setAttribute("href", "#/" + slug + anchor);
      } else {
        // unknown relative target -> treat as external-ish, open safely
        a.setAttribute("target", "_blank");
        a.setAttribute("rel", "noopener");
      }
    });
  }

  // Give headings stable ids for in-page anchors.
  function addHeadingIds(container) {
    var hs = container.querySelectorAll("h1,h2,h3,h4,h5,h6");
    var used = {};
    Array.prototype.forEach.call(hs, function (h) {
      if (h.id) return;
      var base = (h.textContent || "").toLowerCase()
        .replace(/[^\w\s-]/g, "").trim().replace(/\s+/g, "-");
      if (!base) base = "section";
      var id = base, n = 1;
      while (used[id]) { id = base + "-" + (n++); }
      used[id] = true;
      h.id = id;
    });
  }

  // Wrap tables so wide tables scroll horizontally.
  function wrapTables(container) {
    var tables = container.querySelectorAll("table");
    Array.prototype.forEach.call(tables, function (t) {
      if (t.parentNode && t.parentNode.classList &&
          t.parentNode.classList.contains("table-wrap")) return;
      var wrap = document.createElement("div");
      wrap.className = "table-wrap";
      t.parentNode.insertBefore(wrap, t);
      wrap.appendChild(t);
    });
  }

  function highlightNav(slug) {
    var links = document.querySelectorAll("#sidebar .side-list a[data-slug]");
    Array.prototype.forEach.call(links, function (a) {
      var on = a.getAttribute("data-slug") === slug;
      a.classList.toggle("active", on);
      if (on) { a.setAttribute("aria-current", "page"); }
      else { a.removeAttribute("aria-current"); }
    });
  }

  function buildSidebar() {
    var ul = document.getElementById("navList");
    if (!ul) return;
    var html = PAGES.map(function (p) {
      return '<li><a href="#/' + p.slug + '" data-slug="' + p.slug + '">' + p.title + "</a></li>";
    }).join("");
    ul.innerHTML = html;
  }

  function setStatus(content, html) {
    content.innerHTML = html;
  }

  function showLoading(content, page) {
    setStatus(content,
      '<div class="wiki-status"><span class="spinner" aria-hidden="true"></span>' +
      "<span>Loading " + page.title + "...</span></div>");
  }

  function showError(content, page) {
    setStatus(content,
      '<div class="wiki-error"><h2>Could not load this page</h2>' +
      "<p>The " + page.title + " page could not be fetched right now. " +
      "You can read it directly on GitHub instead.</p>" +
      '<p><a href="' + WIKI_BASE + page.wiki + '" target="_blank" rel="noopener">' +
      "Open " + page.title + " on the GitHub wiki</a></p></div>");
  }

  function render(content, page, md) {
    var processed = preprocess(md);
    var rawHtml = marked.parse(processed, { gfm: true, breaks: false });
    // strip any emoji, then sanitize
    rawHtml = rawHtml.replace(EMOJI_RE, "");
    var clean = DOMPurify.sanitize(rawHtml, { ADD_ATTR: ["target", "rel", "id"] });
    content.innerHTML = clean;
    addHeadingIds(content);
    rewriteLinks(content);
    wrapTables(content);

    var src = document.createElement("p");
    src.className = "page-source";
    src.innerHTML = 'Source: <a href="' + WIKI_BASE + page.wiki +
      '" target="_blank" rel="noopener">wiki/' + page.file + "</a> (edit there)";
    content.appendChild(src);
  }

  function route() {
    var content = document.getElementById("content");
    if (!content) return;
    var slug = currentSlug();
    var page = BY_SLUG[slug];

    document.title = page.title + " - Brucia Wiki";
    highlightNav(slug);

    // close mobile nav on navigation
    var sb = document.getElementById("sidebar");
    var mb = document.getElementById("menuBtn");
    if (sb) sb.classList.remove("open");
    if (mb) mb.setAttribute("aria-expanded", "false");

    window.scrollTo(0, 0);

    if (cache[slug] != null) {
      render(content, page, cache[slug]);
      return;
    }

    showLoading(content, page);
    var reqSlug = slug;
    fetch(RAW_BASE + page.file, { cache: "no-cache" })
      .then(function (r) {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.text();
      })
      .then(function (text) {
        cache[reqSlug] = text;
        if (currentSlug() !== reqSlug) return; // user navigated away
        render(content, page, text);
      })
      .catch(function () {
        if (currentSlug() !== reqSlug) return;
        showError(content, page);
      });
  }

  function init() {
    buildSidebar();

    var menuBtn = document.getElementById("menuBtn");
    var sidebar = document.getElementById("sidebar");
    if (menuBtn && sidebar) {
      menuBtn.addEventListener("click", function () {
        var open = sidebar.classList.toggle("open");
        menuBtn.setAttribute("aria-expanded", open ? "true" : "false");
      });
    }

    window.addEventListener("hashchange", route);

    if (!location.hash) {
      location.replace("#/" + DEFAULT_SLUG);
    }
    route();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
