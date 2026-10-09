package com.tecush.kurdishproverbs.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tecush.kurdishproverbs.R;
import com.tecush.kurdishproverbs.core.Lang;
import com.tecush.kurdishproverbs.data.Models.CollectionItem;
import com.tecush.kurdishproverbs.ui.widget.DialectColors;
import com.tecush.kurdishproverbs.ui.widget.Fonts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main list: optional "proverb of the day" card, letter headers, collection rows.
 * Header counts and letter positions are computed once per data change
 * (1.0 recounted them on every scroll).
 */
public class CollectionListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onCollectionClick(CollectionItem c);
        void onCollectionLongClick(CollectionItem c);
        void onDailyShare(CollectionItem c);
    }

    private static final int T_DAILY = 0, T_HEADER = 1, T_ROW = 2;

    private static final class Row {
        final int type;
        final String letter;
        final int count;
        final CollectionItem item;
        Row(int type, String letter, int count, CollectionItem item) {
            this.type = type; this.letter = letter; this.count = count; this.item = item;
        }
    }

    private final Listener listener;
    private final List<Row> rows = new ArrayList<>();
    private final List<String> letters = new ArrayList<>();
    private final Map<String, Integer> letterPos = new HashMap<>();
    private String[] letterOfRow = new String[0];

    private Map<Integer, String> dialectNames = new HashMap<>();
    private boolean showDialect = true, colorByDialect = false;
    private float scale = 1f;

    public CollectionListAdapter(Listener l) {
        this.listener = l;
        setHasStableIds(true);
    }

    public void setDisplay(Map<Integer, String> names, boolean showDialect, boolean colorByDialect, float scale) {
        this.dialectNames = names;
        this.showDialect = showDialect;
        this.colorByDialect = colorByDialect;
        this.scale = scale;
        notifyDataSetChanged();
    }

    /**
     * @param grouped true = alphabetical with letter headers; false = flat (size order)
     * @param daily   collection shown as "proverb of the day", or null
     */
    public void submit(List<CollectionItem> items, boolean grouped, CollectionItem daily) {
        rows.clear();
        letters.clear();
        letterPos.clear();
        if (daily != null) rows.add(new Row(T_DAILY, null, 0, daily));

        if (grouped) {
            int i = 0;
            while (i < items.size()) {
                String letter = letterOf(items.get(i));
                int j = i;
                while (j < items.size() && letterOf(items.get(j)).equals(letter)) j++;
                if (!letterPos.containsKey(letter)) {      // a letter appears once in dictionary order
                    letterPos.put(letter, rows.size());
                    letters.add(letter);
                }
                rows.add(new Row(T_HEADER, letter, j - i, null));
                for (int k = i; k < j; k++) rows.add(new Row(T_ROW, letter, 0, items.get(k)));
                i = j;
            }
        } else {
            for (CollectionItem c : items) rows.add(new Row(T_ROW, null, 0, c));
        }

        letterOfRow = new String[rows.size()];
        String cur = letters.isEmpty() ? null : letters.get(0);
        for (int r = 0; r < rows.size(); r++) {
            if (rows.get(r).letter != null) cur = rows.get(r).letter;
            letterOfRow[r] = cur;
        }
        notifyDataSetChanged();
    }

    private static String letterOf(CollectionItem c) {
        if (c.startChar != null && !c.startChar.isEmpty()) return c.startChar;
        if (c.repText != null && !c.repText.isEmpty()) return c.repText.substring(0, 1);
        return "?";
    }

    public List<String> getLetters() { return letters; }

    public int positionForLetter(String letter) {
        Integer p = letterPos.get(letter);
        return p != null ? p : 0;
    }

    public String letterForPosition(int position) {
        if (position < 0 || position >= letterOfRow.length) return null;
        return letterOfRow[position];
    }

    public boolean hasDaily() { return !rows.isEmpty() && rows.get(0).type == T_DAILY; }

    // ── Adapter ───────────────────────────────────────────────────────────

    @Override public int getItemCount() { return rows.size(); }
    @Override public int getItemViewType(int position) { return rows.get(position).type; }

    @Override
    public long getItemId(int position) {
        Row r = rows.get(position);
        if (r.type == T_DAILY) return -1;
        if (r.type == T_HEADER) return -1000L - position;
        return r.item.proverbRow ? (1L << 40) + r.item.repProverbId : r.item.id;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == T_DAILY) return new DailyVH(inf.inflate(R.layout.item_daily, parent, false));
        if (viewType == T_HEADER) return new HeaderVH(inf.inflate(R.layout.item_letter_header, parent, false));
        return new RowVH(inf.inflate(R.layout.item_collection, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row r = rows.get(position);
        Context ctx = holder.itemView.getContext();
        if (holder instanceof HeaderVH) {
            HeaderVH h = (HeaderVH) holder;
            h.letter.setText(r.letter);
            h.count.setText(Lang.num(ctx, r.count));
        } else if (holder instanceof DailyVH) {
            DailyVH h = (DailyVH) holder;
            CollectionItem c = r.item;
            h.text.setText(c.repText);
            Fonts.setSize(h.text, 18, scale);
            h.meta.setText(meta(ctx, c));
            h.itemView.setOnClickListener(v -> listener.onCollectionClick(c));
            h.share.setOnClickListener(v -> listener.onDailyShare(c));
        } else {
            RowVH h = (RowVH) holder;
            CollectionItem c = r.item;
            h.text.setText(c.repText != null ? c.repText : "—");
            Fonts.setSize(h.text, 16, scale);
            int dColor = DialectColors.get(ctx, c.repDialectId);
            h.text.setTextColor(colorByDialect ? dColor : ctx.getColor(R.color.textPrimary));
            String dName = dialectNames.get(c.repDialectId);
            if (showDialect && dName != null) {
                h.dialect.setVisibility(View.VISIBLE);
                h.dialect.setText(dName);
                h.dialect.setTextColor(dColor);
            } else {
                h.dialect.setVisibility(View.GONE);
            }
            if (c.proverbRow) {
                boolean other = c.parentTitle != null && !c.parentTitle.equals(c.repText);
                h.meta.setText(other ? "↲ " + c.parentTitle : "");
                h.meta.setTextDirection(View.TEXT_DIRECTION_RTL);
            } else {
                h.meta.setText(meta(ctx, c));
                h.meta.setTextDirection(View.TEXT_DIRECTION_LOCALE);
            }
            h.itemView.setOnClickListener(v -> listener.onCollectionClick(c));
            h.itemView.setOnLongClickListener(v -> { listener.onCollectionLongClick(c); return true; });
        }
    }

    static String meta(Context ctx, CollectionItem c) {
        StringBuilder sb = new StringBuilder(ctx.getString(R.string.count_proverbs, Lang.num(ctx, c.totalProverbs)));
        if (c.totalSynonyms > 0)
            sb.append("  ·  ").append(ctx.getString(R.string.count_synonyms, Lang.num(ctx, c.totalSynonyms)));
        if (c.totalReferences > 0)
            sb.append("  ·  ").append(ctx.getString(R.string.count_references, Lang.num(ctx, c.totalReferences)));
        return sb.toString();
    }

    static final class HeaderVH extends RecyclerView.ViewHolder {
        final TextView letter, count;
        HeaderVH(View v) { super(v); letter = v.findViewById(R.id.tvLetter); count = v.findViewById(R.id.tvHeaderCount); }
    }

    static final class RowVH extends RecyclerView.ViewHolder {
        final TextView text, dialect, meta;
        RowVH(View v) {
            super(v);
            text = v.findViewById(R.id.tvText);
            dialect = v.findViewById(R.id.tvDialect);
            meta = v.findViewById(R.id.tvMeta);
        }
    }

    static final class DailyVH extends RecyclerView.ViewHolder {
        final TextView text, meta;
        final View share;
        DailyVH(View v) {
            super(v);
            text = v.findViewById(R.id.tvDailyText);
            meta = v.findViewById(R.id.tvDailyMeta);
            share = v.findViewById(R.id.btnDailyShare);
        }
    }
}
