(function () {
  const MAX_LIMIT = 80;
  const DEFAULT_LIMIT = 40;
  const NAME_MAX = 48;
  const VALUE_MAX = 24;
  const HREF_MAX = 400;
  const SRC_MAX = 400;
  const READ_MAX = 4000;
  const JS_MAX = 4000;
  const INSPECT_TEXT = 500;
  const OPTION_MAX = 30;

  let nextId = 1;
  let ids = new WeakMap();
  const byId = new Map();
  let lastHref = "";
  let nativePort = null;
  let helloTimer = 0;
  const DOC_ID = "d" + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);

  function resetRefs() {
    nextId = 1;
    byId.clear();
    ids = new WeakMap();
  }

  function resetIfNavigated() {
    if (lastHref === location.href) return;
    lastHref = location.href;
    resetRefs();
    scheduleAnnounce("spa");
  }

  try {
    const origPush = history.pushState;
    history.pushState = function () {
      const ret = origPush.apply(this, arguments);
      resetIfNavigated();
      return ret;
    };
    const origReplace = history.replaceState;
    history.replaceState = function () {
      const ret = origReplace.apply(this, arguments);
      resetIfNavigated();
      return ret;
    };
    window.addEventListener("popstate", resetIfNavigated);
    window.addEventListener("hashchange", resetIfNavigated);
  } catch (e) {}

  function pruneDisconnected() {
    for (const [id, el] of Array.from(byId.entries())) {
      if (!el || !el.isConnected) byId.delete(id);
    }
  }

  function idOf(el) {
    let id = ids.get(el);
    if (!id) {
      id = nextId++;
      ids.set(el, id);
    }
    byId.set(id, el);
    return id;
  }

  function byRef(ref) {
    const match = String(ref || "").match(/e(\d+)/i);
    if (!match) return null;
    const el = byId.get(Number(match[1]));
    if (!el || !el.isConnected) return null;
    return el;
  }

  function clip(text, max) {
    const s = String(text || "").replace(/\s+/g, " ").trim();
    if (s.length <= max) return s;
    return s.slice(0, Math.max(0, max - 1)) + "…";
  }

  function clipUrl(text, max) {
    const s = String(text || "").trim();
    if (s.length <= max) return s;
    return s.slice(0, max);
  }

  function absoluteHref(el) {
    try {
      const raw = el.getAttribute && el.getAttribute("href");
      if (!raw) return "";
      return new URL(raw, location.href).href;
    } catch (e) {
      return (el.getAttribute && el.getAttribute("href")) || "";
    }
  }

  function styleOf(el) {
    try {
      return window.getComputedStyle(el);
    } catch (e) {
      return null;
    }
  }

  function boxOf(el) {
    try {
      const r = el.getBoundingClientRect();
      return { x: r.x, y: r.y, w: r.width, h: r.height };
    } catch (e) {
      return { x: 0, y: 0, w: 0, h: 0 };
    }
  }

  function inViewport(box) {
    const vw = window.innerWidth || 1;
    const vh = window.innerHeight || 1;
    return box.w >= 1 && box.h >= 1 && box.x < vw && box.y < vh && box.x + box.w > 0 && box.y + box.h > 0;
  }

  function regionOf(box) {
    const vh = window.innerHeight || 1;
    const cy = box.y + box.h / 2;
    if (cy < vh / 3) return "top";
    if (cy < (2 * vh) / 3) return "middle";
    return "bottom";
  }

  function isHidden(el) {
    if (!el || el.nodeType !== 1) return true;
    if (el.hasAttribute("hidden") || el.getAttribute("aria-hidden") === "true" || el.hasAttribute("inert")) return true;
    const st = styleOf(el);
    if (!st) return false;
    if (st.display === "none" || st.visibility === "hidden" || Number(st.opacity) === 0) return true;
    return false;
  }

  function isSubtreeHidden(el) {
    let node = el;
    while (node && node.nodeType === 1) {
      if (node.hasAttribute("inert") || node.getAttribute("aria-hidden") === "true" || node.hasAttribute("hidden")) {
        return true;
      }
      const st = styleOf(node);
      if (st && (st.display === "none" || st.visibility === "hidden")) return true;
      node = node.parentElement;
    }
    return false;
  }

  function roleOf(el) {
    const explicit = (el.getAttribute("role") || "").trim();
    if (explicit) return explicit;
    const tag = el.tagName.toLowerCase();
    if (tag === "a") return el.hasAttribute("href") ? "link" : "generic";
    if (tag === "button") return "button";
    if (tag === "textarea") return "textbox";
    if (tag === "select") return "combobox";
    if (tag === "img") return el.getAttribute("alt") === "" ? "presentation" : "image";
    if (tag === "dialog") return "dialog";
    if (tag === "summary") return "button";
    if (tag === "form") return "form";
    if (tag === "article") return "article";
    if (tag === "aside") return "complementary";
    if (tag === "nav") return "navigation";
    if (tag === "main") return "main";
    if (tag === "header") return closestLandmark(el, "article,section,main") ? "generic" : "banner";
    if (tag === "footer") return closestLandmark(el, "article,section,main") ? "generic" : "contentinfo";
    if (tag === "section") return nameOf(el, true) ? "region" : "generic";
    if (tag === "ul" || tag === "ol" || tag === "menu") return "list";
    if (tag === "li") return "listitem";
    if (tag === "table") return "table";
    if (tag === "tr") return "row";
    if (tag === "td") return "cell";
    if (tag === "th") return el.scope === "row" ? "rowheader" : "columnheader";
    if (tag === "progress") return "progressbar";
    if (tag === "meter") return "meter";
    if (tag === "hr") return "separator";
    if (tag === "output") return "status";
    if (tag === "search") return "search";
    if (tag === "details") return "group";
    if (tag === "input") {
      const type = (el.type || "text").toLowerCase();
      if (type === "checkbox") return "checkbox";
      if (type === "radio") return "radio";
      if (type === "submit" || type === "button" || type === "reset" || type === "image") return "button";
      if (type === "file") return "file";
      if (type === "range") return "slider";
      if (type === "hidden") return "hidden";
      if (type === "search") return "searchbox";
      if (type === "number") return "spinbutton";
      if (type === "password") return "textbox";
      return "textbox";
    }
    if (/^h[1-6]$/.test(tag)) return "heading";
    if (el.isContentEditable) return "textbox";
    const st = styleOf(el);
    if (st && st.cursor === "pointer" && (el.onclick || el.getAttribute("onclick"))) return "button";
    return "generic";
  }

  function closestLandmark(el, selector) {
    try {
      return !!(el.parentElement && el.parentElement.closest(selector));
    } catch (e) {
      return false;
    }
  }

  function labelFor(el) {
    if (el.id) {
      try {
        const lab = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
        if (lab) return (lab.innerText || "").trim();
      } catch (e) {
        const labels = document.querySelectorAll("label[for]");
        for (let i = 0; i < labels.length; i++) {
          if (labels[i].htmlFor === el.id) return (labels[i].innerText || "").trim();
        }
      }
    }
    const wrap = el.closest && el.closest("label");
    if (wrap) return (wrap.innerText || "").trim();
    return "";
  }

  function nameOf(el, skipInner) {
    const labelled = el.getAttribute("aria-labelledby");
    if (labelled) {
      const parts = labelled.split(/\s+/).map(function (id) {
        const n = document.getElementById(id);
        return n ? (n.innerText || "").trim() : "";
      });
      const joined = parts.filter(Boolean).join(" ");
      if (joined) return joined;
    }
    const aria = el.getAttribute("aria-label");
    if (aria) return aria;
    const labelledForm = labelFor(el);
    if (labelledForm) return labelledForm;
    const tag = el.tagName.toLowerCase();
    if (tag === "fieldset") {
      const legend = el.querySelector("legend");
      if (legend) return (legend.innerText || "").trim();
    }
    if (tag === "figure") {
      const cap = el.querySelector("figcaption");
      if (cap) return (cap.innerText || "").trim();
    }
    if (tag === "input" && (el.type === "submit" || el.type === "button" || el.type === "reset")) {
      if (el.value) return el.value;
    }
    if (skipInner) {
      return el.getAttribute("placeholder") || el.getAttribute("alt") || el.getAttribute("title") || el.getAttribute("name") || "";
    }
    return (
      el.getAttribute("placeholder") ||
      el.getAttribute("alt") ||
      el.getAttribute("title") ||
      el.getAttribute("name") ||
      (tag === "input" ? el.getAttribute("type") : "") ||
      (el.innerText || "").trim()
    );
  }

  function valueOf(el) {
    if (el.tagName.toLowerCase() === "select") {
      const opt = el.options && el.options[el.selectedIndex];
      return (opt && (opt.text || opt.value)) || el.value || "";
    }
    if (typeof el.value === "string") return el.value;
    return "";
  }

  function isInteractive(el) {
    const role = roleOf(el);
    const tag = el.tagName.toLowerCase();
    if (role === "hidden" || role === "none" || role === "presentation" || role === "generic") {
      if (!(el.onclick || el.getAttribute("onclick") || (el.tabIndex >= 0 && tag !== "body" && tag !== "html"))) {
        if (role === "generic") {
          /* fall through to tag checks */
        } else {
          return false;
        }
      }
    }
    if (el.disabled) return true;
    if (tag === "a" && el.hasAttribute("href")) return true;
    if (tag === "button" || tag === "select" || tag === "textarea" || tag === "summary") return true;
    if (tag === "input" && (el.type || "").toLowerCase() !== "hidden") return true;
    if (el.isContentEditable) return true;
    if (el.onclick || el.getAttribute("onclick")) return true;
    if (el.tabIndex >= 0 && tag !== "body" && tag !== "html" && tag !== "a") return true;
    return /^(button|link|textbox|checkbox|radio|combobox|menuitem|tab|switch|slider|searchbox|spinbutton|option|file|progressbar)$/.test(role);
  }

  function isStructural(el) {
    const role = roleOf(el);
    const tag = el.tagName.toLowerCase();
    return (
      role === "heading" ||
      role === "dialog" ||
      role === "alertdialog" ||
      role === "main" ||
      role === "navigation" ||
      role === "banner" ||
      role === "contentinfo" ||
      role === "complementary" ||
      role === "form" ||
      role === "search" ||
      role === "region" ||
      role === "article" ||
      role === "list" ||
      role === "image" ||
      tag === "dialog" ||
      tag === "main" ||
      tag === "nav" ||
      tag === "form" ||
      tag === "iframe" ||
      tag === "img" ||
      /^h[1-6]$/.test(tag)
    );
  }

  function collectElements(root, out) {
    if (!root) return;
    const visit = function (node) {
      if (!node || node.nodeType !== 1) return;
      const tag = node.tagName.toLowerCase();
      if (tag === "script" || tag === "style" || tag === "noscript" || tag === "template") return;
      if (tag === "option") return;
      const role = roleOf(node);
      if (role === "none" || role === "presentation") {
        const kids = node.children;
        for (let i = 0; i < kids.length; i++) visit(kids[i]);
        return;
      }
      if (isSubtreeHidden(node)) {
        if ((tag === "dialog" && node.open) || role === "dialog" || role === "alertdialog") {
          out.push(node);
        } else {
          return;
        }
      } else if (isInteractive(node) || isStructural(node) || tag === "iframe" || tag === "img") {
        out.push(node);
      }
      if (tag === "iframe") {
        try {
          const doc = node.contentDocument;
          if (doc && doc.documentElement) collectElements(doc, out);
        } catch (e) {}
        return;
      }
      if (node.shadowRoot) collectElements(node.shadowRoot, out);
      const children = node.children;
      for (let i = 0; i < children.length; i++) visit(children[i]);
    };
    if (root.nodeType === 9) visit(root.documentElement);
    else visit(root);
  }

  function indentLevel(el) {
    let n = 0;
    let p = el.parentElement;
    while (p && n < 4) {
      const tag = p.tagName && p.tagName.toLowerCase();
      const r = roleOf(p);
      if (
        tag === "form" ||
        tag === "dialog" ||
        tag === "nav" ||
        tag === "main" ||
        tag === "ul" ||
        tag === "ol" ||
        tag === "table" ||
        r === "dialog" ||
        r === "list" ||
        r === "form" ||
        r === "main" ||
        r === "navigation"
      ) {
        n++;
      }
      p = p.parentElement;
    }
    return n;
  }

  function isPasswordField(el) {
    return (el.type || "").toLowerCase() === "password" || el.getAttribute("autocomplete") === "current-password" || el.getAttribute("autocomplete") === "new-password";
  }

  function displayValue(el) {
    if (isPasswordField(el)) return el.value ? "••••" : "";
    return valueOf(el);
  }

  function flagsOf(el, box, viewportOnly) {
    const bits = [];
    if (el.disabled) bits.push("disabled");
    if (el.readOnly) bits.push("readonly");
    if (el.checked) bits.push("checked");
    if (el.selected) bits.push("selected");
    if (el.getAttribute("aria-expanded") === "true") bits.push("expanded");
    if (el.getAttribute("aria-pressed") === "true") bits.push("pressed");
    if (el.required) bits.push("required");
    if (!inViewport(box)) bits.push("offscreen");
    return bits;
  }

  function fontPx(el) {
    const st = styleOf(el);
    if (!st) return 0;
    return Math.round(parseFloat(st.fontSize) || 0);
  }

  function headingLevel(el) {
    const tag = el.tagName.toLowerCase();
    const match = tag.match(/^h([1-6])$/);
    if (match) return Number(match[1]);
    const aria = Number(el.getAttribute("aria-level") || 0);
    return aria || 0;
  }

  function nearestHeading(el) {
    let node = el;
    while (node && node !== document.body) {
      if (node.nodeType === 1 && (roleOf(node) === "heading" || /^h[1-6]$/.test(node.tagName.toLowerCase()))) {
        return node;
      }
      if (node.previousElementSibling) {
        let sib = node.previousElementSibling;
        while (sib) {
          if (sib.nodeType === 1 && (roleOf(sib) === "heading" || /^h[1-6]$/.test(sib.tagName.toLowerCase()))) {
            return sib;
          }
          sib = sib.previousElementSibling;
        }
      }
      node = node.parentElement;
    }
    return null;
  }

  function lineOf(el) {
    const role = roleOf(el);
    const tag = el.tagName.toLowerCase();
    const name = clip(nameOf(el), NAME_MAX);
    const value = clip(displayValue(el), VALUE_MAX);
    const href = clipUrl(absoluteHref(el), HREF_MAX);
    const box = boxOf(el);
    const flags = flagsOf(el, box, false);
    const level = headingLevel(el);
    const px = fontPx(el);
    let line = "@e" + idOf(el) + " " + (level ? "h" + level : role);
    if (level && px) line += " " + px + "px";
    else if (role === "heading" && px) line += " " + px + "px";
    if (tag === "img" || role === "image") {
      const nw = el.naturalWidth || Math.round(box.w);
      const nh = el.naturalHeight || Math.round(box.h);
      line += " " + nw + "x" + nh;
      const src = clipUrl(el.currentSrc || el.src || "", SRC_MAX);
      if (src) line += " " + src;
    }
    if (name) line += " \"" + name.replace(/"/g, "'") + "\"";
    if (value && value !== name && tag !== "img") line += " =" + JSON.stringify(value);
    if (role === "link" && href) line += " " + href;
    if (tag === "iframe") line += " " + clip(el.getAttribute("src") || "", 48);
    if (flags.length) line += " [" + flags.join(",") + "]";
    const pad = indentLevel(el);
    return (pad ? "  ".repeat(pad) : "") + line;
  }

  function matchesQuery(el, query) {
    if (!query) return true;
    const q = String(query).toLowerCase();
    const hay = [
      roleOf(el),
      nameOf(el),
      displayValue(el),
      el.tagName,
      el.id,
      el.getAttribute && el.getAttribute("href"),
      el.getAttribute && el.getAttribute("name"),
      el.className,
    ]
      .join(" ")
      .toLowerCase();
    return hay.indexOf(q) >= 0;
  }

  function snapshot(msg) {
    resetIfNavigated();
    pruneDisconnected();
    const query = (msg && msg.query) || "";
    const region = String((msg && msg.region) || "viewport").toLowerCase();
    const offset = Math.max(0, Number(msg && msg.offset) || 0);
    const limit = Math.min(MAX_LIMIT, Math.max(1, Number(msg && msg.limit) || DEFAULT_LIMIT));
    const outline = !!(msg && (msg.outline === true || msg.outline === "true"));
    const collected = [];
    collectElements(document, collected);
    const seen = new Set();
    const filtered = [];
    collected.forEach(function (el) {
      if (seen.has(el)) return;
      seen.add(el);
      if (outline) {
        const role = roleOf(el);
        const tag = el.tagName.toLowerCase();
        if (!(role === "heading" || /^h[1-6]$/.test(tag) || tag === "img" || role === "image" || role === "dialog")) {
          return;
        }
      }
      if (!matchesQuery(el, query)) return;
      const box = boxOf(el);
      if (region === "viewport" && !inViewport(box)) return;
      if ((region === "top" || region === "middle" || region === "bottom") && (!inViewport(box) || regionOf(box) !== region)) {
        return;
      }
      if (box.w < 1 && box.h < 1 && region !== "page") return;
      filtered.push(el);
    });
    const page = filtered.slice(offset, offset + limit);
    const tree = page.map(lineOf).join("\n");
    const pwd = document.querySelector("input[type='password']");
    const form = document.querySelector("form");
    const total = filtered.length;
    const result = {
      ok: true,
      url: location.href,
      title: document.title,
      readyState: document.readyState,
      region: region,
      query: query,
      offset: offset,
      limit: limit,
      shown: page.length,
      total: total,
      dom_total: collected.length,
      has_password_field: !!pwd,
      has_login_form: !!(form && pwd),
      login_wall: !!(pwd && form),
      tree: tree,
    };
    if (total > offset + page.length) {
      result.more = "page_snapshot offset=" + (offset + page.length) + " limit=" + limit;
    }
    if (msg && (msg.boxes === true || msg.boxes === "true")) {
      result.boxes = page.map(function (el) {
        const b = boxOf(el);
        return {
          ref: "@e" + idOf(el),
          x: Math.round(b.x),
          y: Math.round(b.y),
          w: Math.round(b.w),
          h: Math.round(b.h),
        };
      });
    }
    return result;
  }

  function absoluteUrl(raw) {
    const s = String(raw || "").trim();
    if (!s || s.indexOf("data:") === 0 || s.indexOf("javascript:") === 0 || s.indexOf("blob:") === 0) {
      return "";
    }
    try {
      return new URL(s, location.href).href;
    } catch (e) {
      return "";
    }
  }

  function isSearchEngineHost(href) {
    try {
      const host = (new URL(href).hostname || "").replace(/^www\./, "").toLowerCase();
      return (
        host.endsWith("bing.com") ||
        host.endsWith("microsoft.com") ||
        host.endsWith("google.com") ||
        host.endsWith("googleusercontent.com") ||
        host.endsWith("baidu.com") ||
        host.endsWith("duckduckgo.com")
      );
    } catch (e) {
      return false;
    }
  }

  function isSearchEngineChrome(href) {
    if (!isSearchEngineHost(href)) return false;
    try {
      const u = new URL(href);
      const path = (u.pathname || "").toLowerCase();
      const query = (u.search || "").toLowerCase();
      if (
        /\/(ck|aclick)\b/.test(path) ||
        path === "/url" ||
        path.indexOf("/url") === 0 ||
        path.indexOf("/link") >= 0 ||
        path === "/l" ||
        path.indexOf("/l/") === 0 ||
        query.indexOf("uddg=") >= 0 ||
        query.indexOf("u=a1") >= 0 ||
        query.indexOf("imgurl=") >= 0 ||
        query.indexOf("murl=") >= 0
      ) {
        return false;
      }
      return true;
    } catch (e) {
      return true;
    }
  }

  function harvestLinks(msg) {
    const limit = Math.min(20, Math.max(1, Number(msg && msg.limit) || 8));
    const preferred = document.querySelectorAll(
      "h2 a[href], h3 a[href], a.result__a, a[data-testid='result-title-a'], .b_algo h2 a, #b_results h2 a, .g h3 a, .result__title a, .c-title a",
    );
    const fallback = document.querySelectorAll("a[href]");
    let hits = collectHarvestHits(preferred, limit);
    if (!hits.length) hits = collectHarvestHits(fallback, limit);
    return {
      ok: true,
      url: location.href,
      title: document.title,
      hits: hits,
    };
  }

  function collectHarvestHits(nodes, limit) {
    const seen = new Set();
    const hits = [];
    for (let i = 0; i < nodes.length; i++) {
      const a = nodes[i];
      const href = absoluteHref(a);
      if (!href || href.indexOf("http") !== 0) continue;
      if (isSearchEngineChrome(href)) continue;
      const key = href.split("#")[0];
      if (seen.has(key)) continue;
      const box = boxOf(a);
      if (box.w < 8 && box.h < 8) continue;
      const title = clip((a.innerText || a.getAttribute("aria-label") || "").replace(/\s+/g, " ").trim(), 180);
      if (title.length < 2) continue;
      if (isDomainOnlyLabel(title, href)) continue;
      if (
        /^(images|videos|maps|news|sign in|log in|privacy|图片|视频|地图|新闻|登录|更多|设置)$/i.test(title)
      ) {
        continue;
      }
      seen.add(key);
      hits.push({ title: title, url: href, snippet: resultSnippet(a, title) });
      if (hits.length >= limit) break;
    }
    return hits;
  }

  function isDomainOnlyLabel(text, href) {
    const t = String(text || "")
      .trim()
      .toLowerCase()
      .replace(/^www\./, "");
    if (!t || /\s/.test(t)) return false;
    if (t.indexOf("http://") === 0 || t.indexOf("https://") === 0) return true;
    try {
      const host = new URL(href).hostname.replace(/^www\./, "").toLowerCase();
      if (t === host || host.endsWith("." + t) || t.endsWith("." + host)) return true;
    } catch (e) {}
    return /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(t);
  }

  function resultSnippet(anchor, title) {
    const root =
      (anchor &&
        anchor.closest &&
        anchor.closest("li, .b_algo, .g, .result, article, .w-gl, .c-container")) ||
      (anchor && anchor.parentElement);
    if (!root) return "";
    const node = root.querySelector(
      ".b_caption p, .b_lineclamp, .result__snippet, [data-result='snippet'], .VwiC3b, .st, .c-abstract",
    );
    const text = clip(((node && node.innerText) || "").replace(/\s+/g, " ").trim(), 180);
    if (!text || text === title || isDomainOnlyLabel(text, (anchor && absoluteHref(anchor)) || "")) {
      return "";
    }
    return text;
  }

  function parseBingImageMeta(raw) {
    if (!raw) return null;
    const text = String(raw);
    try {
      return JSON.parse(text);
    } catch (e) {
      try {
        return JSON.parse(text.replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&"));
      } catch (err) {
        return null;
      }
    }
  }

  function isBingImageChrome(el) {
    if (!el || !el.closest) return false;
    return !!el.closest(
      "#b_header, #id_h, footer, #b_footer, .b_rs, #relatedSearches, .irhc, .b_focusLabel, .vsud, #b_context, [data-priority='RelatedSearches']",
    );
  }

  /** Text that sits with the image itself: figcaption, aria-label/title, or its anchor. */
  function captionFor(el) {
    if (!el || el.nodeType !== 1) return "";
    try {
      const fig = el.closest && el.closest("figure");
      const cap = fig && fig.querySelector("figcaption");
      if (cap && cap.innerText) return cap.innerText;
      const own = el.getAttribute("aria-label") || el.getAttribute("title");
      if (own) return own;
      const anchor = el.closest && el.closest("a");
      if (anchor) {
        const label = anchor.getAttribute("aria-label") || anchor.getAttribute("title");
        if (label) return label;
        const text = (anchor.innerText || "").replace(/\s+/g, " ").trim();
        if (text) return text;
      }
      const parent = el.parentElement;
      return ((parent && parent.innerText) || "").replace(/\s+/g, " ").trim();
    } catch (e) {
      return "";
    }
  }

  function harvestImages(msg) {
    const limit = Math.min(24, Math.max(1, Number(msg && msg.limit) || 12));
    const seen = new Set();
    const images = [];
    const pageTitle = clip(document.title || "", 80);
    function add(url, alt, el, caption, pageUrl) {
      const abs = absoluteUrl(url);
      if (!abs || abs.indexOf("http") !== 0) return;
      const lower = abs.toLowerCase();
      if (
        lower.indexOf("1x1") >= 0 ||
        lower.indexOf("/pixel") >= 0 ||
        lower.indexOf("favicon") >= 0 ||
        lower.indexOf("sprite") >= 0
      ) {
        return;
      }
      if (seen.has(abs)) return;
      seen.add(abs);
      const altText = clip(alt || "", 80);
      // On an image results page the thumbnail's alt is the *source page* title, not a
      // description of the picture. Report the three labels separately and flag that overlap,
      // so the host can tell "this article mentions X" apart from "this picture shows X".
      images.push({
        src: abs,
        url: abs,
        alt: altText,
        page_title: pageTitle,
        alt_is_page_title: altText.length > 0 && altText === pageTitle,
        caption: clip(caption || captionFor(el) || "", 120),
        // Where this picture actually lives. On an image results page that is the result's source
        // article (Bing hands it over as `purl`); elsewhere it is the page being read. Without it
        // an image in an answer has no provenance and cannot be cited the way a web page is.
        page_url: absoluteUrl(pageUrl || "") || location.href,
      });
    }
    const cards = document.querySelectorAll("a.iusc, a[m], li.iusc, .iuscp a[m], [m]");
    for (let i = 0; i < cards.length && images.length < limit; i++) {
      if (isBingImageChrome(cards[i])) continue;
      const meta = parseBingImageMeta(cards[i].getAttribute("m"));
      if (!meta) continue;
      // Bing's card metadata carries a per-image title in `t`; that describes the picture.
      add(
        meta.murl || meta.turl || meta.purl || "",
        meta.t || cards[i].getAttribute("aria-label") || "",
        cards[i],
        meta.t || "",
        meta.purl || "",
      );
    }
    if (images.length < limit) {
      const scope =
        document.querySelector("#mmComponent, .dgControl, .dg_b, #b_content") || document;
      const imgs = scope.querySelectorAll("img");
      for (let i = 0; i < imgs.length && images.length < limit; i++) {
        const img = imgs[i];
        if (isBingImageChrome(img)) continue;
        const w = img.naturalWidth || img.width || 0;
        const h = img.naturalHeight || img.height || 0;
        if (w > 0 && h > 0 && (w < 48 || h < 48)) continue;
        const src =
          img.currentSrc ||
          img.src ||
          img.getAttribute("data-src") ||
          img.getAttribute("data-original") ||
          img.getAttribute("data-lazy-src") ||
          img.getAttribute("data-mdurl") ||
          "";
        add(src, img.alt || img.getAttribute("aria-label") || img.title || "", img, "");
      }
    }
    if (images.length < limit) {
      const links = document.querySelectorAll("a[href*='murl='], a[href*='imgurl=']");
      for (let i = 0; i < links.length && images.length < limit; i++) {
        if (isBingImageChrome(links[i])) continue;
        try {
          const u = new URL(links[i].href, location.href);
          add(
            u.searchParams.get("murl") || u.searchParams.get("imgurl") || "",
            links[i].getAttribute("aria-label") || links[i].innerText || "",
            links[i],
            "",
            u.searchParams.get("purl") || u.searchParams.get("imgrefurl") || "",
          );
        } catch (e) {}
      }
    }
    return {
      ok: true,
      url: location.href,
      title: document.title,
      images: images.slice(0, limit),
    };
  }

  function harvest(msg) {
    const kind = String((msg && msg.kind) || "links").toLowerCase();
    if (kind === "images" || kind === "image") return harvestImages(msg);
    return harvestLinks(msg);
  }

  function inspect(msg) {
    const raw = msg && (msg.refs || msg.ref);
    const refs = Array.isArray(raw)
      ? raw
      : String(raw || "")
          .split(/[\s,]+/)
          .filter(Boolean);
    if (!refs.length) return { ok: false, errmsg: "Missing ref." };
    const details = refs.slice(0, 8).map(function (ref) {
      const el = byRef(ref);
      if (!el) return { ref: ref, ok: false, errmsg: "Unknown or stale ref" };
      const box = boxOf(el);
      const item = {
        ref: "@e" + idOf(el),
        role: roleOf(el),
        tag: el.tagName.toLowerCase(),
        name: clip(nameOf(el), 80),
        value: clip(displayValue(el), 120),
        type: (el.type || "").toLowerCase(),
        password: isPasswordField(el),
        href: clip(el.getAttribute("href") || "", 120),
        id: clip(el.id || "", 40),
        disabled: !!el.disabled,
        checked: !!el.checked,
        required: !!el.required,
        readonly: !!el.readOnly,
        expanded: el.getAttribute("aria-expanded") || "",
        in_viewport: inViewport(box),
        box: {
          x: Math.round(box.x),
          y: Math.round(box.y),
          w: Math.round(box.w),
          h: Math.round(box.h),
        },
        text: clip(el.innerText || "", INSPECT_TEXT),
      };
      if (el.tagName.toLowerCase() === "select") {
        item.options = Array.from(el.options || [])
          .slice(0, OPTION_MAX)
          .map(function (opt) {
            return {
              value: clip(opt.value, 40),
              label: clip(opt.text, 40),
              selected: !!opt.selected,
            };
          });
        if (el.options && el.options.length > OPTION_MAX) item.options_total = el.options.length;
      }
      return item;
    });
    return { ok: true, url: location.href, details: details };
  }

  function readableRoot(ref) {
    if (ref) {
      const el = byRef(ref);
      if (el) return el;
    }
    return (
      document.querySelector("article, [role='main'], main, #content, .post, .article, [itemprop='articleBody']") ||
      document.body
    );
  }

  function extractText(root) {
    if (!root) return "";
    const clone = root.cloneNode(true);
    clone.querySelectorAll("script,style,nav,footer,noscript,iframe,svg,form").forEach(function (n) {
      n.remove();
    });
    return String(clone.innerText || "")
      .replace(/[ \t]+\n/g, "\n")
      .replace(/\n{3,}/g, "\n\n")
      .trim();
  }

  function extractImages(root) {
    const seen = {};
    const out = [];
    function add(src, alt) {
      if (!src || src.indexOf("data:") === 0) return;
      if (seen[src]) return;
      seen[src] = true;
      out.push({ src: clip(src, 180), alt: clip(alt || "", 80) });
    }
    const og = document.querySelector("meta[property='og:image'], meta[name='twitter:image']");
    if (og) add(og.getAttribute("content") || "", document.title || "");
    (root || document).querySelectorAll("img").forEach(function (img) {
      if (isHidden(img)) return;
      const w = img.naturalWidth || img.width || 0;
      const h = img.naturalHeight || img.height || 0;
      if (w && h && (w < 64 || h < 64)) return;
      add(img.currentSrc || img.src || "", img.getAttribute("alt") || "");
    });
    return out.slice(0, 12);
  }

  function readPage(msg) {
    const offset = Math.max(0, Number(msg && msg.offset) || 0);
    const limit = Math.min(8000, Math.max(200, Number(msg && msg.limit) || READ_MAX));
    const root = readableRoot(msg && msg.ref);
    const full = extractText(root);
    const slice = full.slice(offset, offset + limit);
    return {
      ok: true,
      url: location.href,
      title: document.title,
      offset: offset,
      limit: limit,
      total_chars: full.length,
      truncated: offset + slice.length < full.length,
      text: slice,
      images: extractImages(root),
    };
  }

  function grepPage(msg) {
    const query = String((msg && msg.query) || "");
    if (!query) return { ok: false, errmsg: "Missing query." };
    const max = Math.min(20, Math.max(1, Number(msg && msg.limit) || 8));
    const ctx = Math.min(160, Math.max(24, Number(msg && msg.context) || 72));
    let regex;
    try {
      regex = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "gi");
    } catch (e) {
      return { ok: false, errmsg: "Invalid query." };
    }
    const root = readableRoot(null) || document.body;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const matches = [];
    while (walker.nextNode() && matches.length < max) {
      const node = walker.currentNode;
      const text = node.nodeValue || "";
      regex.lastIndex = 0;
      let found;
      while ((found = regex.exec(text)) && matches.length < max) {
        const el = node.parentElement;
        if (!el || isHidden(el)) continue;
        const heading = nearestHeading(el);
        const start = Math.max(0, found.index - ctx);
        const snippet = clip(text.slice(start, found.index + query.length + ctx), ctx * 2 + query.length);
        const target = el.closest("a, button, input, textarea, select, [role], h1, h2, h3, h4, h5, h6") || el;
        matches.push({
          snippet: snippet,
          heading: heading ? clip(nameOf(heading), 80) : "",
          heading_px: heading ? fontPx(heading) : 0,
          heading_level: heading ? headingLevel(heading) : 0,
          ref: "@e" + idOf(target),
          px: fontPx(el),
        });
      }
    }
    return { ok: true, url: location.href, query: query, total: matches.length, matches: matches };
  }

  function queryAllDeep(root, selector, out) {
    if (!root) return out;
    try {
      const found = root.querySelectorAll(selector);
      for (let i = 0; i < found.length; i++) out.push(found[i]);
    } catch (e) {}
    const walk = function (node) {
      if (!node || node.nodeType !== 1) return;
      if (node.shadowRoot) queryAllDeep(node.shadowRoot, selector, out);
      if (node.tagName && node.tagName.toLowerCase() === "iframe") {
        try {
          if (node.contentDocument) queryAllDeep(node.contentDocument, selector, out);
        } catch (e) {}
      }
      const kids = node.children;
      for (let i = 0; i < kids.length; i++) walk(kids[i]);
    };
    if (root.nodeType === 9) walk(root.documentElement);
    else walk(root);
    return out;
  }

  function formList() {
    resetIfNavigated();
    const nodes = queryAllDeep(document, "input, textarea, select, [contenteditable='true']", []);
    const fields = [];
    nodes.forEach(function (el) {
      const type = (el.type || el.tagName || "").toLowerCase();
      if (type === "hidden" || roleOf(el) === "hidden") return;
      if (isHidden(el)) return;
      const field = {
        ref: "@e" + idOf(el),
        role: roleOf(el),
        type: type,
        name: el.name || "",
        label: clip(nameOf(el), 60),
        autocomplete: el.autocomplete || el.getAttribute("autocomplete") || "",
        placeholder: clip(el.placeholder || "", 40),
        required: !!el.required,
        password: type === "password",
        form: el.form ? (el.form.getAttribute("name") || el.form.id || "") : "",
        value: type === "password" ? (el.value ? "••••" : "") : clip(valueOf(el), 40),
      };
      if (type === "checkbox" || type === "radio") field.checked = !!el.checked;
      if (el.tagName && el.tagName.toLowerCase() === "select") {
        field.options = [].slice.call(el.options || [], 0, 12).map(function (opt) {
          return clip(opt.text || opt.value || "", 40);
        });
      }
      fields.push(field);
    });
    const hasPassword = fields.some(function (f) { return f.password; });
    return {
      ok: true,
      url: location.href,
      title: document.title,
      fields: fields.slice(0, 40),
      has_password_field: hasPassword,
      has_login_form: !!(hasPassword && document.querySelector("form")),
    };
  }

  function usernameHint(field) {
    return /user|email|login|account|phone|tel/.test(
      (field.name + " " + field.label + " " + field.autocomplete + " " + field.type).toLowerCase(),
    );
  }

  function findFormField(spec) {
    if (spec && spec.ref) {
      const by = byRef(spec.ref);
      if (by) return by;
    }
    const listed = formList().fields;
    const hint = String((spec && (spec.name || spec.label || spec.autocomplete || spec.type)) || "").toLowerCase();
    const kind = String((spec && spec.kind) || "").toLowerCase();
    const wanted = String((spec && (spec.value || spec.text)) || "");
    for (let i = 0; i < listed.length; i++) {
      const field = listed[i];
      const el = byRef(field.ref);
      if (!el) continue;
      if (kind === "password" && field.password) return el;
      if (kind === "username" && !field.password && usernameHint(field)) return el;
      if (field.type === "radio" && wanted && String(field.value) === wanted) return el;
      if (hint && (field.name + " " + field.label + " " + field.autocomplete).toLowerCase().indexOf(hint) >= 0) {
        return el;
      }
    }
    if (kind === "password") {
      return document.querySelector("input[type='password']");
    }
    if (kind === "username") {
      return document.querySelector("input[type='email'], input[autocomplete='username'], input[autocomplete='email'], input[name*='user' i], input[name*='email' i]");
    }
    return null;
  }

  function fillOne(el, value) {
    const ref = "@e" + idOf(el);
    const tag = (el.tagName || "").toLowerCase();
    const filled = tag === "select" ? selectRef(ref, value) : fillRef(ref, value);
    return {
      ok: !!filled.ok,
      ref: ref,
      label: clip(nameOf(el), 40),
      type: (el.type || el.tagName || "").toLowerCase(),
    };
  }

  function formFill(msg) {
    const items = (msg && (msg.fields || msg.items)) || [];
    const list = Array.isArray(items) ? items : [];
    const cap = Math.min(Math.max(Number(msg && msg.limit) || 20, 1), 80);
    const results = [];
    list.slice(0, cap).forEach(function (spec) {
      const value = (spec && (spec.value || spec.text)) || "";
      const kind = String((spec && spec.kind) || "").toLowerCase();
      if (spec && spec.all && kind === "password") {
        const passwords = document.querySelectorAll("input[type='password']");
        let any = false;
        passwords.forEach(function (el) {
          if (isHidden(el)) return;
          any = true;
          results.push(fillOne(el, value));
        });
        if (!any) results.push({ ok: false, errmsg: "No matching field", name: "password" });
        return;
      }
      const el = findFormField(spec);
      if (!el) {
        results.push({ ok: false, errmsg: "No matching field", name: (spec && spec.name) || kind });
        return;
      }
      results.push(fillOne(el, value));
    });
    return { ok: true, filled: results.filter(function (r) { return r.ok; }).length, results: results };
  }

  function formSubmit(msg) {
    const ref = msg && msg.ref;
    if (ref) {
      const el = byRef(ref);
      if (!el) return { ok: false, errmsg: "Unknown or stale ref " + ref };
      const tag = (el.tagName || "").toLowerCase();
      if (tag === "form") {
        if (typeof el.requestSubmit === "function") el.requestSubmit();
        else el.submit();
        return { ok: true, ref: ref };
      }
      if (el.form) {
        try {
          if (typeof el.form.requestSubmit === "function") {
            el.form.requestSubmit(el.matches("button, input[type=submit]") ? el : undefined);
          } else {
            el.form.submit();
          }
        } catch (e) {
          el.form.submit();
        }
        return { ok: true, ref: ref };
      }
      realClick(el);
      return { ok: true, ref: ref };
    }
    const pwd = document.querySelector("input[type='password']");
    const form = (pwd && pwd.form) || document.querySelector("form");
    if (form) {
      if (typeof form.requestSubmit === "function") form.requestSubmit();
      else form.submit();
      return { ok: true };
    }
    const btn = document.querySelector("button[type='submit'], input[type='submit'], button:not([type])");
    if (btn && !isHidden(btn)) {
      realClick(btn);
      return { ok: true };
    }
    return { ok: false, errmsg: "No form to submit." };
  }

  function imageInfo(msg) {
    const el = byRef(msg && msg.ref) || document.querySelector("img");
    if (!el) return { ok: false, errmsg: "No image ref." };
    const box = boxOf(el);
    const heading = nearestHeading(el);
    const info = {
      ok: true,
      ref: "@e" + idOf(el),
      role: roleOf(el),
      alt: clip(el.getAttribute("alt") || nameOf(el), 120),
      src: clip(el.currentSrc || el.src || "", 180),
      width: Math.round(box.w),
      height: Math.round(box.h),
      natural_width: el.naturalWidth || 0,
      natural_height: el.naturalHeight || 0,
      heading: heading ? clip(nameOf(heading), 80) : "",
      heading_px: heading ? fontPx(heading) : 0,
    };
    if (el.tagName.toLowerCase() === "img") {
      try {
        const canvas = document.createElement("canvas");
        const maxEdge = 360;
        const nw = el.naturalWidth || box.w || 1;
        const nh = el.naturalHeight || box.h || 1;
        const scale = Math.min(1, maxEdge / Math.max(nw, nh));
        canvas.width = Math.max(1, Math.round(nw * scale));
        canvas.height = Math.max(1, Math.round(nh * scale));
        const ctx = canvas.getContext("2d");
        ctx.drawImage(el, 0, 0, canvas.width, canvas.height);
        const data = canvas.toDataURL("image/jpeg", 0.62);
        info.image_base64 = data.split(",")[1] || "";
        info.image_mime = "image/jpeg";
      } catch (e) {
        info.tainted = true;
      }
    }
    return info;
  }

  function center(el) {
    el.scrollIntoView({ block: "center", inline: "nearest" });
    const r = el.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  }

  function hoverAt(el, p) {
    const opts = { bubbles: true, cancelable: true, view: window, clientX: p.x, clientY: p.y };
    el.dispatchEvent(new MouseEvent("mouseover", opts));
    el.dispatchEvent(new MouseEvent("mouseenter", { bubbles: false, cancelable: true, view: window, clientX: p.x, clientY: p.y }));
    el.dispatchEvent(new MouseEvent("mousemove", opts));
  }

  function realClick(el) {
    const p = center(el);
    hoverAt(el, p);
    const opts = { bubbles: true, cancelable: true, view: window, clientX: p.x, clientY: p.y, button: 0 };
    try {
      el.dispatchEvent(new PointerEvent("pointerdown", opts));
    } catch (e) {}
    el.dispatchEvent(new MouseEvent("mousedown", opts));
    try {
      el.dispatchEvent(new PointerEvent("pointerup", opts));
    } catch (e) {}
    el.dispatchEvent(new MouseEvent("mouseup", opts));
    el.dispatchEvent(new MouseEvent("click", opts));
    if (typeof el.click === "function") el.click();
  }

  function hoverRef(ref) {
    const el = byRef(ref);
    if (!el) return { ok: false, errmsg: "Unknown or stale ref " + ref };
    const p = center(el);
    hoverAt(el, p);
    return { ok: true, ref: ref };
  }

  function clickRef(ref) {
    const el = byRef(ref);
    if (!el) return { ok: false, errmsg: "Unknown or stale ref " + ref };
    realClick(el);
    return { ok: true, ref: ref, url: location.href };
  }

  function setNativeValue(el, value) {
    const tag = el.tagName.toLowerCase();
    const proto = tag === "textarea" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const desc = Object.getOwnPropertyDescriptor(proto, "value");
    if (desc && desc.set) desc.set.call(el, value);
    else el.value = value;
    el.dispatchEvent(new InputEvent("input", { bubbles: true, data: value, inputType: "insertText" }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function fillRef(ref, value) {
    const el = byRef(ref);
    if (!el) return { ok: false, errmsg: "Unknown or stale ref " + ref };
    el.focus();
    const role = roleOf(el);
    if (role === "checkbox" || role === "radio" || role === "switch") {
      const want = /^(1|true|yes|on|checked)$/i.test(String(value));
      if (!!el.checked !== want) realClick(el);
      return { ok: true, ref: ref, checked: !!el.checked };
    }
    if (el.isContentEditable) {
      try {
        document.execCommand("selectAll", false, null);
        document.execCommand("insertText", false, value);
      } catch (e) {
        el.textContent = value;
      }
      el.dispatchEvent(new InputEvent("input", { bubbles: true }));
      return { ok: true, ref: ref };
    }
    if ("value" in el) setNativeValue(el, value);
    else el.textContent = value;
    return { ok: true, ref: ref };
  }

  function selectRef(ref, value) {
    const el = byRef(ref);
    if (!el) return { ok: false, errmsg: "Unknown or stale ref " + ref };
    if (el.tagName.toLowerCase() !== "select") {
      return fillRef(ref, value);
    }
    const raw = String(value || "");
    let matched = false;
    for (let i = 0; i < el.options.length; i++) {
      const opt = el.options[i];
      if (opt.value === raw || opt.text === raw || clip(opt.text, 40) === raw) {
        el.selectedIndex = i;
        matched = true;
        break;
      }
    }
    if (!matched) el.value = raw;
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
    return { ok: true, ref: ref, value: el.value };
  }

  function scrollPage(msg) {
    const ref = msg && msg.ref;
    if (ref) {
      const el = byRef(ref);
      if (!el) return { ok: false, errmsg: "Unknown or stale ref " + ref };
      el.scrollIntoView({ block: (msg && msg.block) || "center", inline: "nearest" });
      return { ok: true, ref: ref };
    }
    const dir = String((msg && msg.direction) || "down").toLowerCase();
    const vh = window.innerHeight || 600;
    const vw = window.innerWidth || 360;
    let dx = 0;
    let dy = 0;
    if (msg && msg.delta != null) dy = Number(msg.delta) || 0;
    else if (dir === "up") dy = -Math.round(vh * 0.85);
    else if (dir === "left") dx = -Math.round(vw * 0.85);
    else if (dir === "right") dx = Math.round(vw * 0.85);
    else dy = Math.round(vh * 0.85);
    window.scrollBy(dx, dy);
    return { ok: true, x: Math.round(window.scrollX), y: Math.round(window.scrollY) };
  }

  function pressKey(msg) {
    const key = String((msg && (msg.key || msg.keys)) || "Enter");
    const el = (msg && msg.ref && byRef(msg.ref)) || document.activeElement || document.body;
    if (msg && msg.ref && el) el.focus();
    const parts = key.split("+");
    const last = parts[parts.length - 1];
    const init = {
      key: last === "Space" ? " " : last,
      code: last,
      bubbles: true,
      cancelable: true,
      ctrlKey: /control|ctrl/i.test(key),
      altKey: /alt/i.test(key),
      shiftKey: /shift/i.test(key),
      metaKey: /meta|cmd|command/i.test(key),
    };
    el.dispatchEvent(new KeyboardEvent("keydown", init));
    el.dispatchEvent(new KeyboardEvent("keypress", init));
    if (last === "Enter" && el.form && typeof el.form.requestSubmit === "function") {
      try {
        el.form.requestSubmit();
      } catch (e) {}
    }
    el.dispatchEvent(new KeyboardEvent("keyup", init));
    return { ok: true, key: key };
  }

  function sleep(ms) {
    return new Promise(function (resolve) {
      setTimeout(resolve, ms);
    });
  }

  async function waitFor(msg) {
    const timeout = Math.min(20000, Math.max(200, Number(msg && msg.timeout_ms) || 8000));
    const text = (msg && msg.text) || "";
    const ref = (msg && msg.ref) || "";
    const wantLoad = !!(msg && msg.load);
    const start = Date.now();
    while (Date.now() - start < timeout) {
      if (wantLoad && (document.readyState === "complete" || document.readyState === "interactive")) {
        await sleep(200);
        return { ok: true, readyState: document.readyState, waited_ms: Date.now() - start };
      }
      if (text && document.body && document.body.innerText.indexOf(text) >= 0) {
        return { ok: true, waited_ms: Date.now() - start };
      }
      if (ref) {
        const el = byRef(ref);
        if (el && !isHidden(el) && inViewport(boxOf(el))) {
          return { ok: true, ref: ref, waited_ms: Date.now() - start };
        }
      }
      if (!wantLoad && !text && !ref && (document.readyState === "complete" || document.readyState === "interactive")) {
        return { ok: true, readyState: document.readyState, waited_ms: Date.now() - start };
      }
      await sleep(150);
    }
    return {
      ok: false,
      code: "timeout",
      errmsg: "Timed out waiting.",
      timed_out: true,
      readyState: document.readyState,
      waited_ms: Date.now() - start,
    };
  }

  function jsDialogs() {
    const api = pageModelContext();
    if (!api || typeof api.dialogSnapshot !== "function") return [];
    try {
      const parsed = JSON.parse(api.dialogSnapshot());
      return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
      return [];
    }
  }

  function dialogOp(msg) {
    const action = String((msg && msg.action) || "list").toLowerCase();
    const html = [];
    const nodes = document.querySelectorAll("dialog, [role='dialog'], [role='alertdialog']");
    for (let i = 0; i < nodes.length; i++) {
      const el = nodes[i];
      const open = el.open === true || el.hasAttribute("open") || !isSubtreeHidden(el);
      if (!open && el.tagName.toLowerCase() !== "dialog") continue;
      if (el.tagName.toLowerCase() === "dialog" && !el.open) continue;
      html.push({
        ref: "@e" + idOf(el),
        role: roleOf(el),
        name: clip(nameOf(el), 80),
        open: el.open === true || el.hasAttribute("open"),
        html: true,
      });
    }
    const prompts = jsDialogs();
    if (action === "list" || !action) {
      return { ok: true, dialogs: html, js_prompts: prompts };
    }
    if (action === "accept" || action === "dismiss" || action === "close") {
      const api = pageModelContext();
      if (api && typeof api.clearDialogs === "function") {
        try {
          api.clearDialogs();
        } catch (e) {}
      }
      const el = byRef(msg && msg.ref) || document.querySelector("dialog[open]");
      if (el && typeof el.close === "function") {
        try {
          el.close();
        } catch (e) {
          try {
            if (typeof el.requestClose === "function") el.requestClose();
          } catch (err) {}
        }
        return { ok: true, handled: "html", ref: "@e" + idOf(el) };
      }
      if (prompts.length) return { ok: true, handled: "js", remaining: 0 };
      if (html.length && action !== "list") {
        return { ok: true, handled: "listed", dialogs: html };
      }
      return { ok: true, handled: "none", dialogs: html, js_prompts: prompts };
    }
    return { ok: false, errmsg: "Unknown dialog action: " + action };
  }

  function serializeJs(value) {
    const seen = new WeakSet();
    try {
      const json = JSON.stringify(value, function (key, v) {
        if (typeof v === "object" && v !== null) {
          if (seen.has(v)) return "[cycle]";
          seen.add(v);
        }
        if (typeof v === "function") return "[function]";
        if (v && v.nodeType === 1) return "<" + v.tagName.toLowerCase() + ">";
        return v;
      });
      return clip(json, JS_MAX);
    } catch (e) {
      return clip(String(value), JS_MAX);
    }
  }

  function pageModelContext() {
    try {
      if (window.wrappedJSObject && window.wrappedJSObject.document) {
        return window.wrappedJSObject.document.modelContext || window.wrappedJSObject.__aetherWebMcp;
      }
    } catch (e) {}
    return document.modelContext || window.__aetherWebMcp;
  }

  function webmcpList() {
    const api = pageModelContext();
    if (!api || typeof api.getTools !== "function") return { ok: true, tools: [] };
    try {
      const tools = (api.getTools() || []).slice(0, 40).map(function (t) {
        return {
          name: t.name,
          description: clip(t.description || "", 120),
        };
      });
      return { ok: true, tools: tools };
    } catch (e) {
      return { ok: false, errmsg: String(e) };
    }
  }

  async function webmcpCall(name, args) {
    const api = pageModelContext();
    if (!api || typeof api.executeTool !== "function") {
      return { ok: false, errmsg: "No document.modelContext on this page." };
    }
    try {
      const result = await api.executeTool(name, args || {});
      return { ok: true, result: serializeJs(result) };
    } catch (e) {
      return { ok: false, errmsg: String(e && e.message ? e.message : e) };
    }
  }

  function settleSleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  /**
   * Wait for a client-rendered page to actually mount. readyState only says the shell finished
   * parsing; a framework that mounts on rAF or an idle callback populates the DOM well after
   * that, and on an inactive tab it may never populate at all.
   */
  async function settlePage(msg) {
    const budget = Math.min(12000, Math.max(500, Number(msg && msg.timeout_ms) || 6000));
    const quiet = Math.min(2000, Math.max(80, Number(msg && msg.quiet_ms) || 350));
    const minNodes = Math.max(0, Number(msg && msg.min_nodes) || 0);
    const deadline = Date.now() + budget;
    let observer = null;
    let lastChange = Date.now();
    try {
      observer = new MutationObserver(function () { lastChange = Date.now(); });
      observer.observe(document.documentElement, {
        childList: true,
        subtree: true,
        characterData: true,
      });
    } catch (e) {
      observer = null;
    }
    let nodes = document.getElementsByTagName("*").length;
    const startNodes = nodes;
    try {
      while (Date.now() < deadline) {
        const now = document.getElementsByTagName("*").length;
        if (now !== nodes) {
          nodes = now;
          lastChange = Date.now();
        }
        if (
          document.readyState === "complete" &&
          Date.now() - lastChange >= quiet &&
          nodes > minNodes
        ) {
          break;
        }
        await settleSleep(50);
      }
    } finally {
      if (observer) {
        try { observer.disconnect(); } catch (e) {}
      }
    }
    return {
      ok: true,
      nodes: nodes,
      grew: nodes - startNodes,
      readyState: document.readyState,
      quiet_for_ms: Date.now() - lastChange,
      settled: Date.now() - lastChange >= quiet,
      timed_out: Date.now() >= deadline,
      visibility: document.visibilityState,
    };
  }

  function pageCapabilities() {
    resetIfNavigated();
    const forms = [];
    queryAllDeep(document, "form", forms);
    const capabilities = forms.slice(0, 30).map(function (form) {
      const fields = Array.from(form.elements || []).filter(function (el) {
        return /^(INPUT|SELECT|TEXTAREA)$/.test(el.tagName) && el.type !== "hidden" && !isHidden(el);
      }).slice(0, 80).map(function (el) {
        return {
          ref: "@e" + idOf(el), name: el.name || "", label: clip(nameOf(el), 100),
          type: el.type || el.tagName.toLowerCase(), required: !!el.required,
          disabled: !!el.disabled, read_only: !!el.readOnly,
          valid: !el.validity || el.validity.valid,
          constraints: { pattern: el.pattern || "", min: el.min || "", max: el.max || "", max_length: el.maxLength == null ? -1 : el.maxLength },
          options: el.options ? Array.from(el.options).slice(0, 80).map(function (o) {
            return { value: o.value, label: o.label, disabled: o.disabled };
          }) : undefined
        };
      });
      return {
        id: DOC_ID + ":form:" + idOf(form), ref: "@e" + idOf(form),
        label: clip(form.getAttribute("aria-label") || form.name || form.id || "Form", 100),
        source: "dom_form", verified: true, verification_scope: "structure_only",
        operation: "fill_and_validate", inputs: fields,
        missing_required: fields.filter(function (f) { return f.required && !f.valid; }).map(function (f) { return f.ref; }),
        completion: { kind: "field_values_and_constraints", business_success: "requires_explicit_evidence" }
      };
    });
    return { ok: true, docId: DOC_ID, url: location.href, title: document.title,
      capabilities: capabilities, website_tools: webmcpList(),
      note: "Verified structure is not proof of business success. Provide explicit completion evidence before submitting." };
  }

  function executeCapability(msg) {
    const prefix = DOC_ID + ":form:";
    const capability = String(msg.capability_id || "");
    if (!capability.startsWith(prefix)) return { ok: false, code: "stale_document", retryable: false };
    const form = byRef("@e" + capability.slice(prefix.length));
    if (!form || form.tagName !== "FORM") return { ok: false, code: "stale_capability", retryable: false };
    if (msg.operation !== "fill_and_validate") return { ok: false, code: "unsupported_operation", retryable: false };
    const fields = msg.fields;
    if (!Array.isArray(fields) || fields.length === 0 || fields.length > 80) return { ok: false, code: "invalid_fields", retryable: false };
    const seen = new Set();
    const resolved = [];
    // Validate the entire plan before the first mutation; never guess an ambiguous field.
    for (const field of fields) {
      const el = byRef(field.ref);
      if (!el || !Array.from(form.elements).includes(el) || seen.has(el) || el.disabled || el.readOnly || isHidden(el)) {
        return { ok: false, code: "invalid_field_target", ref: field.ref, retryable: false };
      }
      if (!Object.prototype.hasOwnProperty.call(field, "value")) return { ok: false, code: "missing_value", ref: field.ref, retryable: false };
      if (el.type === "password" || el.autocomplete === "one-time-code") {
        return { ok: false, code: "input_required", user_takeover: true, reason: "credential", ref: field.ref };
      }
      if (!/^(INPUT|SELECT|TEXTAREA)$/.test(el.tagName) || /^(file|submit|button|reset|hidden|image)$/.test(el.type || "")) {
        return { ok: false, code: "unsupported_field", ref: field.ref, retryable: false };
      }
      seen.add(el);
      resolved.push({ ref: field.ref, value: field.value, el: el });
    }
    const filled = formFill({
      fields: resolved.map(function (item) { return { ref: item.ref, value: item.value }; }),
      limit: resolved.length,
    });
    const results = [];
    for (let i = 0; i < resolved.length; i++) {
      const item = resolved[i];
      const result = (filled.results && filled.results[i]) || { ok: false, ref: item.ref };
      if (!item.el.isConnected) return { ok: false, code: "page_changed", results: results, retryable: false };
      const isCheck = /^(checkbox|radio)$/.test(item.el.type);
      const matches = isCheck ? item.el.checked === /^(1|true|yes|on|checked)$/i.test(String(item.value)) : String(item.el.value) === String(item.value);
      result.verified = !!result.ok && matches && (!item.el.validity || item.el.validity.valid);
      results.push(result);
      if (!result.verified) return { ok: false, code: "value_not_verified", results: results, retryable: false };
    }
    const invalid = Array.from(form.elements).filter(function (el) { return el.willValidate && !el.validity.valid; });
    return { ok: invalid.length === 0, code: invalid.length ? "constraints_unsatisfied" : "values_verified",
      capability_id: capability, operation: "fill_and_validate", results: results,
      missing_or_invalid: invalid.map(function (el) { return "@e" + idOf(el); }),
      submitted: false, business_completed: false, verification_scope: "field_values_and_constraints" };
  }

  async function handle(msg) {
    const op = msg && msg.op;
    if (op === "capabilities") return pageCapabilities();
    if (op === "execute_capability") return executeCapability(msg);
    if (op === "batch") {
      const steps = (msg.steps || []).slice(0, 12);
      const results = [];
      for (let i = 0; i < steps.length; i++) {
        results.push(await handle(steps[i]));
      }
      return { ok: true, steps: results.length, results: results };
    }
    if (op === "settle") return await settlePage(msg);
    if (op === "snapshot") return snapshot(msg);
    if (op === "harvest") return harvest(msg);
    if (op === "inspect") return inspect(msg);
    if (op === "read") return readPage(msg);
    if (op === "grep") return grepPage(msg);
    if (op === "form_list") return formList();
    if (op === "form_fill") return formFill(msg);
    if (op === "form_submit") return formSubmit(msg);
    if (op === "image") return imageInfo(msg);
    if (op === "click") return clickRef(msg.ref);
    if (op === "hover") return hoverRef(msg.ref);
    if (op === "dialog") return dialogOp(msg);
    if (op === "fill") return fillRef(msg.ref, msg.value || msg.text || "");
    if (op === "select") return selectRef(msg.ref, msg.value || msg.text || "");
    if (op === "scroll") return scrollPage(msg);
    if (op === "keys") return pressKey(msg);
    if (op === "wait") return await waitFor(msg);
    if (op === "js") {
      try {
        const value = window.eval(String(msg.expression || ""));
        return { ok: true, value: serializeJs(value) };
      } catch (e) {
        return { ok: false, errmsg: String(e) };
      }
    }
    if (op === "webmcp_list") return webmcpList();
    if (op === "webmcp_call") return await webmcpCall(msg.name, msg.args);
    return { ok: false, errmsg: "Unknown op " + op };
  }

  if (typeof browser !== "undefined" && browser.runtime && browser.runtime.onMessage) {
    browser.runtime.onMessage.addListener(function (msg) {
      return Promise.resolve(handle(msg));
    });
  }

  function announce(reason) {
    if (!nativePort) return;
    try {
      nativePort.postMessage({
        kind: "hello",
        docId: DOC_ID,
        href: location.href,
        title: document.title,
        readyState: document.readyState,
        reason: reason || "connect",
      });
    } catch (e) {}
  }

  function scheduleAnnounce(reason) {
    if (helloTimer) clearTimeout(helloTimer);
    helloTimer = setTimeout(function () {
      helloTimer = 0;
      announce(reason);
    }, 0);
  }

  function stamp(result) {
    if (result && typeof result === "object") {
      result.docId = DOC_ID;
      if (!result.href) result.href = location.href;
      if (!result.readyState) result.readyState = document.readyState;
    }
    return result;
  }

  function openChannel() {
    if (nativePort) return;
    try {
      nativePort = browser.runtime.connectNative("aether");
    } catch (e) {
      nativePort = null;
      setTimeout(openChannel, 500);
      return;
    }
    nativePort.onMessage.addListener(function (msg) {
      const port = nativePort;
      if (!port) return;
      if (msg && msg.op === "ping") {
        try {
          port.postMessage({ id: msg.id, result: stamp({ ok: true }) });
        } catch (e) {}
        return;
      }
      if (msg && msg.docId && msg.docId !== DOC_ID) {
        try {
          port.postMessage({
            id: msg.id,
            result: stamp({
              ok: false,
              code: "stale_document",
              errmsg: "Command targeted a document that is gone.",
            }),
          });
        } catch (e) {}
        return;
      }
      Promise.resolve(handle(msg)).then(function (result) {
        try {
          port.postMessage({ id: msg && msg.id, result: stamp(result) });
        } catch (e) {}
      });
    });
    nativePort.onDisconnect.addListener(function () {
      nativePort = null;
      setTimeout(openChannel, 400);
    });
    announce("connect");
  }

  openChannel();

  document.addEventListener("readystatechange", function () {
    scheduleAnnounce("readystate");
  });
  if (document.readyState !== "complete") {
    window.addEventListener(
      "load",
      function () {
        scheduleAnnounce("load");
      },
      { once: true },
    );
  }
  window.addEventListener("pageshow", function (event) {
    if (event && event.persisted) scheduleAnnounce("bfcache");
  });
  window.addEventListener("pagehide", function () {
    if (!nativePort) return;
    try {
      nativePort.postMessage({ kind: "bye", docId: DOC_ID, href: location.href });
    } catch (e) {}
  });
})();
