package com.tecush.kurdishproverbs.data;

import java.util.ArrayList;
import java.util.List;

/** Plain data holders returned by {@link Db}. */
public final class Models {

    private Models() {}

    public static final class Dialect {
        public int id;
        public String nameKu, nameEn;

        /** Kurdish-script name for Sorani/Persian UI, otherwise the English/Latin name. */
        public String displayName(String lang) {
            boolean arabicScript = "ckb".equals(lang) || "fa".equals(lang);
            if (arabicScript || nameEn == null || nameEn.isEmpty()) return nameKu != null ? nameKu : ("#" + id);
            return nameEn;
        }
    }

    public static final class CollectionItem {
        public int id, repProverbId, repDialectId;
        public int totalEntries, totalProverbs, totalSynonyms, totalReferences;
        public long dialectMask;          // bit n set = dialect n appears in this collection
        public String startChar, repText;
        /** Dialect view: this row is one proverb (repProverbId) inside collection id. */
        public boolean proverbRow;
        public String parentTitle;

        public boolean hasDialect(int dialectId) {
            if (dialectId <= 0) return true;
            if (dialectId < 63) return (dialectMask & (1L << dialectId)) != 0;
            return repDialectId == dialectId;
        }
    }

    public static final class Entry {
        public int entryId, collectionId, paragraphOrder, headerLevel, proverbId, dialectId;
        public boolean isRepresentative, isSynonym, isReference, isTranslation;
        public String synonymType, referenceType, text, translationLanguage;
    }

    public static final class SearchHit {
        public int proverbId, dialectId, collectionId;
        public boolean isTranslation;
        public String text;
    }

    public static final class SearchResult {
        public static final int MODE_NONE = 0, MODE_MATCH = 1, MODE_SIMILAR = 2;
        public final List<SearchHit> hits = new ArrayList<>();
        public int mode = MODE_NONE;
        public String query = "";
    }

    public static final class ProverbInfo {
        public int id, dialectId;
        public boolean isTranslation;
        public String text, translationLanguage;
    }

    public static final class Appearance {
        public static final int ROLE_MAIN = 0, ROLE_SYNONYM = 1, ROLE_REFERENCE = 2,
                ROLE_HEADER = 3, ROLE_SUBHEADER = 4, ROLE_ENTRY = 5, ROLE_REFERENCED_FROM = 6;
        public int collectionId, entryId, role;
        public String detail;             // synonym / reference type, may be null
    }

    public static final class RefTarget {
        public int entryId, collectionId;
        public String text;
    }

    public static final class DialectStat {
        public int dialectId, proverbs, collections;
    }

    public static final class Stats {
        public long collections, entries, proverbs, synonyms, references, translations;
        public String dbVersion = "", builtAt = "";
        public final List<DialectStat> dialects = new ArrayList<>();
    }
}
