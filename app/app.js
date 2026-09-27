/*
 * app.js — Kurdish Proverbs web app (iPhone / iPad / any browser).
 * Screens and behaviour follow the Android app 2.0.
 *
 *   start → (iPhone in Safari: install guide) → registration → dictionary download → main list
 *
 * Settings, favourites and registration: localStorage.
 * The encrypted dictionary: IndexedDB (kept on the device, works offline).
 */
(function () {
  'use strict';

  // ───────────────────────── configuration ─────────────────────────
  var CFG = {
    version: '2.0.0',
    registrationUrl: 'https://script.google.com/macros/s/AKfycbySFYsh1_b5FrBat_nINGnxV1DnKrJAsCsuUzBzCuFAZHHxNZcR4ZBqBEMn0Nk2RKE-/exec',
    website: 'https://tecush.github.io/Comparative-Dictionary-of-Proverbs-Kurdish-Persian-Turkish-/',
    androidApk: 'https://tecush.github.io/Comparative-Dictionary-of-Proverbs-Kurdish-Persian-Turkish-/download',
    email: 'kurdishproverbs2026@gmail.com',
    dbInfo: 'data/db.json'
  };
  // database key (lightly obfuscated; the same kp.dbKey as in secrets.properties)
  var DBK = (function () {
    var m = 'KurdishProverbs-Khalil-Mohammadi', h = '1f3e2b1d10354531371b372445322c552c50380159097a7f37042a392754261107581d2d074105331e3c4e', o = '';
    for (var i = 0; i < h.length; i += 2) o += String.fromCharCode(parseInt(h.substr(i, 2), 16) ^ m.charCodeAt((i / 2) % m.length));
    return o;
  })();

  var LANGS = ['ckb', 'kmr', 'fa', 'tr', 'en'];
  var LANG_NAMES = {ckb: 'کوردی (سۆرانی)', kmr: 'Kurdî (Kurmancî)', fa: 'فارسی', tr: 'Türkçe', en: 'English'};
  var DIALECT_COLORS = {
    light: ['#140DCC', '#BD0104', '#1078A2', '#AA5C10', '#037F6E', '#28811F', '#903AFF', '#2E6103', '#C22468', '#6D4C41', '#00695C', '#AD1457'],
    dark: ['#8DDCFF', '#FF8486', '#6BFFEB', '#FFDB33', '#81FF8C', '#FFAD66', '#CC8FFF', '#D9FFD8', '#FFD3FE', '#D7CCC8', '#80CBC4', '#F48FB1']
  };

  // ───────────────────────── small helpers ─────────────────────────
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var app = document.getElementById('app');
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]; }); }
  function ls(k, d) { try { var v = localStorage.getItem('kp_' + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } }
  function lsSet(k, v) { try { localStorage.setItem('kp_' + k, JSON.stringify(v)); } catch (e) {} }

  var ua = navigator.userAgent;
  var isIOS = /iPad|iPhone|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  var isAndroid = /Android/.test(ua);
  var isStandalone = window.navigator.standalone === true || (window.matchMedia && matchMedia('(display-mode: standalone)').matches);
  var isInApp = /FBAN|FBAV|Instagram|Line\/|Telegram|WhatsApp|Snapchat|TikTok|GSA\//.test(ua);
  var isIOSSafari = isIOS && /Safari/.test(ua) && !/CriOS|FxiOS|EdgiOS|OPiOS/.test(ua) && !isInApp;

  // ───────────────────────── settings ─────────────────────────
  function defaultLang() {
    var n = (navigator.language || 'en').toLowerCase();
    if (n.indexOf('ckb') === 0 || n === 'ku-iq' || n === 'ku-arab') return 'ckb';
    if (n.indexOf('ku') === 0 || n.indexOf('kmr') === 0) return 'kmr';
    if (n.indexOf('fa') === 0) return 'fa';
    if (n.indexOf('tr') === 0) return 'tr';
    if (n.indexOf('ar') === 0) return 'ckb';
    return 'en';
  }
  var P = {
    lang: ls('lang', null) || defaultLang(),
    theme: ls('theme', 0), scale: ls('scale', 1), showDialect: ls('showDialect', true),
    colorDialect: ls('colorDialect', false), daily: ls('daily', true), sort: ls('sort', 0), dialect: ls('dialect', 0)
  };
  function setPref(k, v) { P[k] = v; lsSet(k, v); }
  function favs() { return ls('favorites', []); }
  function isFav(id) { return favs().indexOf(id) >= 0; }
  function toggleFav(id) {
    var f = favs(), i = f.indexOf(id);
    if (i >= 0) f.splice(i, 1); else f.unshift(id);
    lsSet('favorites', f); return i < 0;
  }

  // ───────────────────────── texts ─────────────────────────
  function T(key) {
    var s = (KP_I18N[P.lang] && KP_I18N[P.lang][key]) || KP_I18N.en[key] || key;
    for (var i = 1; i < arguments.length; i++) s = s.split('%' + i + '$s').join(arguments[i]);
    return s.split('%%').join('%');
  }
  function rtl() { return P.lang === 'ckb' || P.lang === 'fa'; }
  function num(n) {
    var s = Number(n).toLocaleString('en-US');
    if (P.lang === 'ckb') return s.replace(/\d/g, function (d) { return '٠١٢٣٤٥٦٧٨٩'[d]; }).replace(/,/g, '٬');
    if (P.lang === 'fa') return s.replace(/\d/g, function (d) { return '۰۱۲۳۴۵۶۷۸۹'[d]; }).replace(/,/g, '٬');
    return s;
  }
  function mb(bytes) { return (bytes / 1048576).toFixed(1) + ' MB'; }
  function isDark() {
    return P.theme === 2 || (P.theme === 0 && window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches);
  }
  function dColor(id) { var a = DIALECT_COLORS[isDark() ? 'dark' : 'light']; return id > 0 ? a[(id - 1) % a.length] : 'var(--text2)'; }
  function applyLook() {
    document.documentElement.lang = P.lang;
    document.documentElement.dir = rtl() ? 'rtl' : 'ltr';
    document.documentElement.dataset.theme = P.theme === 1 ? 'light' : P.theme === 2 ? 'dark' : 'auto';
    document.documentElement.style.setProperty('--scale', P.scale);
    document.title = T('app_name');
  }

  // ───────────────────────── IndexedDB (dictionary file) ─────────────────────────
  function idb() {
    return new Promise(function (res, rej) {
      var r = indexedDB.open('kp', 1);
      r.onupgradeneeded = function () { r.result.createObjectStore('files'); };
      r.onsuccess = function () { res(r.result); };
      r.onerror = function () { rej(r.error); };
    });
  }
  function idbGet(key) {
    return idb().then(function (db) { return new Promise(function (res, rej) {
      var q = db.transaction('files').objectStore('files').get(key);
      q.onsuccess = function () { res(q.result); }; q.onerror = function () { rej(q.error); };
    }); });
  }
  function idbPut(key, val) {
    return idb().then(function (db) { return new Promise(function (res, rej) {
      var tx = db.transaction('files', 'readwrite'); tx.objectStore('files').put(val, key);
      tx.oncomplete = function () { res(); }; tx.onerror = function () { rej(tx.error); };
    }); });
  }

  // ───────────────────────── UI pieces ─────────────────────────
  var ICON = {
    back: '<svg viewBox="0 0 24 24" class="mirror"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>',
    menu: '<svg viewBox="0 0 24 24"><path d="M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z"/></svg>',
    home: '<svg viewBox="0 0 24 24"><path d="M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"/></svg>',
    star: '<svg viewBox="0 0 24 24"><path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"/></svg>',
    starO: '<svg viewBox="0 0 24 24"><path d="M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z"/></svg>',
    stats: '<svg viewBox="0 0 24 24"><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z"/></svg>',
    share: '<svg viewBox="0 0 24 24"><path d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z"/></svg>',
    copy: '<svg viewBox="0 0 24 24"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>',
    search: '<svg viewBox="0 0 24 24"><path d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg>',
    clear: '<svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>',
    iosShare: '<svg viewBox="0 0 24 24" class="inl"><path d="M16 5l-1.42 1.42-1.59-1.59V16h-1.98V4.83L9.42 6.42 8 5l4-4 4 4zm4 5v11c0 1.1-.9 2-2 2H6c-1.11 0-2-.9-2-2V10c0-1.11.89-2 2-2h3v2H6v11h12V10h-3V8h3c1.1 0 2 .89 2 2z"/></svg>',
    today: '<svg viewBox="0 0 24 24"><path d="M19 4h-1V2h-2v2H8V2H6v2H5c-1.11 0-1.99.9-1.99 2L3 20a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 16H5V9h14v11zM7 11h5v5H7z"/></svg>'
  };

  function titlebar(title, opts) {
    opts = opts || {};
    var nav = opts.menu ? '<button class="tb" data-act="menu" aria-label="' + esc(T('menu')) + '">' + ICON.menu + '</button>'
      : opts.back ? '<button class="tb" data-act="back" aria-label="' + esc(T('back')) + '">' + ICON.back + '</button>'
      : '<span class="tb"></span>';
    var acts = (opts.actions || []).map(function (a) {
      return '<button class="tb" data-act="' + a[0] + '" aria-label="' + esc(T(a[2])) + '">' + ICON[a[1]] + '</button>';
    }).join('');
    return '<header class="titlebar">' + nav + '<h1>' + esc(title) + '</h1><div class="acts">' + acts + '</div></header>';
  }

  var toastTimer;
  function toast(msg) {
    var t = $('#toast') || document.body.appendChild(Object.assign(document.createElement('div'), {id: 'toast'}));
    t.textContent = msg; t.className = 'show';
    clearTimeout(toastTimer); toastTimer = setTimeout(function () { t.className = ''; }, 2600);
  }

  function sheet(title, items) {           // items: [[label, fn], ...]
    var ov = document.createElement('div');
    ov.className = 'sheet-ov';
    ov.innerHTML = '<div class="sheet"><div class="sheet-title" dir="rtl">' + esc(title) + '</div>' +
      items.map(function (it, i) { return '<button data-i="' + i + '">' + esc(it[0]) + '</button>'; }).join('') +
      '<button class="cancel">' + esc(T('cancel')) + '</button></div>';
    document.body.appendChild(ov);
    requestAnimationFrame(function () { ov.classList.add('open'); });
    function close() { ov.classList.remove('open'); setTimeout(function () { ov.remove(); }, 200); }
    ov.addEventListener('click', function (e) {
      var b = e.target.closest('button');
      if (e.target === ov || (b && b.classList.contains('cancel'))) return close();
      if (b && b.dataset.i != null) { close(); items[+b.dataset.i][1](); }
    });
  }

  function alertBox(msg, buttons) {         // buttons: [[label, fn], ...]
    var ov = document.createElement('div');
    ov.className = 'sheet-ov center';
    ov.innerHTML = '<div class="dialog"><p>' + esc(msg).replace(/\n/g, '<br>') + '</p><div class="dlg-btns">' +
      (buttons || [[T('ok'), null]]).map(function (b, i) { return '<button data-i="' + i + '">' + esc(b[0]) + '</button>'; }).join('') + '</div></div>';
    document.body.appendChild(ov);
    requestAnimationFrame(function () { ov.classList.add('open'); });
    ov.addEventListener('click', function (e) {
      var b = e.target.closest('button[data-i]');
      if (!b) return;
      ov.remove();
      var f = (buttons || [])[+b.dataset.i]; if (f && f[1]) f[1]();
    });
  }

  function copyText(s) {
    (navigator.clipboard ? navigator.clipboard.writeText(s) : Promise.reject()).then(function () { toast(T('copied')); }, function () {
      var ta = document.createElement('textarea'); ta.value = s; document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); toast(T('copied')); } catch (e) {} ta.remove();
    });
  }
  function shareText(s) {
    if (navigator.share) navigator.share({text: s}).catch(function () {});
    else copyText(s);
  }
  function shareProverb(s) { shareText(s + '\n\n' + T('share_footer', CFG.website)); }

  function proverbActions(pid, text, cid) {
    var items = [
      [T('copy'), function () { copyText(text); }],
      [T('share'), function () { shareProverb(text); }],
      [T(isFav(pid) ? 'favorite_remove' : 'favorite_add'), function () {
        toast(T(toggleFav(pid) ? 'favorite_added' : 'favorite_removed'));
        if (route.name === 'fav') render();
      }],
      [T('details'), function () { go('#/p/' + pid); }]
    ];
    if (cid && route.name !== 'c') items.push([T('open_collection'), function () { go('#/c/' + cid + '/p' + pid); }]);
    sheet(text.length > 60 ? text.slice(0, 57) + '…' : text, items);
  }

  // long press on list rows (touch and mouse)
  function enableLongPress(el, onLong) {
    var timer = null, sx = 0, sy = 0, fired = false;
    el.addEventListener('pointerdown', function (e) {
      fired = false; sx = e.clientX; sy = e.clientY;
      var target = e.target;
      timer = setTimeout(function () { fired = true; onLong(target); }, 550);
    });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (ev) { el.addEventListener(ev, function () { clearTimeout(timer); }); });
    el.addEventListener('pointermove', function (e) { if (Math.abs(e.clientX - sx) > 10 || Math.abs(e.clientY - sy) > 10) clearTimeout(timer); });
    el.addEventListener('click', function (e) { if (fired) { e.stopPropagation(); e.preventDefault(); fired = false; } }, true);
    el.addEventListener('contextmenu', function (e) { e.preventDefault(); onLong(e.target); });
  }

  // highlight search words (character-by-character folding keeps positions)
  function fold(s) {
    var o = '';
    for (var i = 0; i < s.length; i++) {
      var c = s[i].toLowerCase();
      if (c.charCodeAt(0) > 127) { var d = c.normalize('NFKD'); if (d.length) c = d[0]; }
      if (c === '\u064A' || c === '\u0649') c = '\u06CC'; else if (c === '\u0643') c = '\u06A9'; else if (c === '\u0131') c = 'i';
      o += c.length === 1 ? c : s[i];
    }
    return o;
  }
  function highlight(text, raw) {
    if (!raw) return esc(text);
    var f = fold(text), words = fold(raw).split(/[\s.,;:!?،؛؟()«»"']+/).filter(Boolean), marks = [];
    words.forEach(function (w) { var i = 0; while ((i = f.indexOf(w, i)) >= 0) { marks.push([i, i + w.length]); i += w.length; } });
    if (!marks.length) return esc(text);
    marks.sort(function (a, b) { return a[0] - b[0]; });
    var out = '', pos = 0;
    marks.forEach(function (m) { if (m[0] < pos) return; out += esc(text.slice(pos, m[0])) + '<mark>' + esc(text.slice(m[0], m[1])) + '</mark>'; pos = m[1]; });
    return out + esc(text.slice(pos));
  }

  // ───────────────────────── state ─────────────────────────
  var store = null, dialectNames = {}, route = {name: 'main'}, searchQuery = '', mainScroll = 0, deferredPrompt = null;

  function dname(id) { return dialectNames[id]; }
  function buildDialectNames() {
    dialectNames = {};
    store.dialects().forEach(function (d) {
      dialectNames[d.id] = (rtl() || !d.nameEn) ? (d.nameKu || '#' + d.id) : d.nameEn;
    });
  }

  // ───────────────────────── router ─────────────────────────
  function go(hash) { if (location.hash === hash) render(); else location.hash = hash; }
  function parseRoute() {
    var h = location.hash.replace(/^#\/?/, '').split('/');
    switch (h[0]) {
      case 'c': return {name: 'c', id: +h[1], target: h[2] || ''};
      case 'p': return {name: 'p', id: +h[1]};
      case 'fav': return {name: 'fav'};
      case 'stats': return {name: 'stats'};
      case 'settings': return {name: 'settings'};
      case 'about': return {name: 'about'};
      default: return {name: 'main'};
    }
  }
  window.addEventListener('hashchange', function () { if (store) render(); });

  function render() {
    if (route.name === 'main' && $('#list')) mainScroll = $('#list').scrollTop;
    route = parseRoute();
    closeDrawer();
    ({main: renderMain, c: renderCollection, p: renderProverb, fav: renderFavorites,
      stats: renderStats, settings: renderSettings, about: renderAbout})[route.name]();
  }

  // global clicks for title bar buttons
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-act]');
    if (!b) return;
    var a = b.dataset.act;
    if (a === 'back') { if (history.length > 1) history.back(); else go('#/'); }
    else if (a === 'home') go('#/');
    else if (a === 'menu') openDrawer();
    else if (a === 'fav') go('#/fav');
    else if (a === 'stats') go('#/stats');
  });

  // ───────────────────────── first-run screens ─────────────────────────
  function langChips(onChange) {
    return '<div class="chips center">' + LANGS.map(function (l) {
      return '<button class="chip' + (l === P.lang ? ' on' : '') + '" data-lang="' + l + '">' + esc(LANG_NAMES[l]) + '</button>';
    }).join('') + '</div>';
  }
  function bindLangChips(root, rerender) {
    root.querySelectorAll('[data-lang]').forEach(function (b) {
      b.addEventListener('click', function () { setPref('lang', b.dataset.lang); applyLook(); rerender(); });
    });
  }

  function screenInstall(onSkip) {
    var link = location.href.split('#')[0];
    app.innerHTML = titlebar(T('install_title')) +
      '<main class="page narrow"><img class="logo" src="icons/icon-192.png" alt="">' +
      '<h2 class="center">' + esc(T('brand_title')) + '</h2>' + langChips() +
      '<p class="lead">' + esc(T('install_intro')) + '</p>' +
      (isInApp || !isIOSSafari && isIOS
        ? '<div class="card warn"><p>' + esc(T('install_inapp')) + '</p><div class="linkbox" dir="ltr">' + esc(link) + '</div>' +
          '<button class="btn" id="copyLink">' + esc(T('copy_link')) + '</button></div>'
        : '<ol class="steps"><li>' + T('install_step1', ICON.iosShare) + '</li><li>' + esc(T('install_step2')) + '</li><li>' + esc(T('install_step3')) + '</li></ol>') +
      '<button class="linkbtn" id="skip">' + esc(T('install_browser')) + '</button></main>' +
      (isIOSSafari ? '<div class="arrow ' + (/iPad/.test(ua) || (navigator.maxTouchPoints > 1 && !/iPhone/.test(ua)) ? 'top' : 'bottom') + '">' + ICON.iosShare + '</div>' : '');
    bindLangChips(app, function () { screenInstall(onSkip); });
    var cl = $('#copyLink'); if (cl) cl.onclick = function () { copyText(link); toast(T('link_copied')); };
    $('#skip').onclick = function () { lsSet('skipInstall', true); onSkip(); };
  }

  function registered() { var r = ls('reg', null); return !!(r && r.token); }
  function deviceId() {
    var d = ls('device', null);
    if (!d) { d = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random()).replace(/-/g, '').slice(0, 32); lsSet('device', d); }
    return d;
  }
  function server(body) {
    body.device_id = deviceId();
    body.platform = isIOS ? 'ios' : isAndroid ? 'android-web' : 'web';
    body.device_model = isIOS ? (/iPad/.test(ua) ? 'iPad' : 'iPhone') : (navigator.platform || 'browser');
    body.android = '';
    body.app_version = 'web-' + CFG.version;
    body.lang = P.lang;
    return fetch(CFG.registrationUrl, {method: 'POST', headers: {'Content-Type': 'text/plain;charset=utf-8'}, body: JSON.stringify(body)})
      .then(function (r) { return r.text(); })
      .then(function (t) {
        var j; try { j = JSON.parse(t); } catch (e) { throw new Error('network'); }
        if (!j.ok) { var er = new Error(j.error || 'server_error'); er.server = true; throw er; }
        return j;
      });
  }

  function screenRegister(done) {
    var step = 1, resendAt = 0, timer;
    function describe(e) {
      if (!e.server) return T('network_error');
      return {bad_code: T('register_invalid_code'), expired: T('register_invalid_code'), invalid_email: T('register_invalid_email'),
              rate_limited: T('register_rate_limited')}[e.message] || T('register_error', e.message);
    }
    function draw() {
      var name = ($('#rName') || {}).value || '', email = ($('#rEmail') || {}).value || '';
      app.innerHTML = titlebar(T('register_title')) +
        '<main class="page narrow"><img class="logo" src="icons/icon-192.png" alt="">' +
        '<h2 class="center">' + esc(T('brand_title')) + '</h2><p class="center sub">' + esc(T('brand_subtitle')) + '</p>' +
        langChips() + '<p>' + esc(T('register_body')) + '</p>' +
        '<div id="s1"' + (step === 1 ? '' : ' hidden') + '>' +
        '<label class="field"><span>' + esc(T('register_name')) + '</span><input id="rName" autocomplete="name" value="' + esc(name) + '"></label>' +
        '<label class="field"><span>' + esc(T('register_email')) + '</span><input id="rEmail" type="email" dir="ltr" autocomplete="email" value="' + esc(email) + '"></label>' +
        '<button class="btn" id="send">' + esc(T('register_send_code')) + '</button></div>' +
        '<div id="s2"' + (step === 2 ? '' : ' hidden') + '><p>' + esc(T('register_code_sent', email)) + '</p>' +
        '<label class="field"><span>' + esc(T('register_code')) + '</span><input id="rCode" inputmode="numeric" maxlength="6" class="code" dir="ltr"></label>' +
        '<button class="btn" id="verify">' + esc(T('register_verify')) + '</button>' +
        '<div class="row2"><button class="linkbtn" id="resend"></button><button class="linkbtn" id="change">' + esc(T('register_change_email')) + '</button></div></div>' +
        '<div class="spinner" id="busy" hidden></div><p class="error" id="err" hidden></p>' +
        '<button class="linkbtn" id="privacy">' + esc(T('privacy')) + '</button></main>';
      bindLangChips(app, draw);
      $('#privacy').onclick = function () { window.open(CFG.website + 'privacy', '_blank'); };
      $('#send').onclick = send; $('#verify').onclick = verify; $('#resend').onclick = send;
      $('#change').onclick = function () { step = 1; draw(); };
      tick();
    }
    function tick() {
      var b = $('#resend'); if (!b) return;
      var left = Math.ceil((resendAt - Date.now()) / 1000);
      if (left > 0) { b.disabled = true; b.textContent = T('register_resend_wait', num(left)); clearTimeout(timer); timer = setTimeout(tick, 1000); }
      else { b.disabled = false; b.textContent = T('register_resend'); }
    }
    function busy(on) { $('#busy').hidden = !on; app.querySelectorAll('.btn').forEach(function (b) { b.disabled = on; }); if (on) $('#err').hidden = true; }
    function err(m) { var e = $('#err'); e.textContent = m; e.hidden = false; }
    function send() {
      var name = $('#rName').value.trim(), email = $('#rEmail').value.trim();
      if (name.length < 2) return err(T('register_invalid_name'));
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(email)) return err(T('register_invalid_email'));
      busy(true);
      server({action: 'request_code', name: name, email: email.toLowerCase()}).then(function () {
        busy(false); step = 2; resendAt = Date.now() + 60000; draw(); $('#rCode').focus();
      }, function (e) { busy(false); err(describe(e)); });
    }
    function verify() {
      var code = $('#rCode').value.trim(), name = $('#rName').value.trim(), email = $('#rEmail').value.trim().toLowerCase();
      if (!/^\d{6}$/.test(code)) return err(T('register_invalid_code'));
      busy(true);
      server({action: 'verify', name: name, email: email, code: code}).then(function (j) {
        lsSet('reg', {name: name, email: email, token: j.token, at: Date.now()});
        toast(T('register_done')); done();
      }, function (e) { busy(false); err(describe(e)); });
    }
    draw();
  }

  function fetchDbInfo() {
    return fetch(CFG.dbInfo + '?t=' + Math.floor(Date.now() / 60000), {cache: 'no-store'})
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); });
  }

  /** Downloads data/<file>, checks it, stores it. Progress callback gets (done, total). */
  function downloadDb(info, onProgress) {
    return fetch('data/' + info.file + '?v=' + encodeURIComponent(info.version), {cache: 'no-store'}).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      var total = info.size || +r.headers.get('Content-Length') || 0, done = 0, parts = [];
      var reader = r.body.getReader();
      function pump() {
        return reader.read().then(function (x) {
          if (x.done) return;
          parts.push(x.value); done += x.value.length; onProgress(done, total); return pump();
        });
      }
      return pump().then(function () { return new Blob(parts).arrayBuffer(); });
    }).then(function (buf) {
      return crypto.subtle.digest('SHA-256', buf).then(function (h) {
        var hex = Array.from(new Uint8Array(h)).map(function (b) { return b.toString(16).padStart(2, '0'); }).join('');
        if (info.sha256 && hex !== info.sha256) throw new Error('checksum');
        return KPCore.openKpdb(buf, DBK);      // proves the key and the file before storing
      }).then(function (bytes) {
        return idbPut('db', buf).then(function () { return idbPut('dbmeta', {version: info.version, size: info.size}); })
          .then(function () { return bytes; });
      });
    });
  }

  function screenSetup(done) {
    app.innerHTML = titlebar(T('app_name')) +
      '<main class="page narrow center"><img class="logo big" src="icons/icon-192.png" alt="">' +
      '<h2>' + esc(T('setup_title')) + '</h2><p id="msg" class="lead">' + esc(T('loading')) + '</p>' +
      '<div class="bar" id="bar" hidden><div></div></div><p id="prog" class="sub"></p>' +
      '<button class="btn" id="start" disabled>' + esc(T('setup_start')) + '</button></main>';
    var info = null;
    function fail(m) { $('#bar').hidden = true; $('#prog').textContent = m; var b = $('#start'); b.disabled = false; b.textContent = T('retry'); }
    function load() {
      $('#start').disabled = true;
      fetchDbInfo().then(function (i) {
        info = i; $('#msg').textContent = T('web_setup_msg', mb(i.size));
        var b = $('#start'); b.textContent = T('setup_start'); b.disabled = false;
      }, function () { fail(T('setup_failed')); });
    }
    $('#start').onclick = function () {
      if (!info) return load();
      $('#start').disabled = true; $('#bar').hidden = false;
      if (navigator.storage && navigator.storage.persist) navigator.storage.persist();
      downloadDb(info, function (d, t) {
        $('#bar div').style.width = (t ? Math.round(d * 100 / t) : 50) + '%';
        $('#prog').textContent = T('setup_progress', mb(d), mb(t));
      }).then(function (bytes) { $('#prog').textContent = T('setup_preparing'); done(bytes); }, function (e) {
        fail(e.message === 'checksum' || e.message === 'bad_file' ? T('checksum_error') : T('setup_failed'));
      });
    };
    load();
  }

  // ───────────────────────── open the dictionary ─────────────────────────
  var SQLp = null;
  function sql() { return SQLp || (SQLp = initSqlJs({locateFile: function (f) { return 'lib/' + f; }})); }

  function openStore(bytes) {
    return sql().then(function (SQL) {
      store = new KPCore.Store(SQL, bytes);
      store.collections();
      buildDialectNames();
    });
  }

  function loadStored() {
    return idbGet('db').then(function (buf) { if (!buf) return null; return KPCore.openKpdb(buf, DBK); });
  }

  /** Background check: new dictionary on the website → download and switch silently. */
  function backgroundDbUpdate(manual) {
    return Promise.all([fetchDbInfo(), idbGet('dbmeta')]).then(function (r) {
      var info = r[0], cur = r[1] || {};
      if (String(info.version) <= String(cur.version || '')) { if (manual) alertBox(T('update_none')); return; }
      if (manual) toast(T('update_downloading'));
      return downloadDb(info, function () {}).then(function (bytes) {
        return openStore(bytes).then(function () { toast(T('update_db_done')); render(); });
      });
    }).catch(function () { if (manual) alertBox(T('network_error')); });
  }

  // ───────────────────────── drawer ─────────────────────────
  function openDrawer() {
    var d = document.createElement('div');
    d.className = 'drawer-ov';
    d.innerHTML = '<nav class="drawer"><div class="dhead"><img src="icons/icon-192.png" alt=""><b>' + esc(T('brand_title')) + '</b><small>' +
      esc(T('version_short', CFG.version)) + '</small></div>' +
      [['fav', 'favorites'], ['random', 'random_proverb'], ['stats', 'statistics'], ['-'],
       ['sort0', 'sort_alpha', P.sort === 0], ['sort1', 'sort_size', P.sort === 1], ['-'],
       ['settings', 'settings'], ['updates', 'check_updates'], ['about', 'about'], ['-'],
       ['website', 'website'], ['shareapp', 'share_app'], ['contact', 'contact']].concat(
        deferredPrompt ? [['install', 'install_btn']] : []).map(function (it) {
        return it[0] === '-' ? '<hr>' : '<button data-d="' + it[0] + '"' + (it[2] ? ' class="on"' : '') + '>' + esc(T(it[1])) + '</button>';
      }).join('') + '</nav>';
    document.body.appendChild(d);
    requestAnimationFrame(function () { d.classList.add('open'); });
    d.addEventListener('click', function (e) {
      var b = e.target.closest('[data-d]');
      if (e.target === d) return closeDrawer();
      if (!b) return;
      var a = b.dataset.d; closeDrawer();
      if (a === 'fav') go('#/fav');
      else if (a === 'stats') go('#/stats');
      else if (a === 'random') { var c = store.collections(); go('#/c/' + c[Math.floor(Math.random() * c.length)].id); }
      else if (a === 'sort0' || a === 'sort1') { setPref('sort', a === 'sort1' ? 1 : 0); mainScroll = 0; render(); }
      else if (a === 'settings') go('#/settings');
      else if (a === 'updates') { checkAppUpdate(); backgroundDbUpdate(true); }
      else if (a === 'about') go('#/about');
      else if (a === 'website') window.open(CFG.website, '_blank');
      else if (a === 'shareapp') shareText(T('share_app_text', location.href.split('#')[0]));
      else if (a === 'contact') location.href = 'mailto:' + CFG.email + '?subject=' + encodeURIComponent(T('app_name') + ' (web ' + CFG.version + ')');
      else if (a === 'install' && deferredPrompt) { deferredPrompt.prompt(); deferredPrompt = null; }
    });
  }
  function closeDrawer() { var d = $('.drawer-ov'); if (d) { d.classList.remove('open'); setTimeout(function () { d.remove(); }, 200); } }

  // ───────────────────────── main screen ─────────────────────────
  function daily() {
    var all = store.collections(), d = new Date(), seed = d.getFullYear() * 1000 + Math.floor((d - new Date(d.getFullYear(), 0, 0)) / 864e5);
    for (var i = 0; i < 25; i++) {
      seed = (seed * 1103515245 + 12345) % 2147483648;
      var c = all[seed % all.length];
      if (c.text.length >= 12 && c.text.length <= 160) return c;
    }
    return all[seed % all.length];
  }

  function counts(c) {
    var s = T('count_proverbs', num(c.proverbs));
    if (c.synonyms) s += '  ·  ' + T('count_synonyms', num(c.synonyms));
    if (c.references) s += '  ·  ' + T('count_references', num(c.references));
    return s;
  }
  function rowHtml(c) {
    var n = P.showDialect && dname(c.dialect);
    return '<div class="row" data-cid="' + c.id + '" data-l="' + esc(c.letter) + '"><div class="pt"' +
      (P.colorDialect ? ' style="color:' + dColor(c.dialect) + '"' : '') + ' dir="rtl">' + esc(c.text) + '</div>' +
      '<div class="meta">' + (n ? '<b style="color:' + dColor(c.dialect) + '">' + esc(n) + '</b>' : '') + '<span>' + esc(counts(c)) + '</span></div></div>';
  }

  function renderMain() {
    var chips = '<button class="chip' + (P.dialect === 0 ? ' on' : '') + '" data-dl="0">' + esc(T('all_dialects')) + '</button>' +
      store.dialects().map(function (d) {
        return '<button class="chip' + (P.dialect === d.id ? ' on' : '') + '" data-dl="' + d.id + '" style="--c:' + dColor(d.id) + '">' + esc(dname(d.id)) + '</button>';
      }).join('');
    app.innerHTML = titlebar(T('app_name'), {menu: true, actions: [['fav', 'star', 'favorites'], ['stats', 'stats', 'statistics']]}) +
      '<div class="searchbox">' + ICON.search + '<input id="q" type="search" enterkeyhint="search" placeholder="' + esc(T('search_hint')) + '" value="' + esc(searchQuery) + '">' +
      '<button id="qc" aria-label="' + esc(T('clear')) + '"' + (searchQuery ? '' : ' hidden') + '>' + ICON.clear + '</button></div>' +
      '<div class="chips scroll" id="chips">' + chips + '</div>' +
      '<div class="listwrap"><div id="list" class="list"></div><div id="index" class="index" hidden></div><div id="bubble" class="bubble" hidden></div>' +
      '<div id="empty" class="empty" hidden></div></div>';

    var q = $('#q'), timer;
    q.addEventListener('input', function () {
      searchQuery = q.value; $('#qc').hidden = !q.value;
      clearTimeout(timer); timer = setTimeout(runSearch, 250);
    });
    q.addEventListener('keydown', function (e) { if (e.key === 'Enter') { q.blur(); runSearch(); } });
    $('#qc').onclick = function () { q.value = ''; searchQuery = ''; $('#qc').hidden = true; showCollections(); };
    $('#chips').addEventListener('click', function (e) {
      var b = e.target.closest('[data-dl]'); if (!b) return;
      setPref('dialect', +b.dataset.dl);
      $('#chips').querySelectorAll('.chip').forEach(function (x) { x.classList.toggle('on', x === b); });
      mainScroll = 0;
      if (searchQuery.trim()) runSearch(); else showCollections();
    });
    var list = $('#list');
    list.addEventListener('click', function (e) {
      var r = e.target.closest('[data-cid]'), h = e.target.closest('[data-pid]'), s = e.target.closest('[data-share]');
      if (s) { e.stopPropagation(); return shareProverb(store.collection(+s.dataset.share).text); }
      if (h) go('#/c/' + (h.dataset.hcid || 0) + '/p' + h.dataset.pid);
      else if (r) go('#/c/' + r.dataset.cid);
    });
    enableLongPress(list, function (t) {
      var h = t.closest('[data-pid]'), r = t.closest('[data-cid]');
      if (h) proverbActions(+h.dataset.pid, h.dataset.text || h.textContent, +h.dataset.hcid);
      else if (r) { var c = store.collection(+r.dataset.cid); proverbActions(c.repId, c.text, c.id); }
    });
    list.addEventListener('scroll', function () { if (document.activeElement === q) q.blur(); }, {passive: true});
    if (searchQuery.trim()) runSearch(); else showCollections();
  }

  // Virtualised list: items are grouped in blocks; only blocks near the screen hold real rows.
  var observer = null;
  function virtualList(list, items, htmlOf) {
    if (observer) observer.disconnect();
    list.innerHTML = '';
    var BLOCK = 60, blocks = [], est = function (it) { return it.t === 'h' ? 44 : it.t === 'd' ? 170 : 74 * P.scale; };
    for (var i = 0; i < items.length; i += BLOCK) {
      var el = document.createElement('div'), part = items.slice(i, i + BLOCK);
      el.className = 'blk'; el.style.height = part.reduce(function (s, it) { return s + est(it); }, 0) + 'px';
      el._items = part; el._start = i; blocks.push(el); list.appendChild(el);
    }
    function fill(el) {
      if (el._filled) return;
      var before = el.getBoundingClientRect().top < list.getBoundingClientRect().top, oldH = el.offsetHeight;
      el.innerHTML = el._items.map(htmlOf).join(''); el.style.height = ''; el._filled = true;
      if (before) list.scrollTop += el.offsetHeight - oldH;      // keep the visible rows still
    }
    function empty(el) {
      if (!el._filled) return;
      el.style.height = el.offsetHeight + 'px'; el.innerHTML = ''; el._filled = false;
    }
    observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) { if (en.isIntersecting) fill(en.target); else empty(en.target); });
    }, {root: list, rootMargin: '1500px 0px'});
    blocks.forEach(function (b) { observer.observe(b); });
    return {
      scrollToItem: function (idx) {
        var b = blocks[Math.floor(idx / BLOCK)]; if (!b) return;
        fill(b);
        var child = b.children[idx - b._start];
        if (child) list.scrollTop = list.scrollTop + child.getBoundingClientRect().top - list.getBoundingClientRect().top;
      }
    };
  }

  function showCollections() {
    var list = $('#list'), idxBar = $('#index'), empty = $('#empty');
    var all = store.collections().filter(function (c) { return P.dialect === 0 || (c.mask & (1 << P.dialect)) || c.dialect === P.dialect; });
    var items = [], letters = [], letterPos = {};
    if (P.daily && P.dialect === 0 && P.sort === 0) items.push({t: 'd', c: daily()});
    if (P.sort === 1) {
      all.slice().sort(function (a, b) { return b.proverbs - a.proverbs; }).forEach(function (c) { items.push({t: 'r', c: c}); });
    } else {
      for (var i = 0; i < all.length;) {
        var l = all[i].letter, j = i;
        while (j < all.length && all[j].letter === l) j++;
        if (!(l in letterPos)) { letterPos[l] = items.length; letters.push(l); }
        items.push({t: 'h', l: l, n: j - i});
        for (var k = i; k < j; k++) items.push({t: 'r', c: all[k]});
        i = j;
      }
    }
    empty.hidden = all.length > 0; empty.textContent = T('no_collections');
    var vl = virtualList(list, items, function (it) {
      if (it.t === 'h') return '<div class="lhead" data-l="' + esc(it.l) + '"><b>' + esc(it.l) + '</b><span>' + num(it.n) + '</span></div>';
      if (it.t === 'd') return '<div class="daily" data-cid="' + it.c.id + '"><div class="dt">' + ICON.today + '<b>' + esc(T('proverb_of_day')) +
        '</b><button data-share="' + it.c.id + '" aria-label="' + esc(T('share')) + '">' + ICON.share + '</button></div><div class="pt big" dir="rtl">' +
        esc(it.c.text) + '</div><div class="meta"><span>' + esc(counts(it.c)) + '</span></div></div>';
      return rowHtml(it.c);
    });
    list.scrollTop = mainScroll;
    // index bar
    if (letters.length > 1) {
      idxBar.hidden = false;
      idxBar.innerHTML = letters.map(function (l) { return '<span>' + esc(l) + '</span>'; }).join('');
      var bubble = $('#bubble'), active = -1, hideT;
      function pick(y) {
        var r = idxBar.getBoundingClientRect(), i = Math.max(0, Math.min(letters.length - 1, Math.floor((y - r.top) / r.height * letters.length)));
        if (i !== active) { setActive(i); vl.scrollToItem(letterPos[letters[i]]); if (navigator.vibrate) navigator.vibrate(5); }
        bubble.textContent = letters[i]; bubble.hidden = false; clearTimeout(hideT);
      }
      function setActive(i) {
        if (active >= 0 && idxBar.children[active]) idxBar.children[active].classList.remove('on');
        active = i; if (idxBar.children[i]) idxBar.children[i].classList.add('on');
      }
      function end() { hideT = setTimeout(function () { bubble.hidden = true; }, 350); }
      idxBar.addEventListener('pointerdown', function (e) { idxBar.setPointerCapture(e.pointerId); pick(e.clientY); e.preventDefault(); });
      idxBar.addEventListener('pointermove', function (e) { if (e.buttons || e.pointerType === 'touch') pick(e.clientY); });
      idxBar.addEventListener('pointerup', end); idxBar.addEventListener('pointercancel', end);
      var raf = 0;
      list.onscroll = function () {
        if (raf) return;
        raf = requestAnimationFrame(function () {
          raf = 0;
          var lr = list.getBoundingClientRect(), el = document.elementFromPoint(lr.left + lr.width / 2, lr.top + 4);
          var hit = el && el.closest('[data-l]');
          if (hit) { var i = letters.indexOf(hit.dataset.l); if (i >= 0 && i !== active) setActive(i); }
        });
      };
      setActive(0);
    } else { idxBar.hidden = true; list.onscroll = null; }
  }

  function runSearch() {
    var raw = searchQuery.trim(), list = $('#list'), empty = $('#empty');
    if (!raw) return showCollections();
    $('#index').hidden = true; list.onscroll = null;
    list.innerHTML = '<div class="spinner"></div>';
    setTimeout(function () {             // let the spinner appear before a slow "similar" search
      if (raw !== searchQuery.trim()) return;
      var r = store.search(raw, P.dialect);
      if (raw !== searchQuery.trim()) return;
      if (observer) observer.disconnect();
      if (!r.hits.length) { list.innerHTML = ''; empty.hidden = false; empty.textContent = T('no_results', raw); return; }
      empty.hidden = true;
      var head = r.mode === 'similar' ? T('results_similar', num(r.hits.length)) : T('results_count', num(r.hits.length));
      list.innerHTML = '<div class="rhead">' + esc(head) + '</div>' + r.hits.map(function (h) { return hitHtml(h, raw); }).join('');
      list.scrollTop = 0;
    }, 20);
  }

  function hitHtml(h, raw) {
    var n = P.showDialect && dname(h.dialect), where = h.collectionId ? store.title(h.collectionId) : '';
    return '<div class="row" data-pid="' + h.proverbId + '" data-hcid="' + h.collectionId + '" data-text="' + esc(h.text) + '"><div class="pt" dir="rtl"' +
      (P.colorDialect ? ' style="color:' + dColor(h.dialect) + '"' : '') + '>' + highlight(h.text, raw) + '</div><div class="meta">' +
      (n ? '<b style="color:' + dColor(h.dialect) + '">' + esc(n) + '</b>' : '') +
      (where && where !== h.text ? '<span dir="rtl" class="where">↲ ' + esc(where) + '</span>' : '') + '</div></div>';
  }

  // ───────────────────────── collection ─────────────────────────
  function renderCollection() {
    var c = store.collection(route.id);
    if (!c) return go('#/');
    var ents = store.entries(c.id), dset = [];
    ents.forEach(function (e) { var n = dname(e.dialect); if (n && dset.indexOf(n) < 0) dset.push(n); });
    app.innerHTML = titlebar(c.text, {back: true, actions: [['cshare', 'share', 'share'], ['home', 'home', 'home']]}) +
      '<div class="summary">' + esc(T('collection_summary', num(ents.length))) + (dset.length ? '  ·  ' + esc(dset.join(rtl() ? '، ' : ', ')) : '') + '</div>' +
      '<div id="list" class="list entries">' + ents.map(entryHtml).join('') + '</div>';
    var list = $('#list');
    list.addEventListener('click', function (e) {
      var r = e.target.closest('[data-eid]'); if (!r) return;
      var en = ents[+r.dataset.i];
      if (en.ref) followRef(en); else go('#/p/' + en.proverbId);
    });
    enableLongPress(list, function (t) {
      var r = t.closest('[data-eid]'); if (!r) return;
      var en = ents[+r.dataset.i]; proverbActions(en.proverbId, en.text, c.id);
    });
    $('[data-act="cshare"]').onclick = function (e) {
      e.stopPropagation();
      shareProverb(ents.map(function (en) { var n = dname(en.dialect); return (en.rep ? '★ ' : '• ') + en.text + (n ? '  (' + n + ')' : ''); }).join('\n'));
    };
    // target: e<entryId> or p<proverbId>
    var tg = route.target, idx = -1;
    if (tg) {
      var id = +tg.slice(1);
      ents.forEach(function (e, i) { if (idx < 0 && ((tg[0] === 'e' && e.entryId === id) || (tg[0] === 'p' && e.proverbId === id))) idx = i; });
    }
    if (idx >= 0) flash(list, idx); else list.scrollTop = 0;
  }

  function entryHtml(e, i) {
    var cls = 'entry' + (e.rep ? ' rep' : '') + (e.header === 1 ? ' h1' : e.header === 2 ? ' h2' : '') + (e.ref ? ' ref' : '');
    var meta = [], n = P.showDialect && dname(e.dialect);
    if (n) meta.push('<b style="color:' + dColor(e.dialect) + '">' + esc(n) + '</b>');
    if (e.syn) meta.push(esc(T('role_synonym') + (e.synType ? ' (' + e.synType + ')' : '')));
    if (e.ref) meta.push(esc(T('tap_to_follow')));
    if (e.trans) meta.push(esc(T('translation_lang', e.transLang || '?')));
    var style = P.colorDialect && !e.ref && e.header !== 1 ? ' style="color:' + dColor(e.dialect) + '"' : '';
    return '<div class="' + cls + '" data-eid="' + e.entryId + '" data-i="' + i + '"><div class="pt" dir="rtl"' + style + '>' +
      (e.ref ? '↖ ' : '') + esc(e.text) + '</div>' + (meta.length ? '<div class="meta">' + meta.join('<span class="dot">·</span>') + '</div>' : '') + '</div>';
  }

  function flash(list, idx) {
    var el = list.children[idx]; if (!el) return;
    list.scrollTop = el.offsetTop - list.offsetTop - 80;
    el.classList.add('flash'); setTimeout(function () { el.classList.remove('flash'); }, 1700);
  }

  function followRef(en) {
    var ts = store.referenceTargets(en.entryId);
    if (!ts.length) return toast(T('reference_missing'));
    function nav(t) {
      if (t.collectionId === route.id) {
        var list = $('#list'), i = -1;
        Array.prototype.forEach.call(list.children, function (c, k) { if (+c.dataset.eid === t.entryId) i = k; });
        if (i >= 0) flash(list, i);
      } else go('#/c/' + t.collectionId + '/e' + t.entryId);
    }
    if (ts.length === 1) return nav(ts[0]);
    sheet(T('choose_target'), ts.map(function (t, i) { return [(i + 1) + '. ' + (store.title(t.collectionId) || t.text), function () { nav(t); }]; }));
  }

  // ───────────────────────── proverb details ─────────────────────────
  function renderProverb() {
    var p = store.proverb(route.id); if (!p) return go('#/');
    var apps = store.appearances(p.id), cols = {}, main = 0, syn = 0, ref = 0;
    apps.forEach(function (a) { cols[a.collectionId] = 1; if (a.role === 'main') main++; else if (a.role === 'synonym') syn++; else if (a.role === 'reference' || a.role === 'referenced') ref++; });
    var roleIcon = {main: '★ ', synonym: '≈ ', reference: '→ ', referenced: '← ', header: '▶ ', subheader: '▷ ', entry: '· '};
    var roleKey = {main: 'role_main', synonym: 'role_synonym', reference: 'role_reference', referenced: 'role_referenced_from', header: 'role_header', subheader: 'role_subheader', entry: 'role_entry'};
    var fav = isFav(p.id);
    app.innerHTML = titlebar(T('details'), {back: true, actions: [['home', 'home', 'home']]}) +
      '<main class="page"><div class="card"><div class="pt huge" dir="rtl">' + esc(p.text) + '</div>' +
      (dname(p.dialect) ? '<p class="dlabel" style="color:' + dColor(p.dialect) + '">' + esc(T('dialect_label', dname(p.dialect))) + '</p>' : '') +
      (p.trans ? '<p class="sub">' + esc(T('translation_lang', p.transLang || '?')) + '</p>' : '') +
      '<div class="actions3"><button id="pc">' + ICON.copy + esc(T('copy')) + '</button><button id="ps">' + ICON.share + esc(T('share')) +
      '</button><button id="pf">' + (fav ? ICON.star : ICON.starO) + esc(T('favorite_short')) + '</button></div></div>' +
      '<h3>' + esc(T('appears_in', num(Object.keys(cols).length))) + '</h3><p class="sub">' + esc(T('appears_stats', num(main), num(syn), num(ref))) + '</p>' +
      '<div class="apps">' + apps.map(function (a) {
        return '<div class="app-row" data-c="' + a.collectionId + '" data-e="' + a.entryId + '"><small>' + roleIcon[a.role] + esc(T(roleKey[a.role])) +
          (a.detail ? ' (' + esc(a.detail) + ')' : '') + '</small><div class="pt" dir="rtl">' + esc(store.title(a.collectionId)) + '</div></div>';
      }).join('') + '</div></main>';
    $('#pc').onclick = function () { copyText(p.text); };
    $('#ps').onclick = function () { shareProverb(p.text); };
    $('#pf').onclick = function () { toast(T(toggleFav(p.id) ? 'favorite_added' : 'favorite_removed')); renderProverb(); };
    $('.apps').addEventListener('click', function (e) { var r = e.target.closest('[data-c]'); if (r) go('#/c/' + r.dataset.c + '/e' + r.dataset.e); });
  }

  // ───────────────────────── favourites, stats, settings, about ─────────────────────────
  function renderFavorites() {
    var hits = store.proverbsByIds(favs());
    app.innerHTML = titlebar(T('favorites'), {back: true, actions: [['home', 'home', 'home']]}) +
      '<div id="list" class="list">' + (hits.length ? '<div class="rhead">' + esc(T('favorites_count', num(hits.length))) + '</div>' + hits.map(function (h) { return hitHtml(h, ''); }).join('')
        : '<div class="empty">' + esc(T('favorites_empty')) + '</div>') + '</div>';
    var list = $('#list');
    list.addEventListener('click', function (e) { var h = e.target.closest('[data-pid]'); if (h) go('#/c/' + h.dataset.hcid + '/p' + h.dataset.pid); });
    enableLongPress(list, function (t) { var h = t.closest('[data-pid]'); if (h) proverbActions(+h.dataset.pid, h.dataset.text, +h.dataset.hcid); });
  }

  function renderStats() {
    var s = store.stats(), max = 1, total = 0;
    s.dialects.forEach(function (d) { max = Math.max(max, d.proverbs); total += d.proverbs; });
    function card(v, k) { return '<div class="stat"><b>' + num(v) + '</b><span>' + esc(T(k)) + '</span></div>'; }
    app.innerHTML = titlebar(T('statistics'), {back: true, actions: [['home', 'home', 'home']]}) +
      '<main class="page"><div class="grid2">' + card(s.collections, 'stats_collections') + card(s.proverbs, 'stats_proverbs') +
      card(s.entries, 'stats_entries') + card(s.synonyms, 'stats_synonyms') + card(s.references, 'stats_references') + card(s.translations, 'stats_translations') +
      '</div><h3>' + esc(T('stats_by_dialect')) + '</h3>' + s.dialects.map(function (d) {
        return '<div class="dstat"><div><b style="color:' + dColor(d.id) + '">' + esc(dname(d.id) || '#' + d.id) + '</b><span>' +
          esc(T('stats_dialect_count', num(d.proverbs), num(total ? Math.round(d.proverbs * 100 / total) : 0))) + '</span></div>' +
          '<div class="track"><i style="width:' + Math.max(1, d.proverbs * 100 / max) + '%;background:' + dColor(d.id) + '"></i></div></div>';
      }).join('') + '<p class="sub center">' + esc(T('stats_db_version', s.version || '—')) + '</p></main>';
  }

  function renderSettings() {
    var themes = [T('theme_system'), T('theme_light'), T('theme_dark')], scales = [0.85, 0.92, 1, 1.1, 1.2, 1.3, 1.4, 1.55, 1.7];
    var si = scales.indexOf(P.scale); if (si < 0) si = 2;
    var reg = ls('reg', null);
    function sw(id, on, key) { return '<label class="sw"><span>' + esc(T(key)) + '</span><input type="checkbox" id="' + id + '"' + (on ? ' checked' : '') + '><i></i></label>'; }
    app.innerHTML = titlebar(T('settings'), {back: true}) +
      '<main class="page settings"><h4>' + esc(T('settings_appearance')) + '</h4>' +
      '<label class="set"><span>' + esc(T('settings_language')) + '</span><select id="sLang">' +
      LANGS.map(function (l) { return '<option value="' + l + '"' + (l === P.lang ? ' selected' : '') + '>' + esc(LANG_NAMES[l]) + '</option>'; }).join('') + '</select></label>' +
      '<label class="set"><span>' + esc(T('settings_theme')) + '</span><select id="sTheme">' +
      themes.map(function (t, i) { return '<option value="' + i + '"' + (i === P.theme ? ' selected' : '') + '>' + esc(t) + '</option>'; }).join('') + '</select></label>' +
      '<div class="set col"><span>' + esc(T('settings_text_size')) + '</span><input type="range" id="sScale" min="0" max="8" value="' + si + '">' +
      '<div class="pt" dir="rtl" id="preview">' + esc(T('text_size_preview')) + '</div></div>' +
      sw('sDialect', P.showDialect, 'settings_show_dialect') + sw('sColor', P.colorDialect, 'settings_color_dialect') + sw('sDaily', P.daily, 'settings_daily') +
      '<h4>' + esc(T('settings_updates')) + '</h4><button class="set" id="sUpd"><span>' + esc(T('check_updates')) + '</span><small id="sVer"></small></button>' +
      '<h4>' + esc(T('settings_account')) + '</h4><div class="set col"><span>' + esc(reg ? reg.name + ' — ' + reg.email : T('account_unregistered')) + '</span></div>' +
      '<button class="set" id="sPriv"><span>' + esc(T('privacy')) + '</span><small>' + esc(T('privacy_summary')) + '</small></button></main>';
    idbGet('dbmeta').then(function (m) { $('#sVer') && ($('#sVer').textContent = T('versions_line', CFG.version, (m && m.version) || '—')); });
    $('#sLang').onchange = function () { setPref('lang', this.value); applyLook(); buildDialectNames(); renderSettings(); };
    $('#sTheme').onchange = function () { setPref('theme', +this.value); applyLook(); };
    $('#sScale').oninput = function () { setPref('scale', scales[+this.value]); applyLook(); };
    $('#sDialect').onchange = function () { setPref('showDialect', this.checked); };
    $('#sColor').onchange = function () { setPref('colorDialect', this.checked); };
    $('#sDaily').onchange = function () { setPref('daily', this.checked); };
    $('#sUpd').onclick = function () { checkAppUpdate(); backgroundDbUpdate(true); };
    $('#sPriv').onclick = function () { window.open(CFG.website + 'privacy', '_blank'); };
  }

  function renderAbout() {
    app.innerHTML = titlebar(T('about'), {back: true}) +
      '<main class="page"><img class="logo" src="icons/icon-192.png" alt=""><h2 class="center">' + esc(T('brand_title')) + '</h2>' +
      '<p class="center sub">' + esc(T('brand_subtitle')) + '</p><p class="center sub" id="aVer"></p>' +
      '<div class="center row2"><button class="linkbtn" id="aWeb">' + esc(T('website')) + '</button><button class="linkbtn" id="aPriv">' + esc(T('privacy')) +
      '</button><button class="linkbtn" id="aMail">' + esc(T('contact')) + '</button></div>' +
      (isIOS ? '' : '<p class="center"><a class="linkbtn" href="' + CFG.androidApk + '">' + esc(T('android_app')) + '</a></p>') +
      '<div id="intro" class="intro"></div><p class="center sub credits">' + esc(T('about_credits')).replace(/\n/g, '<br>') + '<br>sql.js (MIT)</p></main>';
    idbGet('dbmeta').then(function (m) { $('#aVer').textContent = T('about_version', CFG.version, 'web') + '  ·  ' + T('about_database', (m && m.version) || '—'); });
    $('#aWeb').onclick = function () { window.open(CFG.website, '_blank'); };
    $('#aPriv').onclick = function () { window.open(CFG.website + 'privacy', '_blank'); };
    $('#aMail').onclick = function () { location.href = 'mailto:' + CFG.email; };
    fetch('intro.json').then(function (r) { return r.json(); }).then(function (all) {
      var o = all[P.lang] || all.en, dir = (P.lang === 'ckb' || P.lang === 'fa') ? 'rtl' : 'ltr';
      $('#intro').innerHTML = '<div dir="' + dir + '"><h3>' + esc(o.title) + '</h3><p class="author">' + esc(o.author) + '</p>' +
        o.sections.map(function (s) { return '<h4>' + esc(s.heading) + '</h4>' + s.paragraphs.map(function (p) { return '<p>' + esc(p) + '</p>'; }).join(''); }).join('') + '</div>';
    }).catch(function () {});
  }

  // ───────────────────────── app updates (service worker) ─────────────────────────
  var swReg = null;
  function checkAppUpdate() { if (swReg) swReg.update().catch(function () {}); }
  function registerSW() {
    if (!('serviceWorker' in navigator)) return;
    var hadController = !!navigator.serviceWorker.controller;
    navigator.serviceWorker.register('sw.js').then(function (r) { swReg = r; }).catch(function () {});
    navigator.serviceWorker.addEventListener('controllerchange', function () {
      if (hadController) { lsSet('justUpdated', true); location.reload(); }
    });
  }
  window.addEventListener('beforeinstallprompt', function (e) { e.preventDefault(); deferredPrompt = e; });

  // ───────────────────────── start ─────────────────────────
  function checkin() {
    var reg = ls('reg', null), last = ls('lastCheckin', 0);
    if (!reg || Date.now() - last < 7 * 864e5) return;
    lsSet('lastCheckin', Date.now());
    idbGet('dbmeta').then(function (m) {
      server({action: 'checkin', email: reg.email, token: reg.token, db_version: (m && m.version) || ''}).catch(function () {});
    });
  }

  function opening() {
    app.innerHTML = '<div class="splash"><img src="icons/icon-192.png" alt=""><div class="spinner"></div><p>' + esc(T('opening')) + '</p></div>';
  }

  function start() {
    applyLook();
    if (!window.indexedDB || !window.crypto || !crypto.subtle || typeof DecompressionStream === 'undefined' || typeof WebAssembly === 'undefined') {
      app.innerHTML = '<div class="splash"><p>' + esc(T('browser_unsupported')) + '</p></div>'; return;
    }
    registerSW();
    // iPhone/iPad in the browser: storage there is separate from the Home Screen app,
    // so guide the user to install first instead of downloading twice.
    if (isIOS && !isStandalone && !ls('skipInstall', false)) return screenInstall(step2);
    step2();
    function step2() { if (!registered()) screenRegister(step3); else step3(); }
    function step3() {
      opening();
      loadStored().then(function (bytes) {
        if (!bytes) return screenSetup(function (b) { opening(); finish(b); });
        finish(bytes);
      }).catch(function () { screenSetup(function (b) { opening(); finish(b); }); });
    }
    function finish(bytes) {
      openStore(bytes).then(function () {
        render();
        if (ls('justUpdated', false)) { lsSet('justUpdated', false); toast(T('app_updated')); }
        setTimeout(function () { backgroundDbUpdate(false); checkin(); }, 3000);
      }).catch(function () {
        screenSetup(function (b) { opening(); finish(b); });
      });
    }
  }

  if (window.matchMedia) matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () { if (store && P.theme === 0) render(); });
  start();
})();
