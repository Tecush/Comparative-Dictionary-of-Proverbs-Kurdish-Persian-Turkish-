/*
 * core.js — Kurdish Proverbs web app: text normalization, database decryption, queries.
 * Same rules as the Android app (TextNormalizer.java, DbCrypto.java, Db.java) and the
 * release tool (kp_android_release.py), so all three read the same database file.
 * Works in the browser (window.KPCore) and in Node for testing (module.exports).
 */
(function (root) {
  'use strict';

  // ───────────────────────── normalization (== TextNormalizer.java) ─────────────────────────
  var MAP = {0x064A: 0x06CC, 0x0649: 0x06CC, 0x0643: 0x06A9, 0x0131: 0x69};
  var RE_MN = /\p{Mn}/u, RE_CF = /\p{Cf}/u, RE_KEEP = /[\p{L}\p{Nd}]/u;

  function normalize(s) {
    if (!s) return '';
    s = s.split('\u0647\u200C').join('\u06D5').normalize('NFKD');
    var out = '', lastSpace = true;
    for (var ch of s) {
      if (RE_MN.test(ch)) continue;
      if (RE_CF.test(ch) || ch === '\u0640') continue;
      var low = ch.toLowerCase();
      var cp = (Array.from(low).length === 1 ? low : ch).codePointAt(0);
      if (MAP[cp] !== undefined) cp = MAP[cp];
      if (cp >= 0x0660 && cp <= 0x0669) cp = 48 + cp - 0x0660;
      else if (cp >= 0x06F0 && cp <= 0x06F9) cp = 48 + cp - 0x06F0;
      var c = String.fromCodePoint(cp);
      if (RE_KEEP.test(c)) { out += c; lastSpace = false; }
      else if (!lastSpace) { out += ' '; lastSpace = true; }
    }
    return out.endsWith(' ') ? out.slice(0, -1) : out;
  }

  // ───────────────────────── SHA-256 keystream (== DbCrypto.java) ─────────────────────────
  var K = new Uint32Array([
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]);
  var H0 = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];

  function expand(W) {           // W: Uint32Array(64) with W[0..15] filled
    for (var i = 16; i < 64; i++) {
      var a = W[i - 15], b = W[i - 2];
      var s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
      var s1 = ((b >>> 17) | (b << 15)) ^ ((b >>> 19) | (b << 13)) ^ (b >>> 10);
      W[i] = (W[i - 16] + s0 + W[i - 7] + s1) | 0;
    }
  }

  function compress(st, W) {     // st: Int32Array(8), W: expanded
    var a = st[0], b = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
    for (var i = 0; i < 64; i++) {
      var S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      var t1 = (h + S1 + ((e & f) ^ (~e & g)) + K[i] + W[i]) | 0;
      var S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      var t2 = (S0 + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    st[0] = (st[0] + a) | 0; st[1] = (st[1] + b) | 0; st[2] = (st[2] + c) | 0; st[3] = (st[3] + d) | 0;
    st[4] = (st[4] + e) | 0; st[5] = (st[5] + f) | 0; st[6] = (st[6] + g) | 0; st[7] = (st[7] + h) | 0;
  }

  function be32(b, o) { return (b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]; }

  /** XORs data in place with SHA-256(encKey + iv + blockNo) — 56-byte message, two compressions. */
  function xorKeystream(data, encKey, iv) {
    var W1 = new Uint32Array(64), W2 = new Uint32Array(64), st = new Int32Array(8);
    var fixed = new Uint8Array(48); fixed.set(encKey, 0); fixed.set(iv, 32);
    var fixedWords = []; for (var i = 0; i < 12; i++) fixedWords.push(be32(fixed, i * 4));
    for (i = 0; i < 16; i++) W2[i] = 0;
    W2[15] = 56 * 8; expand(W2);                // second block: only the message length
    var n = data.length, block = 0;
    for (var off = 0; off < n; off += 32, block++) {
      for (i = 0; i < 12; i++) W1[i] = fixedWords[i];
      W1[12] = Math.floor(block / 4294967296) | 0; W1[13] = block >>> 0;
      W1[14] = 0x80000000 | 0; W1[15] = 0;
      expand(W1);
      for (i = 0; i < 8; i++) st[i] = H0[i];
      compress(st, W1); compress(st, W2);
      var lim = Math.min(32, n - off);
      for (i = 0; i < lim; i++) data[off + i] ^= (st[i >> 2] >>> (24 - 8 * (i & 3))) & 0xff;
    }
    return data;
  }

  var te = new TextEncoder();
  function subtle() { return (root.crypto || require('crypto').webcrypto).subtle; }

  /** Verifies and decrypts a .kpdb file, returns the SQLite database bytes. */
  async function openKpdb(fileBytes, secret) {
    var u8 = new Uint8Array(fileBytes);
    var magic = String.fromCharCode.apply(null, u8.subarray(0, 5));
    if (magic !== 'KPDB2') throw new Error('bad_file');
    var iv = u8.slice(5, 21), data = u8.slice(21, u8.length - 32), tag = u8.subarray(u8.length - 32);
    var sc = subtle();
    var encKey = new Uint8Array(await sc.digest('SHA-256', te.encode('enc|' + secret)));
    var macKey = new Uint8Array(await sc.digest('SHA-256', te.encode('mac|' + secret)));
    var hk = await sc.importKey('raw', macKey, {name: 'HMAC', hash: 'SHA-256'}, false, ['verify']);
    var signed = new Uint8Array(16 + data.length); signed.set(iv, 0); signed.set(data, 16);
    if (!(await sc.verify('HMAC', hk, tag, signed))) throw new Error('bad_file');
    xorKeystream(data, encKey, iv);
    var ds = new DecompressionStream('gzip');
    var stream = new Blob([data]).stream().pipeThrough(ds);
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }

  // ───────────────────────── database access (== Db.java) ─────────────────────────
  function Store(SQL, bytes) {
    this.db = new SQL.Database(bytes);
    this._collections = null; this._byId = null; this._dialects = null;
  }
  Store.prototype.rows = function (sql, params) {
    var st = this.db.prepare(sql), out = [];
    try { if (params) st.bind(params); while (st.step()) out.push(st.get()); } finally { st.free(); }
    return out;
  };
  Store.prototype.meta = function () {
    var m = {}; this.rows('SELECT key, value FROM meta').forEach(function (r) { m[r[0]] = r[1]; }); return m;
  };
  Store.prototype.dialects = function () {
    if (!this._dialects) this._dialects = this.rows('SELECT id, name_ku, name_en FROM dialects ORDER BY id')
      .map(function (r) { return {id: r[0], nameKu: r[1], nameEn: r[2]}; });
    return this._dialects;
  };
  Store.prototype.collections = function () {
    if (this._collections) return this._collections;
    var byId = {};
    this._collections = this.rows(
      'SELECT c.id, c.representative_proverb_id, c.start_char, c.total_entries, c.total_proverbs, ' +
      'c.total_synonyms, c.total_references, c.dialect_mask, p.text, p.dialect_id ' +
      'FROM collections c LEFT JOIN proverbs p ON p.id = c.representative_proverb_id ORDER BY c.id')
      .map(function (r) {
        var c = {id: r[0], repId: r[1], letter: r[2] || (r[8] ? Array.from(r[8])[0] : '?'), entries: r[3],
                 proverbs: r[4], synonyms: r[5], references: r[6], mask: r[7] || 0, text: r[8] || '', dialect: r[9]};
        byId[c.id] = c; return c;
      });
    this._byId = byId;
    return this._collections;
  };
  Store.prototype.collection = function (id) { this.collections(); return this._byId[id]; };
  Store.prototype.title = function (id) { var c = this.collection(id); return c ? c.text : '#' + id; };

  Store.prototype.entries = function (cid) {
    return this.rows(
      'SELECT e.entry_id, e.paragraph_order, e.is_representative, e.is_synonym, e.synonym_type, ' +
      'e.is_reference, e.reference_type, e.header_level, p.id, p.text, p.dialect_id, p.is_translation, ' +
      'p.translation_language FROM collection_entries e JOIN proverbs p ON p.id = e.proverb_id ' +
      'WHERE e.collection_id = ? ORDER BY e.paragraph_order', [cid])
      .map(function (r) {
        return {entryId: r[0], rep: r[2] === 1, syn: r[3] === 1, synType: r[4], ref: r[5] === 1, refType: r[6],
                header: r[7], proverbId: r[8], text: r[9] || '', dialect: r[10], trans: r[11] === 1, transLang: r[12]};
      });
  };

  Store.prototype.referenceTargets = function (entryId) {
    var r = this.rows('SELECT main_target_entry_id, sub_target_entry_ids FROM reference_targets WHERE source_entry_id=?', [entryId]);
    if (!r.length) return [];
    var ids = [r[0][0]];
    String(r[0][1] || '').replace(/[\[\]"\s]/g, '').split(',').forEach(function (p) {
      var n = parseInt(p, 10); if (!isNaN(n) && ids.indexOf(n) < 0) ids.push(n);
    });
    var self = this, out = [];
    ids.forEach(function (id) {
      var t = self.rows('SELECT e.collection_id, p.text FROM collection_entries e JOIN proverbs p ON p.id = e.proverb_id WHERE e.entry_id=?', [id]);
      if (t.length) out.push({entryId: id, collectionId: t[0][0], text: t[0][1]});
    });
    return out;
  };

  Store.prototype.proverb = function (id) {
    var r = this.rows('SELECT id, text, dialect_id, is_translation, translation_language FROM proverbs WHERE id=?', [id]);
    return r.length ? {id: r[0][0], text: r[0][1], dialect: r[0][2], trans: r[0][3] === 1, transLang: r[0][4]} : null;
  };

  var HIT_COLS = 'p.id, p.text, p.dialect_id, p.is_translation, ' +
    '(SELECT e.collection_id FROM collection_entries e WHERE e.proverb_id = p.id ' +
    ' ORDER BY e.is_representative DESC, e.paragraph_order LIMIT 1) ';
  function hit(r) { return {proverbId: r[0], text: r[1] || '', dialect: r[2], trans: r[3] === 1, collectionId: r[4] || 0}; }

  Store.prototype.proverbsByIds = function (ids) {
    if (!ids.length) return [];
    var found = {};
    this.rows('SELECT ' + HIT_COLS + 'FROM proverbs p WHERE p.id IN (' + ids.map(function () { return '?'; }).join(',') + ')', ids)
      .forEach(function (r) { found[r[0]] = hit(r); });
    return ids.map(function (id) { return found[id]; }).filter(Boolean);
  };

  Store.prototype.appearances = function (pid) {
    var seen = {}, out = [];
    this.rows('SELECT collection_id, entry_id, is_representative, is_synonym, synonym_type, is_reference, reference_type, ' +
      'header_level FROM collection_entries WHERE proverb_id=? ORDER BY is_representative DESC, collection_id', [pid])
      .forEach(function (r) {
        var role = r[2] === 1 ? 'main' : r[3] === 1 ? 'synonym' : r[5] === 1 ? 'reference' : r[7] === 1 ? 'header' : r[7] === 2 ? 'subheader' : 'entry';
        seen[r[1]] = 1;
        out.push({collectionId: r[0], entryId: r[1], role: role, detail: r[3] === 1 ? r[4] : r[5] === 1 ? r[6] : null});
      });
    this.rows('SELECT src.collection_id, src.entry_id, src.reference_type FROM reference_targets rt ' +
      'JOIN collection_entries tgt ON tgt.entry_id = rt.main_target_entry_id AND tgt.proverb_id = ? ' +
      'JOIN collection_entries src ON src.entry_id = rt.source_entry_id ORDER BY src.collection_id', [pid])
      .forEach(function (r) {
        if (seen[r[1]]) return; seen[r[1]] = 1;
        out.push({collectionId: r[0], entryId: r[1], role: 'referenced', detail: r[2]});
      });
    return out;
  };

  Store.prototype.stats = function () {
    var m = this.meta();
    return {
      collections: +m.total_collections || 0, entries: +m.total_entries || 0, proverbs: +m.total_proverbs || 0,
      synonyms: +m.total_synonyms || 0, references: +m.total_references || 0, translations: +m.total_translations || 0,
      version: m.db_version || '', dialects: this.rows('SELECT dialect_id, proverbs, collections FROM dialect_stats ORDER BY proverbs DESC')
        .map(function (r) { return {id: r[0], proverbs: r[1], collections: r[2]}; })
    };
  };

  var LIMIT = 300;
  /** Word search → inside-word search → similar spellings. Returns {hits, mode, query}. */
  Store.prototype.search = function (raw, dialectId) {
    var q = normalize(raw), res = {hits: [], mode: 'none', query: q};
    if (!q) return res;
    var ds = dialectId > 0 ? ' AND p.dialect_id = ' + (dialectId | 0) : '';
    var seen = {}, self = this;
    function add(rows) { rows.forEach(function (r) { if (res.hits.length < LIMIT && !seen[r[0]]) { seen[r[0]] = 1; res.hits.push(hit(r)); } }); }
    if (Array.from(q).length === 1) {
      add(this.rows('SELECT ' + HIT_COLS + 'FROM proverbs p WHERE p.search LIKE ?' + ds + ' LIMIT ' + LIMIT, [q + '%']));
    } else {
      var match = q.split(' ').filter(Boolean).map(function (t) { return t + '*'; }).join(' ');
      add(this.rows('SELECT ' + HIT_COLS + 'FROM proverbs_fts f JOIN proverbs p ON p.id = f.docid WHERE proverbs_fts MATCH ?' +
        ds + ' ORDER BY length(p.search) LIMIT ' + LIMIT, [match]));
      if (res.hits.length < LIMIT)
        add(this.rows('SELECT ' + HIT_COLS + 'FROM proverbs p WHERE p.search LIKE ?' + ds + ' LIMIT ' + (LIMIT * 2), ['%' + q + '%']));
    }
    if (res.hits.length) { res.mode = 'match'; return res; }
    if (Array.from(q).length < 3) return res;
    res.hits = this.similar(q, dialectId);
    if (res.hits.length) res.mode = 'similar';
    return res;
  };

  function grams(t) { var s = new Set(); for (var i = 0; i + 3 <= t.length; i++) s.add(t.substr(i, 3)); return s; }

  Store.prototype.similar = function (q, dialectId) {
    var qg = grams(' ' + q + ' '), need = Math.max(2, Math.ceil(qg.size * 0.5)), best = [];
    var st = this.db.prepare('SELECT id, search FROM proverbs p' + (dialectId > 0 ? ' WHERE p.dialect_id = ' + (dialectId | 0) : ''));
    try {
      while (st.step()) {
        var r = st.get(), t = ' ' + (r[1] || '') + ' ', m = 0, got = new Set();
        for (var i = 0; i + 3 <= t.length; i++) { var g = t.substr(i, 3); if (qg.has(g) && !got.has(g)) { got.add(g); m++; } }
        if (m < need) continue;
        best.push([r[0], m / qg.size - Math.min(0.2, t.length / 2000)]);
        if (best.length > 400) { best.sort(function (a, b) { return b[1] - a[1]; }); best.length = 100; }
      }
    } finally { st.free(); }
    best.sort(function (a, b) { return b[1] - a[1]; });
    return this.proverbsByIds(best.slice(0, 100).map(function (b) { return b[0]; }));
  };

  var api = {normalize: normalize, xorKeystream: xorKeystream, openKpdb: openKpdb, Store: Store};
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.KPCore = api;
})(typeof self !== 'undefined' ? self : this);
