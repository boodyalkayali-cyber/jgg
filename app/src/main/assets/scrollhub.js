(function () {
  'use strict';
  if (window.__sh) return;
  var P = window.__SH_PLATFORM || '';
  var SH = window.__sh = {
    store: new Map(),
    frozen: false,
    intercept: window.__SH_INTERCEPT !== false,
    nav: { on: !!window.__SH_NAV_ON, hrefs: window.__SH_NAV_HREFS || [], sels: window.__SH_NAV_SELS || [], hidden: [] }
  };

  /* ===================== 1) التقاط بيانات الوسائط من استجابات الموقع ===================== */
  var MARK = ['video_versions', 'image_versions2', 'carousel_media', 'extended_entities',
              'playable_url', 'browser_native_hd_url', 'browser_native_sd_url'];
  function hasMark(s) { for (var i = 0; i < MARK.length; i++) if (s.indexOf(MARK[i]) > -1) return true; return false; }

  function put(key, items, text, link) {
    if (!key || !items || !items.length) return;
    var old = SH.store.get(key);
    SH.store.set(key, { items: items, text: text || (old && old.text) || '', link: link || (old && old.link) || '' });
    if (SH.store.size > 700) SH.store.delete(SH.store.keys().next().value);
  }

  function igItem(m) {
    if (!m) return null;
    var c = m.image_versions2 && m.image_versions2.candidates;
    var poster = c && c[0] && c[0].url;
    if (m.video_versions && m.video_versions.length) return { t: 'video', u: m.video_versions[0].url, p: poster || '' };
    if (poster) return { t: 'image', u: poster };
    return null;
  }

  function xItem(m) {
    if (!m) return null;
    if (m.type === 'photo' && m.media_url_https) {
      return { t: 'image', u: m.media_url_https + (m.media_url_https.indexOf('?') < 0 ? '?name=large' : '') };
    }
    if ((m.type === 'video' || m.type === 'animated_gif') && m.video_info && m.video_info.variants) {
      var best = null, hls = null;
      m.video_info.variants.forEach(function (v) {
        if (v.content_type === 'video/mp4') { if (!best || (v.bitrate || 0) > (best.bitrate || 0)) best = v; }
        else if (v.content_type === 'application/x-mpegURL') hls = v;
      });
      var u = best ? best.url : (hls && hls.url);
      if (u) return { t: 'video', u: u, p: m.media_url_https || '', hls: !best };
    }
    return null;
  }

  function visit(o) {
    if ((P === 'ig' || P === 'th') && o.code && (o.video_versions || o.image_versions2 || o.carousel_media)) {
      var items = [], i, it;
      if (o.carousel_media && o.carousel_media.length) {
        for (i = 0; i < o.carousel_media.length; i++) { it = igItem(o.carousel_media[i]); if (it) items.push(it); }
      } else { it = igItem(o); if (it) items.push(it); }
      var link = P === 'th' ? location.origin + '/post/' + o.code
               : location.origin + ((items[0] && items[0].t === 'video') ? '/reel/' : '/p/') + o.code + '/';
      put(String(o.code), items, o.caption && o.caption.text, link);
    }
    if (P === 'x' && o.rest_id && o.legacy && o.legacy.extended_entities && o.legacy.extended_entities.media) {
      var xs = [];
      o.legacy.extended_entities.media.forEach(function (m) { var r = xItem(m); if (r) xs.push(r); });
      put(String(o.rest_id), xs, o.legacy.full_text, 'https://x.com/i/status/' + o.rest_id);
    }
    if (P === 'fb') {
      var u = o.browser_native_hd_url || o.playable_url_quality_hd || o.browser_native_sd_url || o.playable_url;
      var id = o.id || o.videoId || o.video_id;
      if (typeof u === 'string' && id) {
        var th = (o.preferred_thumbnail && o.preferred_thumbnail.image && o.preferred_thumbnail.image.uri) ||
                 (o.thumbnailImage && o.thumbnailImage.uri) || '';
        put(String(id), [{ t: 'video', u: u, p: th }], '', 'https://m.facebook.com/reel/' + id);
      }
    }
  }

  function walk(o, depth) {
    if (!o || typeof o !== 'object' || depth > 40) return;
    if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) walk(o[i], depth + 1); return; }
    try { visit(o); } catch (e) {}
    for (var k in o) { var v = o[k]; if (v && typeof v === 'object') walk(v, depth + 1); }
  }

  function scanText(txt) {
    if (!txt || txt.length < 50 || !hasMark(txt)) return;
    txt = txt.replace(/^\s*for \(;;\);/, '');
    try { walk(JSON.parse(txt), 0); return; } catch (e) {}
    var lines = txt.split('\n');
    for (var i = 0; i < lines.length; i++) {
      try { walk(JSON.parse(lines[i].replace(/^\s*for \(;;\);/, '')), 0); } catch (e2) {}
    }
  }

  var seenScripts = new WeakSet();
  function scanInline() {
    var ss = document.querySelectorAll('script[type="application/json"]');
    for (var i = 0; i < ss.length; i++) {
      if (seenScripts.has(ss[i])) continue;
      seenScripts.add(ss[i]);
      scanText(ss[i].textContent);
    }
  }

  var ofetch = window.fetch;
  if (ofetch) {
    window.fetch = function () {
      var p = ofetch.apply(this, arguments);
      try {
        p.then(function (res) {
          try {
            var ct = (res.headers && res.headers.get('content-type')) || '';
            if (/json|javascript|text/.test(ct)) res.clone().text().then(scanText).catch(function () {});
          } catch (e) {}
        }).catch(function () {});
      } catch (e) {}
      return p;
    };
  }
  var xsend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function () {
    this.addEventListener('load', function () {
      try {
        var ct = this.getResponseHeader('content-type') || '';
        if (/json|javascript|text/.test(ct) && (this.responseType === '' || this.responseType === 'text')) scanText(this.responseText);
      } catch (e) {}
    });
    return xsend.apply(this, arguments);
  };

  /* منع تشغيل الصفحة لفيديوهاتها أثناء ظهور مشغّلنا */
  var oplay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    if (SH.frozen) { try { this.pause(); } catch (e) {} return Promise.resolve(); }
    return oplay.apply(this, arguments);
  };
  SH.freeze = function (on) {
    var was = SH.frozen;
    SH.frozen = !!on;
    var vs = document.querySelectorAll('video,audio');
    if (on && !was) {
      for (var i = 0; i < vs.length; i++) { try { vs[i].setAttribute('data-sh-was', vs[i].paused ? '' : '1'); vs[i].pause(); } catch (e) {} }
    } else if (!on && was) {
      for (var j = 0; j < vs.length; j++) { if (vs[j].getAttribute('data-sh-was') === '1') { try { oplay.call(vs[j]); } catch (e) {} } }
    }
  };

  /* ===================== 2) إيجاد المنشور المطابق لعنصر الوسائط المضغوط ===================== */
  var KEY_RES = {
    ig: [/\/(?:p|reel|reels|tv)\/([A-Za-z0-9_-]{5,})/],
    th: [/\/post\/([A-Za-z0-9_-]{5,})/],
    x:  [/\/status\/(\d+)/],
    fb: [/\/reel\/(\d+)/, /[?&]v=(\d+)/, /\/videos\/(?:[^\/]+\/)?(\d+)/, /\/(?:posts|permalink)\/(\d+)/, /story_fbid=(\d+)/]
  };
  function keyFrom(s) {
    var rs = KEY_RES[P] || [];
    for (var i = 0; i < rs.length; i++) { var m = rs[i].exec(s); if (m) return m[1]; }
    return null;
  }
  function findKey(el) {
    var n = el, depth = 0;
    while (n && n !== document.body && depth < 14) {
      if (n.tagName === 'A') { var k0 = keyFrom(n.getAttribute('href') || ''); if (k0) return k0; }
      if (n.querySelectorAll) {
        var as = n.querySelectorAll('a[href]');
        for (var i = 0; i < as.length && i < 80; i++) {
          var k = keyFrom(as[i].getAttribute('href') || '');
          if (k) return k;
        }
      }
      n = n.parentElement; depth++;
    }
    return null;
  }
  function basename(u) { try { return u.split('?')[0].split('/').pop(); } catch (e) { return u; } }
  function matchIndex(el, items) {
    var cur = el.currentSrc || el.src || el.poster || '';
    var b = basename(cur);
    if (b) {
      for (var i = 0; i < items.length; i++) {
        if (basename(items[i].u) === b || (items[i].p && basename(items[i].p) === b)) return i;
      }
    }
    return 0;
  }

  SH.reelList = function () {
    var out = [];
    SH.store.forEach(function (ent, key) {
      var v = null;
      for (var i = 0; i < ent.items.length; i++) if (ent.items[i].t === 'video') { v = ent.items[i]; break; }
      if (v) out.push({ key: key, t: 'video', u: v.u, p: v.p || '', link: ent.link || '', hls: !!v.hls });
    });
    return out;
  };
  SH.more = function () {
    var vs = document.querySelectorAll('video');
    if (!vs.length) return;
    try { vs[vs.length - 1].scrollIntoView({ block: 'center' }); } catch (e) {}
  };

  function pickMedia(e) {
    var stack = document.elementsFromPoint ? document.elementsFromPoint(e.clientX, e.clientY) : [e.target];
    var mi = -1, el = null, i;
    for (i = 0; i < stack.length; i++) {
      if (stack[i].tagName === 'VIDEO' || stack[i].tagName === 'IMG') { mi = i; el = stack[i]; break; }
    }
    if (!el) return null;
    var mr = el.getBoundingClientRect();
    if (mr.width < 180 || mr.height < 120) return null;
    for (i = 0; i < mi; i++) {
      var b = stack[i].closest && stack[i].closest('button,[role="button"],input,textarea,select');
      if (b) {
        var br = b.getBoundingClientRect();
        if (br.width * br.height < 0.5 * mr.width * mr.height) return null; // زر صغير (إعجاب/مشاركة...)
      }
    }
    return el;
  }

  function build(el) {
    scanInline();
    var path = location.pathname, isVid = el.tagName === 'VIDEO', i;
    var reelPage = /\/reels?(\/|$)/.test(path) || /\/shorts/.test(path) || (P === 'fb' && /^\/watch/.test(path));
    var key = (isVid && reelPage ? keyFrom(path + location.search) : null) || findKey(el) || keyFrom(path + location.search);

    if (isVid && reelPage) {
      var list = SH.reelList(), idx = -1;
      for (i = 0; i < list.length; i++) if (list[i].key === key) { idx = i; break; }
      if (idx < 0) return null;
      return { mode: 'v', index: idx, items: list };
    }

    var ent = key ? SH.store.get(key) : null;
    if (ent) {
      var items = ent.items.map(function (m) {
        return { key: key, t: m.t, u: m.u, p: m.p || '', link: ent.link || '', hls: !!m.hls };
      });
      return { mode: 'h', index: matchIndex(el, items), items: items };
    }

    if (isVid) {
      var s = el.currentSrc || el.src;
      if (s && /^https?:/.test(s)) {
        return { mode: 'h', index: 0, items: [{ key: key || '', t: 'video', u: s, p: el.poster || '', link: location.href, hls: /\.m3u8/.test(s) }] };
      }
      return null; // فيديو مشفّر (blob) بلا رابط معروف: يعمل مشغّل الموقع
    }

    var box = el.closest('article,[role="article"]');
    var imgs = box ? box.querySelectorAll('img') : [el];
    var out = [], seen = {}, cur = 0;
    for (i = 0; i < imgs.length && out.length < 30; i++) {
      var im = imgs[i], r = im.getBoundingClientRect();
      var u = im.currentSrc || im.src;
      if (!u || !/^https?:/.test(u) || seen[u] || r.width < 180 || r.height < 120) continue;
      seen[u] = 1;
      if (im === el) cur = out.length;
      out.push({ key: key || '', t: 'image', u: u, p: '', link: location.href, hls: false });
    }
    if (!out.length) return null;
    return { mode: 'h', index: cur, items: out };
  }

  document.addEventListener('click', function (e) {
    if (!SH.intercept || !e.isTrusted || !window.ScrollHubBridge) return;
    try {
      var el = pickMedia(e);
      if (!el) return;
      var res = build(el);
      if (!res || !res.items || !res.items.length) return;
      e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
      window.ScrollHubBridge.openMedia(JSON.stringify(res));
    } catch (err) {}
  }, true);

  /* ===================== 3) إخفاء شريط التبويبات الأصلي للموقع ===================== */
  function isBar(el) {
    var r = el.getBoundingClientRect();
    if (r.height > 170 || r.height < 20 || r.width < window.innerWidth * 0.6) return false;
    if (r.top + window.scrollY < 340) return true;
    var n = el, k = 0;
    while (n && k < 4) {
      var pos = getComputedStyle(n).position;
      if (pos === 'fixed' || pos === 'sticky') return true;
      n = n.parentElement; k++;
    }
    return false;
  }
  function hideEl(el) {
    if (el.getAttribute('data-sh-hid')) return;
    el.setAttribute('data-sh-hid', '1');
    el.style.setProperty('display', 'none', 'important');
    SH.nav.hidden.push(el);
  }
  function hideNav() {
    if (!SH.nav.on) return;
    var i, j;
    for (i = 0; i < SH.nav.sels.length; i++) {
      var list = document.querySelectorAll(SH.nav.sels[i]);
      for (j = 0; j < list.length; j++) if (isBar(list[j])) hideEl(list[j]);
    }
    if (!SH.nav.hrefs.length) return;
    var as = document.querySelectorAll('a[href]'), counts = new Map();
    for (i = 0; i < as.length; i++) {
      var a = as[i];
      var path = (a.getAttribute('href') || '').replace(location.origin, '').split('?')[0].split('#')[0];
      var ok = false;
      for (j = 0; j < SH.nav.hrefs.length; j++) { if (path.indexOf(SH.nav.hrefs[j]) === 0) { ok = true; break; } }
      if (!ok) continue;
      var r = a.getBoundingClientRect();
      if (r.width === 0 || r.width > 220 || r.height > 110) continue;
      var n = a.parentElement, k = 0;
      while (n && k < 8) {
        if (isBar(n)) { counts.set(n, (counts.get(n) || 0) + 1); break; }
        n = n.parentElement; k++;
      }
    }
    counts.forEach(function (c, el) { if (c >= 3) hideEl(el); });
  }
  var navTimer = null;
  function schedule() {
    if (navTimer) return;
    navTimer = setTimeout(function () { navTimer = null; try { hideNav(); } catch (e) {} }, 350);
  }
  function startObserver() {
    if (!document.documentElement) return;
    new MutationObserver(function () { if (SH.nav.on) schedule(); })
      .observe(document.documentElement, { childList: true, subtree: true });
    schedule();
  }
  if (document.documentElement) startObserver(); else document.addEventListener('DOMContentLoaded', startObserver);
  window.addEventListener('load', schedule);
  SH.setNav = function (on) {
    SH.nav.on = !!on;
    if (on) schedule();
    else {
      SH.nav.hidden.forEach(function (el) { el.style.removeProperty('display'); el.removeAttribute('data-sh-hid'); });
      SH.nav.hidden = [];
    }
  };
})();
