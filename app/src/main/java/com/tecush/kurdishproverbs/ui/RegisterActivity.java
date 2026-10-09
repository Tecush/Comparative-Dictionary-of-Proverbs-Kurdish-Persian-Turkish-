package com.tecush.kurdishproverbs.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.tecush.kurdishproverbs.R;
import com.tecush.kurdishproverbs.core.AppExecutors;
import com.tecush.kurdishproverbs.core.Lang;
import com.tecush.kurdishproverbs.license.LicenseManager;

/**
 * First-run registration: language, name, email → 6-digit code by email → verify.
 * The website's privacy page explains what is stored.
 */
public class RegisterActivity extends BaseActivity {

    private static final String S_STEP = "step", S_RESEND_AT = "resend_at";

    private View stepDetails, stepCode, stepTelegram, progress, btnSend, btnVerify, btnResend;
    private EditText etName, etEmail, etCode;
    private TextView tvError, tvCodeSent;
    private int step = 1;
    private long resendAllowedAt = 0;
    private boolean busy;
    private final Runnable resendTicker = this::updateResend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        setupTitleNoNav(getString(R.string.register_title));

        stepDetails = findViewById(R.id.stepDetails);
        stepCode = findViewById(R.id.stepCode);
        stepTelegram = findViewById(R.id.stepTelegram);
        progress = findViewById(R.id.progress);
        etName = findViewById(R.id.etName);
        etEmail = findViewById(R.id.etEmail);
        etCode = findViewById(R.id.etCode);
        tvError = findViewById(R.id.tvError);
        tvCodeSent = findViewById(R.id.tvCodeSent);
        btnSend = findViewById(R.id.btnSendCode);
        btnVerify = findViewById(R.id.btnVerify);
        btnResend = findViewById(R.id.btnResend);

        buildLanguageChips();

        btnSend.setOnClickListener(v -> sendCode());
        btnVerify.setOnClickListener(v -> verify());
        btnResend.setOnClickListener(v -> sendCode());
        findViewById(R.id.btnChangeEmail).setOnClickListener(v -> showStep(1));
        findViewById(R.id.tvPrivacyLink).setOnClickListener(v -> UiUtil.openPrivacy(this));

        boolean tg = LicenseManager.isTelegramConfigured();
        setVisible(findViewById(R.id.tvOr), tg);
        setVisible(findViewById(R.id.btnTelegram), tg);
        findViewById(R.id.btnTelegram).setOnClickListener(v -> { showStep(3); openTelegram(); });
        findViewById(R.id.btnTgOpen).setOnClickListener(v -> openTelegram());
        findViewById(R.id.btnTgVerify).setOnClickListener(v -> verifyTelegram());
        findViewById(R.id.btnTgBack).setOnClickListener(v -> showStep(1));
        ((TextView) findViewById(R.id.tvTgSteps)).setText(getString(R.string.register_tg_wait,
                "@" + com.tecush.kurdishproverbs.BuildConfig.TELEGRAM_BOT));

        if (savedInstanceState != null) {
            step = savedInstanceState.getInt(S_STEP, 1);
            resendAllowedAt = savedInstanceState.getLong(S_RESEND_AT, 0);
        }
        showStep(step);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(S_STEP, step);
        out.putLong(S_RESEND_AT, resendAllowedAt);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppExecutors.cancelMain(resendTicker);
    }

    private void buildLanguageChips() {
        ChipGroup group = findViewById(R.id.chipsLanguage);
        String current = Lang.current(this);
        for (int i = 0; i < Lang.CODES.length; i++) {
            final String code = Lang.CODES[i];
            Chip chip = new Chip(this);
            chip.setText(Lang.NATIVE_NAMES[i]);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setId(View.generateViewId());
            group.addView(chip);
            if (code.equals(current)) chip.setChecked(true);
            chip.setOnClickListener(v -> {
                if (!code.equals(Lang.current(this))) Lang.set(code);   // screen is recreated in the new language
            });
        }
    }

    private void showStep(int s) {
        step = s;
        stepDetails.setVisibility(s == 1 ? View.VISIBLE : View.GONE);
        stepCode.setVisibility(s == 2 ? View.VISIBLE : View.GONE);
        stepTelegram.setVisibility(s == 3 ? View.VISIBLE : View.GONE);
        tvError.setVisibility(View.GONE);
        if (s == 2) {
            tvCodeSent.setText(getString(R.string.register_code_sent, etEmail.getText().toString().trim()));
            etCode.requestFocus();
            updateResend();
        }
    }

    private void updateResend() {
        long left = resendAllowedAt - System.currentTimeMillis();
        if (left > 0) {
            btnResend.setEnabled(false);
            ((TextView) btnResend).setText(getString(R.string.register_resend_wait, Lang.num(this, (left + 999) / 1000)));
            AppExecutors.mainDelayed(resendTicker, 1000);
        } else {
            btnResend.setEnabled(true);
            ((TextView) btnResend).setText(R.string.register_resend);
        }
    }

    private void sendCode() {
        if (busy) return;
        String name = etName.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        if (name.length() < 2) { showError(getString(R.string.register_invalid_name)); return; }
        if (!LicenseManager.isValidEmail(email)) { showError(getString(R.string.register_invalid_email)); return; }
        setBusy(true);
        String lang = Lang.current(this);
        AppExecutors.net().execute(() -> {
            try {
                LicenseManager.get(this).requestCode(name, email, lang);
                AppExecutors.main(() -> {
                    setBusy(false);
                    resendAllowedAt = System.currentTimeMillis() + 60_000;
                    showStep(2);
                });
            } catch (Exception e) {
                AppExecutors.main(() -> { setBusy(false); showError(describe(e)); });
            }
        });
    }

    private void verify() {
        if (busy) return;
        String code = etCode.getText().toString().trim();
        if (code.length() != 6) { showError(getString(R.string.register_invalid_code)); return; }
        String name = etName.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        setBusy(true);
        String lang = Lang.current(this);
        AppExecutors.net().execute(() -> {
            try {
                LicenseManager.get(this).verify(name, email, code, lang);
                AppExecutors.main(() -> {
                    setBusy(false);
                    toast(R.string.register_done);
                    startActivity(LauncherActivity.next(this));
                    finish();
                });
            } catch (Exception e) {
                AppExecutors.main(() -> { setBusy(false); showError(describe(e)); });
            }
        });
    }

    private String describe(Exception e) {
        if (e instanceof LicenseManager.ServerException) {
            switch (((LicenseManager.ServerException) e).code) {
                case "bad_code":
                case "expired":       return getString(R.string.register_invalid_code);
                case "invalid_email": return getString(R.string.register_invalid_email);
                case "rate_limited":  return getString(R.string.register_rate_limited);
                case "wrong_code":
                case "code_expired":  return getString(R.string.register_invalid_code);
                default:              return getString(R.string.register_error, ((LicenseManager.ServerException) e).code);
            }
        }
        // Show the technical reason too, so problems can be diagnosed
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return getString(R.string.network_error) + "\n(" + detail + ")";
    }

    // ── Telegram (same as the desktop: Start in the bot → 6-digit code → enter it here) ──

    private void openTelegram() {
        android.net.Uri uri = android.net.Uri.parse("https://t.me/" + com.tecush.kurdishproverbs.BuildConfig.TELEGRAM_BOT);
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException e) {
            showError(getString(R.string.register_tg_missing));
        }
    }

    private void verifyTelegram() {
        if (busy) return;
        EditText et = findViewById(R.id.etTgCode);
        String code = et.getText().toString().trim();
        if (code.length() != 6) { showError(getString(R.string.register_invalid_code)); return; }
        setBusy(true);
        String lang = Lang.current(this);
        AppExecutors.net().execute(() -> {
            try {
                LicenseManager.get(this).verifyTelegram(code, lang);
                AppExecutors.main(() -> {
                    setBusy(false);
                    toast(R.string.register_done);
                    startActivity(LauncherActivity.next(this));
                    finish();
                });
            } catch (Exception e) {
                AppExecutors.main(() -> { setBusy(false); showError(describe(e)); });
            }
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        btnSend.setEnabled(!b);
        btnVerify.setEnabled(!b);
        findViewById(R.id.btnTgVerify).setEnabled(!b);
        if (b) tvError.setVisibility(View.GONE);
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }
}
