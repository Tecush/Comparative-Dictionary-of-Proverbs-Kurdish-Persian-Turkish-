package com.tecush.kurdishproverbs.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.navigation.NavigationView;
import com.tecush.kurdishproverbs.BuildConfig;
import com.tecush.kurdishproverbs.R;
import com.tecush.kurdishproverbs.core.AppExecutors;
import com.tecush.kurdishproverbs.core.Cancel;
import com.tecush.kurdishproverbs.core.Lang;
import com.tecush.kurdishproverbs.data.Models.CollectionItem;
import com.tecush.kurdishproverbs.data.Models.Dialect;
import com.tecush.kurdishproverbs.data.Models.SearchHit;
import com.tecush.kurdishproverbs.data.Models.SearchResult;
import com.tecush.kurdishproverbs.license.LicenseManager;
import com.tecush.kurdishproverbs.ui.adapter.CollectionListAdapter;
import com.tecush.kurdishproverbs.ui.adapter.SearchAdapter;
import com.tecush.kurdishproverbs.ui.widget.AlphabetIndexBar;
import com.tecush.kurdishproverbs.ui.widget.DialectColors;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Main screen: collections (alphabetical with index bar, or largest first),
 * dialect filter chips, instant search, proverb of the day, side menu.
 */
public class MainActivity extends BaseActivity
        implements CollectionListAdapter.Listener, SearchAdapter.Listener {

    private static final long SEARCH_DELAY_MS = 250;
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;

    private DrawerLayout drawer;
    private NavigationView nav;
    private EditText etSearch;
    private View btnClear, progress, emptyGroup;
    private TextView tvEmpty, letterBubble;
    private Button btnEmptyAction;
    private RecyclerView rv;
    private LinearLayoutManager llm;
    private AlphabetIndexBar indexBar;
    private ChipGroup chipGroup;

    private CollectionListAdapter listAdapter;
    private SearchAdapter searchAdapter;

    private List<CollectionItem> all = new ArrayList<>();
    private final Map<Integer, String> dialectNames = new HashMap<>();
    private int loadedGeneration = -1;
    private Parcelable listScrollState;
    private boolean shownDaily;

    private Cancel searchCancel;
    private final Runnable searchRunnable = this::runSearch;
    private final Runnable hideBubble = () -> letterBubble.animate().alpha(0f).setDuration(200)
            .withEndAction(() -> letterBubble.setVisibility(View.GONE)).start();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!db.isInstalled()) {               // e.g. the user cleared app storage
            startActivity(LauncherActivity.next(this));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);

        drawer = findViewById(R.id.drawerLayout);
        nav = findViewById(R.id.navView);
        etSearch = findViewById(R.id.etSearch);
        btnClear = findViewById(R.id.btnClear);
        progress = findViewById(R.id.progress);
        emptyGroup = findViewById(R.id.emptyGroup);
        tvEmpty = findViewById(R.id.tvEmpty);
        btnEmptyAction = findViewById(R.id.btnEmptyAction);
        letterBubble = findViewById(R.id.letterBubble);
        rv = findViewById(R.id.rvList);
        indexBar = findViewById(R.id.alphabetBar);
        chipGroup = findViewById(R.id.chipGroup);

        setupTitleWithMenu(getString(R.string.app_name), v -> drawer.openDrawer(Gravity.START));
        addAction(R.drawable.ic_star, R.string.favorites, v -> startActivity(new android.content.Intent(this, FavoritesActivity.class)));
        addAction(R.drawable.ic_stats, R.string.statistics, v -> startActivity(new android.content.Intent(this, StatsActivity.class)));

        llm = new LinearLayoutManager(this);
        rv.setLayoutManager(llm);
        rv.setHasFixedSize(true);
        listAdapter = new CollectionListAdapter(this);
        searchAdapter = new SearchAdapter(this, id -> db.collectionTitle(id));
        rv.setAdapter(listAdapter);

        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView r, int dx, int dy) {
                if (rv.getAdapter() != listAdapter || indexBar.getVisibility() != View.VISIBLE) return;
                int first = llm.findFirstVisibleItemPosition();
                String letter = listAdapter.letterForPosition(first);
                if (letter != null) indexBar.setActiveLetter(letter);
            }
            @Override
            public void onScrollStateChanged(RecyclerView r, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard();
            }
        });

        indexBar.setOnIndexDragListener((dragging, letter) -> {
            AppExecutors.cancelMain(hideBubble);
            if (dragging && letter != null) {
                letterBubble.setText(letter);
                letterBubble.setAlpha(1f);
                letterBubble.setVisibility(View.VISIBLE);
            } else {
                AppExecutors.mainDelayed(hideBubble, 350);
            }
        });

        setupSearch();
        setupDrawer();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(Gravity.START)) drawer.closeDrawer(Gravity.START);
                else if (etSearch.getText().length() > 0) etSearch.setText("");
                else finish();
            }
        });

        loadData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (all.isEmpty()) return;
        if (db.generation() != loadedGeneration) { loadData(); return; }
        applyDisplaySettings();          // settings may have changed meanwhile
        updateSortChecks();
        if (shownDaily != prefs.showDailyProverb()) refreshList();
    }

    @Override
    public void onDatabaseReplaced() { loadData(); }

    // ── Loading ───────────────────────────────────────────────────────────

    private void loadData() {
        progress.setVisibility(View.VISIBLE);
        emptyGroup.setVisibility(View.GONE);
        AppExecutors.db().execute(() -> {
            boolean ok = db.ensureOpen();
            List<CollectionItem> cols = ok ? db.collections() : new ArrayList<>();
            List<Dialect> dialects = ok ? db.dialects() : new ArrayList<>();
            int gen = db.generation();
            AppExecutors.main(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                if (!ok || cols.isEmpty()) { showDbError(); return; }
                loadedGeneration = gen;
                all = cols;
                dialectRows.clear();
                String lang = Lang.current(this);
                dialectNames.clear();
                for (Dialect d : dialects) dialectNames.put(d.id, d.displayName(lang));
                buildDialectChips(dialects, lang);
                applyDisplaySettings();
                refreshList();
                if (etSearch.getText().length() > 0) runSearch();
                afterFirstLoad();
            });
        });
    }

    private void afterFirstLoad() {
        UpdateFlow.autoCheck(this);
        long now = System.currentTimeMillis();
        if (now - prefs.getLastCheckIn() > WEEK) {
            prefs.setLastCheckIn(now);
            AppExecutors.net().execute(() -> {
                String v = "";
                try { v = AppExecutors.db().submit(() -> db.version()).get(); } catch (Exception ignored) {}
                LicenseManager.get(this).checkIn(v);
            });
        }
    }

    private void showDbError() {
        emptyGroup.setVisibility(View.VISIBLE);
        tvEmpty.setText(R.string.error_db);
        btnEmptyAction.setVisibility(View.VISIBLE);
        btnEmptyAction.setText(R.string.db_redownload);
        btnEmptyAction.setOnClickListener(v -> {
            startActivity(new android.content.Intent(this, DbSetupActivity.class));
        });
    }

    private void applyDisplaySettings() {
        float scale = prefs.getTextScale();
        listAdapter.setDisplay(dialectNames, prefs.showDialectLabels(), prefs.colorByDialect(), scale);
        searchAdapter.setDisplay(dialectNames, prefs.showDialectLabels(), prefs.colorByDialect(), scale);
    }

    // ── Collections list ──────────────────────────────────────────────────

    private final Map<Integer, List<CollectionItem>> dialectRows = new HashMap<>();

    private void refreshList() {
        int dialect = prefs.getDialectFilter();
        if (dialect > 0 && !dialectRows.containsKey(dialect)) {
            // first time for this dialect: load its proverbs in the background, then show them
            progress.setVisibility(View.VISIBLE);
            AppExecutors.db().execute(() -> {
                List<CollectionItem> rows = db.proverbsOfDialect(dialect);
                AppExecutors.main(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progress.setVisibility(View.GONE);
                    dialectRows.put(dialect, rows);
                    if (prefs.getDialectFilter() == dialect) refreshList();
                });
            });
            return;
        }
        boolean bySize = prefs.getSortMode() == 1 && dialect == 0;
        List<CollectionItem> filtered;
        if (dialect > 0) {
            filtered = dialectRows.get(dialect);              // the dialect's own proverbs
        } else {
            filtered = new ArrayList<>(all);
            if (bySize) filtered.sort((a, b) -> Integer.compare(b.totalProverbs, a.totalProverbs));
        }

        CollectionItem daily = (prefs.showDailyProverb() && dialect == 0 && !bySize) ? proverbOfTheDay() : null;
        listAdapter.submit(filtered, !bySize, daily);
        shownDaily = prefs.showDailyProverb();

        if (etSearch.getText().length() == 0) {
            rv.setAdapter(listAdapter);
            llm.scrollToPositionWithOffset(0, 0);
            boolean showIndex = !bySize && listAdapter.getLetters().size() > 1;
            indexBar.setVisibility(showIndex ? View.VISIBLE : View.GONE);
            if (showIndex) indexBar.setLetters(listAdapter.getLetters(),
                    letter -> llm.scrollToPositionWithOffset(listAdapter.positionForLetter(letter), 0));
            showEmpty(filtered.isEmpty() ? getString(R.string.no_collections) : null);
        }
    }

    /** Same proverb for everyone on the same day. */
    private CollectionItem proverbOfTheDay() {
        if (all.isEmpty()) return null;
        Calendar c = Calendar.getInstance();
        long seed = c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR);
        Random r = new Random(seed * 7919L);
        for (int tries = 0; tries < 20; tries++) {
            CollectionItem item = all.get(r.nextInt(all.size()));
            if (item.repText != null && item.repText.length() >= 12 && item.repText.length() <= 160) return item;
        }
        return all.get(r.nextInt(all.size()));
    }

    private void buildDialectChips(List<Dialect> dialects, String lang) {
        chipGroup.removeAllViews();
        int selected = prefs.getDialectFilter();
        addChip(0, getString(R.string.all_dialects), getColor(R.color.link), selected == 0);
        boolean found = selected == 0;
        for (Dialect d : dialects) {
            addChip(d.id, d.displayName(lang), DialectColors.get(this, d.id), selected == d.id);
            if (selected == d.id) found = true;
        }
        if (!found) { prefs.setDialectFilter(0); ((Chip) chipGroup.getChildAt(0)).setChecked(true); }
    }

    private void addChip(int dialectId, String label, int color, boolean checked) {
        Chip chip = new Chip(this);
        chip.setId(View.generateViewId());
        chip.setText(label);
        chip.setCheckable(true);
        chip.setCheckedIconVisible(false);
        chip.setTextColor(color);
        chip.setChipStrokeWidth(dp(1));
        chip.setChipStrokeColor(ColorStateList.valueOf(color));
        chip.setChipBackgroundColor(ColorStateList.valueOf(getColor(R.color.surface)));
        chipGroup.addView(chip);
        chip.setChecked(checked);
        chip.setOnClickListener(v -> {
            if (prefs.getDialectFilter() == dialectId) { chip.setChecked(true); return; }
            prefs.setDialectFilter(dialectId);
            refreshList();
            if (etSearch.getText().length() > 0) scheduleSearch(0);
        });
    }

    private void showEmpty(String message) {
        boolean show = message != null;
        emptyGroup.setVisibility(show ? View.VISIBLE : View.GONE);
        btnEmptyAction.setVisibility(View.GONE);
        if (show) tvEmpty.setText(message);
    }

    // ── Search ────────────────────────────────────────────────────────────

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                boolean has = s.toString().trim().length() > 0;
                btnClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (has) {
                    scheduleSearch(SEARCH_DELAY_MS);
                } else {
                    cancelSearch();
                    backToList();
                }
            }
        });
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { scheduleSearch(0); hideKeyboard(); return true; }
            return false;
        });
        btnClear.setOnClickListener(v -> etSearch.setText(""));
    }

    private void scheduleSearch(long delay) {
        AppExecutors.cancelMain(searchRunnable);
        AppExecutors.mainDelayed(searchRunnable, delay);
    }

    private void cancelSearch() {
        AppExecutors.cancelMain(searchRunnable);
        if (searchCancel != null) searchCancel.cancel();
    }

    private void runSearch() {
        final String raw = etSearch.getText().toString().trim();
        if (raw.isEmpty() || all.isEmpty()) return;
        if (searchCancel != null) searchCancel.cancel();
        final Cancel token = new Cancel();
        searchCancel = token;
        final int dialect = prefs.getDialectFilter();
        if (rv.getAdapter() == listAdapter) listScrollState = llm.onSaveInstanceState();
        progress.setVisibility(View.VISIBLE);
        AppExecutors.db().execute(() -> {
            if (token.isCancelled()) return;
            SearchResult r = db.search(raw, dialect, token);
            AppExecutors.main(() -> {
                // Ignore results that arrive after the text changed or was cleared
                if (token.isCancelled() || !raw.equals(etSearch.getText().toString().trim())) return;
                progress.setVisibility(View.GONE);
                showResults(r, raw);
            });
        });
    }

    private void showResults(SearchResult r, String raw) {
        indexBar.setVisibility(View.GONE);
        if (rv.getAdapter() != searchAdapter) rv.setAdapter(searchAdapter);
        if (r.hits.isEmpty()) {
            searchAdapter.submit(new ArrayList<>(), null, raw);
            showEmpty(getString(R.string.no_results, raw));
            return;
        }
        showEmpty(null);
        String n = Lang.num(this, r.hits.size());
        String header = r.mode == SearchResult.MODE_SIMILAR
                ? getString(R.string.results_similar, n) : getString(R.string.results_count, n);
        searchAdapter.submit(r.hits, header, raw);
        llm.scrollToPositionWithOffset(0, 0);
    }

    private void backToList() {
        progress.setVisibility(View.GONE);
        if (rv.getAdapter() == listAdapter) return;
        rv.setAdapter(listAdapter);
        boolean bySize = prefs.getSortMode() == 1 && prefs.getDialectFilter() == 0;
        boolean showIndex = !bySize && listAdapter.getLetters().size() > 1;
        indexBar.setVisibility(showIndex ? View.VISIBLE : View.GONE);
        showEmpty(listAdapter.getItemCount() == 0 ? getString(R.string.no_collections) : null);
        if (listScrollState != null) llm.onRestoreInstanceState(listScrollState);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        etSearch.clearFocus();
    }

    // ── List callbacks ────────────────────────────────────────────────────

    @Override
    public void onCollectionClick(CollectionItem c) {
        CollectionActivity.start(this, c.id, 0, c.proverbRow ? c.repProverbId : 0);
    }

    @Override
    public void onCollectionLongClick(CollectionItem c) {
        ProverbActions.show(this, c.repProverbId, c.repText, c.id, null);
    }

    @Override
    public void onDailyShare(CollectionItem c) { UiUtil.shareProverb(this, c.repText); }

    @Override
    public void onHitClick(SearchHit h) {
        hideKeyboard();
        if (h.collectionId > 0) CollectionActivity.start(this, h.collectionId, 0, h.proverbId);
        else ProverbActivity.start(this, h.proverbId);
    }

    @Override
    public void onHitLongClick(SearchHit h) { ProverbActions.show(this, h.proverbId, h.text, h.collectionId, null); }

    // ── Drawer ────────────────────────────────────────────────────────────

    private void setupDrawer() {
        View header = nav.getHeaderView(0);
        TextView ver = header.findViewById(R.id.navVersion);
        ver.setText(getString(R.string.version_short, BuildConfig.VERSION_NAME));
        updateSortChecks();
        nav.setNavigationItemSelectedListener(this::onDrawerItem);
    }

    private void updateSortChecks() {
        if (nav == null) return;
        nav.getMenu().findItem(R.id.nav_sort_alpha).setChecked(prefs.getSortMode() == 0);
        nav.getMenu().findItem(R.id.nav_sort_size).setChecked(prefs.getSortMode() == 1);
    }

    private boolean onDrawerItem(MenuItem item) {
        int id = item.getItemId();
        drawer.closeDrawer(Gravity.START);
        if (id == R.id.nav_favorites) startActivity(new android.content.Intent(this, FavoritesActivity.class));
        else if (id == R.id.nav_random) openRandom();
        else if (id == R.id.nav_stats) startActivity(new android.content.Intent(this, StatsActivity.class));
        else if (id == R.id.nav_sort_alpha || id == R.id.nav_sort_size) {
            int mode = id == R.id.nav_sort_size ? 1 : 0;
            if (mode != prefs.getSortMode()) {
                prefs.setSortMode(mode);
                updateSortChecks();
                refreshList();
            }
        }
        else if (id == R.id.nav_settings) startActivity(new android.content.Intent(this, SettingsActivity.class));
        else if (id == R.id.nav_updates) UpdateFlow.manualCheck(this);
        else if (id == R.id.nav_about) startActivity(new android.content.Intent(this, AboutActivity.class));
        else if (id == R.id.nav_website) UiUtil.openUrl(this, BuildConfig.WEBSITE_URL);
        else if (id == R.id.nav_share_app) UiUtil.share(this, getString(R.string.share_app_text, BuildConfig.WEBSITE_URL));
        else if (id == R.id.nav_contact) UiUtil.email(this);
        return true;
    }

    private void openRandom() {
        if (all.isEmpty()) return;
        CollectionItem c = all.get(new Random().nextInt(all.size()));
        CollectionActivity.start(this, c.id, 0, 0);
    }
}
