package com.tecush.kurdishproverbs.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.tecush.kurdishproverbs.core.Cancel;
import com.tecush.kurdishproverbs.data.Models.Appearance;
import com.tecush.kurdishproverbs.data.Models.CollectionItem;
import com.tecush.kurdishproverbs.data.Models.Dialect;
import com.tecush.kurdishproverbs.data.Models.DialectStat;
import com.tecush.kurdishproverbs.data.Models.Entry;
import com.tecush.kurdishproverbs.data.Models.ProverbInfo;
import com.tecush.kurdishproverbs.data.Models.RefTarget;
import com.tecush.kurdishproverbs.data.Models.SearchHit;
import com.tecush.kurdishproverbs.data.Models.SearchResult;
import com.tecush.kurdishproverbs.data.Models.Stats;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only class that reads the proverb database.
 *
 * - The database is DOWNLOADED (see net/DbInstaller) into files/db/kp.db.
 *   It is no longer shipped inside the APK — that is what made the old app 300 MB.
 * - Opened read-only, once, and kept open for the life of the process.
 *   Screens never close it (the old rotation crash is gone).
 * - Call every method from AppExecutors.db() — never from the UI thread.
 * - Every query is wrapped so a problem returns an empty result instead of a crash.
 */
public final class Db {

    private static final String TAG = "KP-Db";
    public static final String DB_DIR = "db";
    public static final String DB_FILE = "kp.db";
    /** The app understands mobile databases with this schema_version (see meta table). */
    public static final int SCHEMA_VERSION = 1;

    private static final int SEARCH_LIMIT = 300;
    private static final int SIMILAR_LIMIT = 100;

    private static Db instance;

    public static synchronized Db get(Context ctx) {
        if (instance == null) instance = new Db(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private SQLiteDatabase db;
    private volatile int generation = 0;          // increases every time a new database is installed

    // In-memory caches (collections are needed constantly and are small enough)
    private List<CollectionItem> collectionsCache;
    private Map<Integer, CollectionItem> collectionById;
    private List<Dialect> dialectsCache;

    private Db(Context app) { this.app = app; }

    // ─────────────────────────────────────────────────────────────────────
    // File & lifecycle
    // ─────────────────────────────────────────────────────────────────────

    public static File dbDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), DB_DIR);
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    public static File dbFile(Context ctx) { return new File(dbDir(ctx), DB_FILE); }

    /** True when a usable database file is present. Cheap: does not open it. */
    public boolean isInstalled() {
        File f = dbFile(app);
        return f.exists() && f.length() > 1024;
    }

    public int generation() { return generation; }

    /** Opens the database if needed. @return false if it is missing or unreadable. */
    public synchronized boolean ensureOpen() {
        if (db != null && db.isOpen()) return true;
        File f = dbFile(app);
        if (!f.exists()) return false;
        try {
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            // Quick sanity check
            try (Cursor c = db.rawQuery("SELECT value FROM meta WHERE key='schema_version'", null)) {
                if (!c.moveToFirst()) throw new IllegalStateException("meta table empty");
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "open failed", e);
            closeQuietly();
            return false;
        }
    }

    /** Closes the database (used only while a new one is being installed). */
    public synchronized void close() {
        closeQuietly();
        collectionsCache = null;
        collectionById = null;
        dialectsCache = null;
    }

    /** Called by DbInstaller after a new file was put in place. */
    public synchronized void onNewDatabaseInstalled() {
        close();
        generation++;
    }

    private void closeQuietly() {
        try { if (db != null) db.close(); } catch (Exception ignored) {}
        db = null;
    }

    /**
     * Validates a freshly downloaded file before it replaces the current one.
     * @return the database version string, or null if the file is not usable.
     */
    public static String validate(File file) {
        SQLiteDatabase d = null;
        try {
            d = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            String version = null;
            int schema = -1;
            try (Cursor c = d.rawQuery("SELECT key, value FROM meta WHERE key IN ('db_version','schema_version')", null)) {
                while (c.moveToNext()) {
                    if ("db_version".equals(c.getString(0))) version = c.getString(1);
                    else schema = Integer.parseInt(c.getString(1));
                }
            }
            if (schema != SCHEMA_VERSION || version == null) return null;
            try (Cursor c = d.rawQuery("SELECT count(*) FROM collections", null)) {
                if (!c.moveToFirst() || c.getInt(0) == 0) return null;
            }
            try (Cursor c = d.rawQuery("SELECT docid FROM proverbs_fts WHERE proverbs_fts MATCH 'a*' LIMIT 1", null)) {
                c.moveToFirst();   // proves the full-text index is readable on this phone
            }
            return version;
        } catch (Exception e) {
            Log.e(TAG, "validate failed", e);
            return null;
        } finally {
            try { if (d != null) d.close(); } catch (Exception ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Meta & statistics
    // ─────────────────────────────────────────────────────────────────────

    public String meta(String key) {
        if (!ensureOpen()) return null;
        try (Cursor c = db.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : null;
        } catch (Exception e) {
            Log.e(TAG, "meta " + key, e);
            return null;
        }
    }

    /** Installed database version (a date like 20260925), or "" if none. */
    public String version() {
        String v = meta("db_version");
        return v != null ? v : "";
    }

    public Stats stats() {
        Stats s = new Stats();
        if (!ensureOpen()) return s;
        try (Cursor c = db.rawQuery("SELECT key, value FROM meta", null)) {
            while (c.moveToNext()) {
                String k = c.getString(0), v = c.getString(1);
                switch (k) {
                    case "total_collections":  s.collections  = parseLong(v); break;
                    case "total_entries":      s.entries      = parseLong(v); break;
                    case "total_proverbs":     s.proverbs     = parseLong(v); break;
                    case "total_synonyms":     s.synonyms     = parseLong(v); break;
                    case "total_references":   s.references   = parseLong(v); break;
                    case "total_translations": s.translations = parseLong(v); break;
                    case "db_version":         s.dbVersion    = v; break;
                    case "built_at":           s.builtAt      = v; break;
                    default: break;
                }
            }
        } catch (Exception e) { Log.e(TAG, "stats meta", e); }
        try (Cursor c = db.rawQuery(
                "SELECT dialect_id, proverbs, collections FROM dialect_stats ORDER BY proverbs DESC", null)) {
            while (c.moveToNext()) {
                DialectStat d = new DialectStat();
                d.dialectId = c.getInt(0);
                d.proverbs = c.getInt(1);
                d.collections = c.getInt(2);
                s.dialects.add(d);
            }
        } catch (Exception e) { Log.e(TAG, "stats dialects", e); }
        return s;
    }

    private static long parseLong(String v) {
        try { return Long.parseLong(v); } catch (Exception e) { return 0; }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Dialects & collections (cached)
    // ─────────────────────────────────────────────────────────────────────

    public synchronized List<Dialect> dialects() {
        if (dialectsCache != null) return dialectsCache;
        List<Dialect> list = new ArrayList<>();
        if (!ensureOpen()) return list;
        try (Cursor c = db.rawQuery("SELECT id, name_ku, name_en FROM dialects ORDER BY id", null)) {
            while (c.moveToNext()) {
                Dialect d = new Dialect();
                d.id = c.getInt(0);
                d.nameKu = c.getString(1);
                d.nameEn = c.getString(2);
                list.add(d);
            }
            dialectsCache = Collections.unmodifiableList(list);
        } catch (Exception e) { Log.e(TAG, "dialects", e); }
        return list;
    }

    public Dialect dialect(int id) {
        for (Dialect d : dialects()) if (d.id == id) return d;
        return null;
    }

    /** All collections in dictionary order. Loaded once, then served from memory. */
    public synchronized List<CollectionItem> collections() {
        if (collectionsCache != null) return collectionsCache;
        List<CollectionItem> list = new ArrayList<>();
        if (!ensureOpen()) return list;
        String sql =
                "SELECT c.id, c.representative_proverb_id, c.start_char, c.total_entries, " +
                "c.total_proverbs, c.total_synonyms, c.total_references, c.dialect_mask, " +
                "p.text, p.dialect_id " +
                "FROM collections c LEFT JOIN proverbs p ON p.id = c.representative_proverb_id " +
                "ORDER BY c.id";
        try (Cursor c = db.rawQuery(sql, null)) {
            Map<Integer, CollectionItem> byId = new HashMap<>(c.getCount() * 2);
            while (c.moveToNext()) {
                CollectionItem it = new CollectionItem();
                it.id              = c.getInt(0);
                it.repProverbId    = c.getInt(1);
                it.startChar       = c.getString(2);
                it.totalEntries    = c.getInt(3);
                it.totalProverbs   = c.getInt(4);
                it.totalSynonyms   = c.getInt(5);
                it.totalReferences = c.getInt(6);
                it.dialectMask     = c.getLong(7);
                it.repText         = c.getString(8);
                it.repDialectId    = c.getInt(9);
                list.add(it);
                byId.put(it.id, it);
            }
            collectionsCache = Collections.unmodifiableList(list);
            collectionById = byId;
        } catch (Exception e) { Log.e(TAG, "collections", e); }
        return list;
    }

    public CollectionItem collection(int id) {
        collections();
        Map<Integer, CollectionItem> m = collectionById;
        return m != null ? m.get(id) : null;
    }

    /** Title of a collection = its representative proverb. */
    public String collectionTitle(int id) {
        CollectionItem c = collection(id);
        return c != null && c.repText != null ? c.repText : "#" + id;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Collection content
    // ─────────────────────────────────────────────────────────────────────

    public List<Entry> entries(int collectionId) {
        List<Entry> list = new ArrayList<>();
        if (!ensureOpen()) return list;
        String sql =
                "SELECT e.entry_id, e.paragraph_order, e.is_representative, e.is_synonym, e.synonym_type, " +
                "e.is_reference, e.reference_type, e.header_level, p.id, p.text, p.dialect_id, " +
                "p.is_translation, p.translation_language " +
                "FROM collection_entries e JOIN proverbs p ON p.id = e.proverb_id " +
                "WHERE e.collection_id = ? ORDER BY e.paragraph_order";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(collectionId)})) {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.entryId             = c.getInt(0);
                e.collectionId        = collectionId;
                e.paragraphOrder      = c.getInt(1);
                e.isRepresentative    = c.getInt(2) == 1;
                e.isSynonym           = c.getInt(3) == 1;
                e.synonymType         = c.getString(4);
                e.isReference         = c.getInt(5) == 1;
                e.referenceType       = c.getString(6);
                e.headerLevel         = c.getInt(7);
                e.proverbId           = c.getInt(8);
                e.text                = c.getString(9);
                e.dialectId           = c.getInt(10);
                e.isTranslation       = c.getInt(11) == 1;
                e.translationLanguage = c.getString(12);
                list.add(e);
            }
        } catch (Exception e) { Log.e(TAG, "entries", e); }
        return list;
    }

    /** Where a reference entry points to: main target first, then the others. */
    public List<RefTarget> referenceTargets(int sourceEntryId) {
        List<RefTarget> list = new ArrayList<>();
        if (!ensureOpen()) return list;
        List<Integer> ids = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT main_target_entry_id, sub_target_entry_ids FROM reference_targets WHERE source_entry_id=?",
                new String[]{String.valueOf(sourceEntryId)})) {
            if (!c.moveToFirst()) return list;
            ids.add(c.getInt(0));
            String sub = c.getString(1);
            if (sub != null) {
                for (String part : sub.replaceAll("[\\[\\]\"\\s]", "").split(",")) {
                    if (part.isEmpty()) continue;
                    try {
                        int id = Integer.parseInt(part);
                        if (!ids.contains(id)) ids.add(id);
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "referenceTargets", e);
            return list;
        }
        for (int entryId : ids) {
            try (Cursor c = db.rawQuery(
                    "SELECT e.collection_id, p.text FROM collection_entries e " +
                    "JOIN proverbs p ON p.id = e.proverb_id WHERE e.entry_id=?",
                    new String[]{String.valueOf(entryId)})) {
                if (c.moveToFirst()) {
                    RefTarget t = new RefTarget();
                    t.entryId = entryId;
                    t.collectionId = c.getInt(0);
                    t.text = c.getString(1);
                    list.add(t);
                }
            } catch (Exception e) { Log.e(TAG, "referenceTarget " + entryId, e); }
        }
        return list;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Single proverb
    // ─────────────────────────────────────────────────────────────────────

    public ProverbInfo proverb(int proverbId) {
        if (!ensureOpen()) return null;
        try (Cursor c = db.rawQuery(
                "SELECT id, text, dialect_id, is_translation, translation_language FROM proverbs WHERE id=?",
                new String[]{String.valueOf(proverbId)})) {
            if (!c.moveToFirst()) return null;
            ProverbInfo p = new ProverbInfo();
            p.id = c.getInt(0);
            p.text = c.getString(1);
            p.dialectId = c.getInt(2);
            p.isTranslation = c.getInt(3) == 1;
            p.translationLanguage = c.getString(4);
            return p;
        } catch (Exception e) {
            Log.e(TAG, "proverb", e);
            return null;
        }
    }

    /** Several proverbs at once, returned in the order of the given ids (used by Favourites). */
    public List<SearchHit> proverbsByIds(List<Integer> ids) {
        List<SearchHit> out = new ArrayList<>();
        if (ids.isEmpty() || !ensureOpen()) return out;
        Map<Integer, SearchHit> found = new HashMap<>();
        StringBuilder ph = new StringBuilder();
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) ph.append(',');
            ph.append('?');
            args[i] = String.valueOf(ids.get(i));
        }
        try (Cursor c = db.rawQuery(
                "SELECT p.id, p.text, p.dialect_id, p.is_translation, " +
                "(SELECT e.collection_id FROM collection_entries e WHERE e.proverb_id = p.id " +
                " ORDER BY e.is_representative DESC, e.paragraph_order LIMIT 1) " +
                "FROM proverbs p WHERE p.id IN (" + ph + ")", args)) {
            while (c.moveToNext()) {
                SearchHit h = readHit(c);
                found.put(h.proverbId, h);
            }
        } catch (Exception e) { Log.e(TAG, "proverbsByIds", e); }
        for (int id : ids) {
            SearchHit h = found.get(id);
            if (h != null) out.add(h);
        }
        return out;
    }

    /**
     * Dialect view: every proverb of one dialect, in the order of the collections it first appears in
     * (= dictionary order), as list rows. Built from the cached collections, so letters match the main list.
     */
    public List<CollectionItem> proverbsOfDialect(int dialectId) {
        List<CollectionItem> out = new ArrayList<>();
        if (!ensureOpen()) return out;
        collections();
        try (Cursor c = db.rawQuery(
                "SELECT p.id, p.text, MIN(e.collection_id) AS cid FROM proverbs p " +
                "JOIN collection_entries e ON e.proverb_id = p.id WHERE p.dialect_id = ? " +
                "GROUP BY p.id ORDER BY cid, p.id", new String[]{String.valueOf(dialectId)})) {
            while (c.moveToNext()) {
                CollectionItem parent = collectionById.get(c.getInt(2));
                if (parent == null) continue;
                CollectionItem it = new CollectionItem();
                it.proverbRow = true;
                it.id = parent.id;
                it.repProverbId = c.getInt(0);
                it.repText = c.getString(1);
                it.repDialectId = dialectId;
                it.startChar = parent.startChar;
                it.parentTitle = parent.repText;
                out.add(it);
            }
        } catch (Exception e) { Log.e(TAG, "proverbsOfDialect", e); }
        return out;
    }

    /** Every place a proverb appears, including collections that REFER to it. */
    public List<Appearance> appearances(int proverbId) {
        List<Appearance> list = new ArrayList<>();
        if (!ensureOpen()) return list;
        Set<Integer> seenEntries = new HashSet<>();
        try (Cursor c = db.rawQuery(
                "SELECT collection_id, entry_id, is_representative, is_synonym, synonym_type, " +
                "is_reference, reference_type, header_level FROM collection_entries " +
                "WHERE proverb_id=? ORDER BY is_representative DESC, collection_id",
                new String[]{String.valueOf(proverbId)})) {
            while (c.moveToNext()) {
                Appearance a = new Appearance();
                a.collectionId = c.getInt(0);
                a.entryId = c.getInt(1);
                int header = c.getInt(7);
                if (c.getInt(2) == 1)      { a.role = Appearance.ROLE_MAIN; }
                else if (c.getInt(3) == 1) { a.role = Appearance.ROLE_SYNONYM;   a.detail = c.getString(4); }
                else if (c.getInt(5) == 1) { a.role = Appearance.ROLE_REFERENCE; a.detail = c.getString(6); }
                else if (header == 1)      { a.role = Appearance.ROLE_HEADER; }
                else if (header == 2)      { a.role = Appearance.ROLE_SUBHEADER; }
                else                       { a.role = Appearance.ROLE_ENTRY; }
                seenEntries.add(a.entryId);
                list.add(a);
            }
        } catch (Exception e) { Log.e(TAG, "appearances", e); }

        // Collections whose reference entries point at this proverb
        try (Cursor c = db.rawQuery(
                "SELECT src.collection_id, src.entry_id, src.reference_type FROM reference_targets rt " +
                "JOIN collection_entries tgt ON tgt.entry_id = rt.main_target_entry_id AND tgt.proverb_id = ? " +
                "JOIN collection_entries src ON src.entry_id = rt.source_entry_id " +
                "ORDER BY src.collection_id",
                new String[]{String.valueOf(proverbId)})) {
            while (c.moveToNext()) {
                int entryId = c.getInt(1);
                if (!seenEntries.add(entryId)) continue;
                Appearance a = new Appearance();
                a.collectionId = c.getInt(0);
                a.entryId = entryId;
                a.role = Appearance.ROLE_REFERENCED_FROM;
                a.detail = c.getString(2);
                list.add(a);
            }
        } catch (Exception e) { Log.e(TAG, "appearances refs", e); }
        return list;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Search
    //
    //   1. Word search  — full-text index, every word as a prefix  ("دار" finds "دارێک")
    //   2. Inside words — "contains" search to add matches inside longer words
    //   3. Similar      — only if 1+2 found nothing: trigram similarity, catches
    //                     spelling differences between dialects
    //
    //   The query is normalized exactly like the index, so typing Arabic ي/ك,
    //   old-style ه‌, or Turkish without ç/ş/ğ still finds the right proverbs.
    // ─────────────────────────────────────────────────────────────────────

    public SearchResult search(String rawQuery, int dialectId, Cancel cancel) {
        SearchResult result = new SearchResult();
        String q = TextNormalizer.normalize(rawQuery);
        result.query = q;
        if (q.isEmpty() || !ensureOpen()) return result;

        Set<Integer> seen = new HashSet<>();
        String dialectSql = dialectId > 0 ? " AND p.dialect_id = " + dialectId : "";
        String cols = "p.id, p.text, p.dialect_id, p.is_translation, " +
                "(SELECT e.collection_id FROM collection_entries e WHERE e.proverb_id = p.id " +
                " ORDER BY e.is_representative DESC, e.paragraph_order LIMIT 1) ";

        try {
            if (q.length() == 1) {
                // One letter: proverbs that START with it (the full-text index is not useful here)
                query(result, seen, "SELECT " + cols + "FROM proverbs p WHERE p.search LIKE ?" + dialectSql +
                        " LIMIT " + SEARCH_LIMIT, new String[]{q + "%"}, cancel);
            } else {
                // 1. word-prefix search (safe: the normalized query only has letters, digits, spaces)
                StringBuilder match = new StringBuilder();
                for (String tok : q.split(" ")) {
                    if (tok.isEmpty()) continue;
                    if (match.length() > 0) match.append(' ');
                    match.append(tok).append('*');
                }
                query(result, seen, "SELECT " + cols + "FROM proverbs_fts f JOIN proverbs p ON p.id = f.docid " +
                        "WHERE proverbs_fts MATCH ?" + dialectSql + " ORDER BY length(p.search) LIMIT " + SEARCH_LIMIT,
                        new String[]{match.toString()}, cancel);

                // 2. contains-search to fill up
                if (result.hits.size() < SEARCH_LIMIT && !isCancelled(cancel)) {
                    query(result, seen, "SELECT " + cols + "FROM proverbs p WHERE p.search LIKE ?" + dialectSql +
                            " LIMIT " + (SEARCH_LIMIT + seen.size()), new String[]{"%" + q + "%"}, cancel);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "search", e);
        }
        if (!result.hits.isEmpty()) {
            result.mode = SearchResult.MODE_MATCH;
            return result;
        }
        if (isCancelled(cancel) || q.length() < 3) return result;

        // 3. similar spellings
        similar(result, q, dialectId, cancel);
        if (!result.hits.isEmpty()) result.mode = SearchResult.MODE_SIMILAR;
        return result;
    }

    private void query(SearchResult r, Set<Integer> seen, String sql, String[] args, Cancel cancel) {
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext() && r.hits.size() < SEARCH_LIMIT) {
                if (isCancelled(cancel)) return;
                int id = c.getInt(0);
                if (!seen.add(id)) continue;
                r.hits.add(readHit(c));
            }
        }
    }

    private static SearchHit readHit(Cursor c) {
        SearchHit h = new SearchHit();
        h.proverbId = c.getInt(0);
        h.text = c.getString(1);
        h.dialectId = c.getInt(2);
        h.isTranslation = c.getInt(3) == 1;
        h.collectionId = c.isNull(4) ? 0 : c.getInt(4);
        return h;
    }

    private static boolean isCancelled(Cancel c) { return c != null && c.isCancelled(); }

    /** Trigram similarity over the whole collection (about half a second on a mid-range phone). */
    private void similar(SearchResult result, String q, int dialectId, Cancel cancel) {
        long[] qGrams = trigrams(" " + q + " ");
        if (qGrams.length == 0) return;
        Arrays.sort(qGrams);
        int needed = Math.max(2, (int) Math.ceil(qGrams.length * 0.5));

        final int keep = SIMILAR_LIMIT;
        int[] bestId = new int[keep];
        float[] bestScore = new float[keep];
        int count = 0;

        String sql = "SELECT id, search FROM proverbs p" + (dialectId > 0 ? " WHERE p.dialect_id = " + dialectId : "");
        boolean[] hit = new boolean[qGrams.length];
        try (Cursor c = db.rawQuery(sql, null)) {
            int row = 0;
            while (c.moveToNext()) {
                if ((++row & 2047) == 0 && isCancelled(cancel)) return;
                String s = c.getString(1);
                if (s == null || s.isEmpty()) continue;
                Arrays.fill(hit, false);
                int matched = 0;
                String t = " " + s + " ";
                for (int i = 0; i + 3 <= t.length(); i++) {
                    long g = gram(t, i);
                    int idx = Arrays.binarySearch(qGrams, g);
                    if (idx >= 0 && !hit[idx]) { hit[idx] = true; matched++; }
                }
                if (matched < needed) continue;
                // Prefer texts of similar length: penalize very long proverbs a little
                float score = matched / (float) qGrams.length - Math.min(0.2f, t.length() / 2000f);
                // insert into the top list
                if (count < keep) {
                    bestId[count] = c.getInt(0);
                    bestScore[count] = score;
                    count++;
                } else {
                    int worst = 0;
                    for (int k = 1; k < keep; k++) if (bestScore[k] < bestScore[worst]) worst = k;
                    if (score > bestScore[worst]) { bestId[worst] = c.getInt(0); bestScore[worst] = score; }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "similar scan", e);
            return;
        }
        if (count == 0) return;

        // sort best first
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) order[i] = i;
        final float[] sc = bestScore;
        Arrays.sort(order, (a, b) -> Float.compare(sc[b], sc[a]));
        List<Integer> ids = new ArrayList<>(count);
        for (Integer o : order) ids.add(bestId[o]);
        result.hits.addAll(proverbsByIds(ids));
    }

    private static long[] trigrams(String t) {
        Set<Long> set = new HashSet<>();
        for (int i = 0; i + 3 <= t.length(); i++) set.add(gram(t, i));
        long[] out = new long[set.size()];
        int i = 0;
        for (Long g : set) out[i++] = g;
        return out;
    }

    private static long gram(String t, int i) {
        return ((long) t.charAt(i) << 32) | ((long) t.charAt(i + 1) << 16) | t.charAt(i + 2);
    }
}
