package com.tecush.kurdishproverbs.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tecush.kurdishproverbs.App;
import com.tecush.kurdishproverbs.BuildConfig;
import com.tecush.kurdishproverbs.R;
import com.tecush.kurdishproverbs.core.AppExecutors;
import com.tecush.kurdishproverbs.core.Lang;
import com.tecush.kurdishproverbs.license.LicenseManager;
import com.tecush.kurdishproverbs.ui.widget.Fonts;

import java.util.Arrays;

public class SettingsActivity extends BaseActivity {

    private static final float[] SCALES = {0.85f, 0.92f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.55f, 1.7f};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setupTitle(getString(R.string.settings));

        // Language
        TextView tvLang = findViewById(R.id.tvLanguage);
        String cur = Lang.current(this);
        int curIdx = Arrays.asList(Lang.CODES).indexOf(cur);
        tvLang.setText(Lang.NATIVE_NAMES[Math.max(0, curIdx)]);
        findViewById(R.id.rowLanguage).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(Lang.NATIVE_NAMES, curIdx, (d, which) -> {
                    d.dismiss();
                    Lang.set(Lang.CODES[which]);
                })
                .show());

        // Theme
        TextView tvTheme = findViewById(R.id.tvTheme);
        String[] themes = {getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)};
        tvTheme.setText(themes[prefs.getThemeMode()]);
        findViewById(R.id.rowTheme).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_theme)
                .setSingleChoiceItems(themes, prefs.getThemeMode(), (d, which) -> {
                    d.dismiss();
                    prefs.setThemeMode(which);
                    App.applyTheme(which);
                })
                .show());

        // Text size
        SeekBar seek = findViewById(R.id.seekTextSize);
        TextView preview = findViewById(R.id.tvTextPreview);
        int idx = 2;
        for (int i = 0; i < SCALES.length; i++) if (Math.abs(SCALES[i] - prefs.getTextScale()) < 0.01f) idx = i;
        seek.setMax(SCALES.length - 1);
        seek.setProgress(idx);
        Fonts.setSize(preview, 16, SCALES[idx]);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                Fonts.setSize(preview, 16, SCALES[p]);
                prefs.setTextScale(SCALES[p]);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        bindSwitch(R.id.swDialect, prefs.showDialectLabels(), (b, on) -> prefs.setShowDialectLabels(on));
        bindSwitch(R.id.swColorDialect, prefs.colorByDialect(), (b, on) -> prefs.setColorByDialect(on));
        bindSwitch(R.id.swDaily, prefs.showDailyProverb(), (b, on) -> prefs.setShowDailyProverb(on));
        bindSwitch(R.id.swAutoUpdate, prefs.autoUpdateCheck(), (b, on) -> prefs.setAutoUpdateCheck(on));

        // Updates & database
        TextView tvVersions = findViewById(R.id.tvVersions);
        tvVersions.setText(getString(R.string.version_short, BuildConfig.VERSION_NAME));
        AppExecutors.db().execute(() -> {
            String v = db.version();
            AppExecutors.main(() -> tvVersions.setText(getString(R.string.versions_line, BuildConfig.VERSION_NAME,
                    v.isEmpty() ? "—" : v)));
        });
        findViewById(R.id.rowCheckUpdates).setOnClickListener(v -> UpdateFlow.manualCheck(this));
        findViewById(R.id.rowRedownload).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.db_redownload)
                .setMessage(R.string.db_redownload_confirm)
                .setPositiveButton(R.string.ok, (d, w) -> startActivity(new Intent(this, DbSetupActivity.class)))
                .setNegativeButton(R.string.cancel, null)
                .show());

        // Account
        LicenseManager lm = LicenseManager.get(this);
        TextView tvAccount = findViewById(R.id.tvAccount);
        TextView tvDevice = findViewById(R.id.tvDevice);
        if (!LicenseManager.isConfigured()) {
            tvAccount.setText(R.string.account_not_configured);
        } else if (!lm.registeredEmail().isEmpty()) {
            tvAccount.setText(getString(R.string.account_registered, lm.registeredName(), lm.registeredVia()));
        } else {
            tvAccount.setText(R.string.account_unregistered);
        }
        tvDevice.setText(getString(R.string.account_device, lm.deviceId().substring(0, 8)));
        findViewById(R.id.rowPrivacy).setOnClickListener(v -> UiUtil.openPrivacy(this));
    }

    private void bindSwitch(int id, boolean value, CompoundButton.OnCheckedChangeListener l) {
        CompoundButton sw = findViewById(id);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener(l);
    }
}
