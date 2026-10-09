package com.tecush.kurdishproverbs.ui;

import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tecush.kurdishproverbs.BuildConfig;
import com.tecush.kurdishproverbs.R;
import com.tecush.kurdishproverbs.core.AppExecutors;
import com.tecush.kurdishproverbs.core.Cancel;
import com.tecush.kurdishproverbs.core.Lang;
import com.tecush.kurdishproverbs.core.Prefs;
import com.tecush.kurdishproverbs.data.Db;
import com.tecush.kurdishproverbs.net.ApkUpdater;
import com.tecush.kurdishproverbs.net.DbInstaller;
import com.tecush.kurdishproverbs.net.Http;
import com.tecush.kurdishproverbs.net.UpdateManifest;

import java.io.File;

/**
 * Update checks for BOTH the app and the database, driven by latest_android.json.
 *   - automatic: when the app opens (every 6 hours, and right after an app update);
 *                a new dictionary is installed silently, a new app shows a window
 *   - manual:    "Check for updates" in the menu, Settings and About
 */
public final class UpdateFlow {

    /** How often the automatic check runs (a new app version always checks right away). */
    private static final long AUTO_INTERVAL = 6L * 60 * 60 * 1000;
    private static volatile boolean dbInstallRunning;

    /**
     * Runs when the main screen opens.
     *   new dictionary → downloaded and installed silently (no question), short note when done
     *   new app        → the usual "New version available" window (Android always asks for APKs)
     */
    public static void autoCheck(BaseActivity a) {
        Prefs prefs = Prefs.get(a);
        if (!prefs.autoUpdateCheck()) return;
        long now = System.currentTimeMillis();
        boolean appChanged = prefs.getLastCheckedVersionCode() != BuildConfig.VERSION_CODE;
        if (!appChanged && now - prefs.getLastUpdateCheck() < AUTO_INTERVAL) return;
        prefs.setLastUpdateCheck(now);
        prefs.setLastCheckedVersionCode(BuildConfig.VERSION_CODE);
        final android.content.Context app = a.getApplicationContext();
        AppExecutors.net().execute(() -> {
            try {
                UpdateManifest m = UpdateManifest.fetch();
                String installed = AppExecutors.db().submit(() -> Db.get(app).version()).get();
                if (m.dbUpdateAvailable(installed)) silentDbInstall(app, a, m.db);
                if (m.appUpdateAvailable()) AppExecutors.main(() -> {
                    if (!a.isFinishing() && !a.isDestroyed()) showAppUpdate(a, m.app);
                });
            } catch (Exception ignored) {
                // offline or GitHub unreachable: try again next time
            }
        });
    }

    /** Background dictionary update. Runs on the calling (network) thread. */
    private static void silentDbInstall(android.content.Context app, BaseActivity a, UpdateManifest.DbInfo info) {
        if (dbInstallRunning) return;
        dbInstallRunning = true;
        try {
            DbInstaller.install(app, info, (phase, done, total) -> {}, null);
            AppExecutors.main(() -> {
                android.widget.Toast.makeText(app, R.string.update_db_done, android.widget.Toast.LENGTH_LONG).show();
                if (!a.isFinishing() && !a.isDestroyed()) a.onDatabaseReplaced();
            });
        } catch (Exception e) {
            android.util.Log.i("KP-Update", "dictionary update postponed: " + e.getMessage());
        } finally {
            dbInstallRunning = false;
        }
    }

    public static void manualCheck(BaseActivity a) {
        Progress p = Progress.show(a, a.getString(R.string.update_checking), null);
        AppExecutors.net().execute(() -> {
            try {
                UpdateManifest m = UpdateManifest.fetch();
                String installed = AppExecutors.db().submit(() -> Db.get(a).version()).get();
                Prefs.get(a).setLastUpdateCheck(System.currentTimeMillis());
                AppExecutors.main(() -> {
                    p.dismiss();
                    if (a.isFinishing() || a.isDestroyed()) return;
                    if (m.appUpdateAvailable()) showAppUpdate(a, m.app);
                    else if (m.dbUpdateAvailable(installed)) showDbUpdate(a, m.db);
                    else new MaterialAlertDialogBuilder(a)
                                .setTitle(R.string.check_updates)
                                .setMessage(R.string.update_none)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                });
            } catch (Exception e) {
                AppExecutors.main(() -> {
                    p.dismiss();
                    if (!a.isFinishing()) error(a, a.getString(R.string.network_error));
                });
            }
        });
    }

    // ── App update ────────────────────────────────────────────────────────

    public static void showAppUpdate(BaseActivity a, UpdateManifest.AppInfo info) {
        String notes = UpdateManifest.notes(info.notes, Lang.current(a));
        String msg = a.getString(R.string.update_app_msg, info.versionName, Lang.size(a, info.size))
                + (notes.isEmpty() ? "" : "\n\n" + notes);
        new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.update_app_title)
                .setMessage(msg)
                .setPositiveButton(R.string.update_install, (d, w) -> downloadApk(a, info))
                .setNegativeButton(R.string.later, null)
                .setNeutralButton(R.string.update_open_browser, (d, w) -> UiUtil.openUrl(a, info.apkUrl))
                .show();
    }

    private static void downloadApk(BaseActivity a, UpdateManifest.AppInfo info) {
        Cancel cancel = new Cancel();
        Progress p = Progress.show(a, a.getString(R.string.update_downloading), cancel);
        AppExecutors.net().execute(() -> {
            try {
                File apk = ApkUpdater.download(a, info, (done, total) -> AppExecutors.main(() ->
                        p.set(done, total, a.getString(R.string.setup_progress, Lang.size(a, done), Lang.size(a, total)))), cancel);
                AppExecutors.main(() -> {
                    p.dismiss();
                    if (!a.isFinishing()) install(a, apk, info);
                });
            } catch (Http.CancelledException ce) {
                AppExecutors.main(p::dismiss);
            } catch (Exception e) {
                AppExecutors.main(() -> {
                    p.dismiss();
                    if (!a.isFinishing()) errorWithBrowser(a, a.getString(R.string.update_failed, describe(a, e)), info.apkUrl);
                });
            }
        });
    }

    private static void install(BaseActivity a, File apk, UpdateManifest.AppInfo info) {
        if (!ApkUpdater.canInstall(a)) {
            new MaterialAlertDialogBuilder(a)
                    .setTitle(R.string.update_app_title)
                    .setMessage(R.string.update_permission)
                    .setPositiveButton(R.string.update_allow, (d, w) -> ApkUpdater.openInstallPermissionSettings(a))
                    .setNeutralButton(R.string.update_install_now, (d, w) -> install(a, apk, info))
                    .setNegativeButton(R.string.later, null)
                    .show();
            return;
        }
        try {
            ApkUpdater.install(a, apk);
        } catch (Exception e) {
            errorWithBrowser(a, a.getString(R.string.update_failed, describe(a, e)), info.apkUrl);
        }
    }

    // ── Database update ───────────────────────────────────────────────────

    public static void showDbUpdate(BaseActivity a, UpdateManifest.DbInfo info) {
        String notes = UpdateManifest.notes(info.notes, Lang.current(a));
        String msg = a.getString(R.string.update_db_msg, info.version, Lang.size(a, info.size))
                + (notes.isEmpty() ? "" : "\n\n" + notes);
        new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.update_db_title)
                .setMessage(msg)
                .setPositiveButton(R.string.update_db_install, (d, w) -> installDb(a, info))
                .setNegativeButton(R.string.later, null)
                .show();
    }

    public static void installDb(BaseActivity a, UpdateManifest.DbInfo info) {
        if (dbInstallRunning) { a.toast(R.string.update_downloading); return; }
        Cancel cancel = new Cancel();
        Progress p = Progress.show(a, a.getString(R.string.update_downloading), cancel);
        AppExecutors.net().execute(() -> {
            try {
                DbInstaller.install(a, info, (phase, done, total) -> AppExecutors.main(() -> {
                    if (phase == 0) {
                        p.setMessage(a.getString(R.string.update_downloading));
                        p.set(done, total, a.getString(R.string.setup_progress, Lang.size(a, done), Lang.size(a, total)));
                    } else {
                        p.setMessage(a.getString(R.string.setup_preparing));
                        p.set(done, total, "");
                    }
                }), cancel);
                AppExecutors.main(() -> {
                    p.dismiss();
                    if (a.isFinishing()) return;
                    a.toast(R.string.update_db_done);
                    a.onDatabaseReplaced();
                });
            } catch (Http.CancelledException ce) {
                AppExecutors.main(p::dismiss);
            } catch (Exception e) {
                AppExecutors.main(() -> {
                    p.dismiss();
                    if (!a.isFinishing()) error(a, a.getString(R.string.update_failed, describe(a, e)));
                });
            }
        });
    }

    // ── Errors ────────────────────────────────────────────────────────────

    public static String describe(android.content.Context c, Exception e) {
        if (e instanceof DbInstaller.StorageException)
            return c.getString(R.string.storage_error, Lang.size(c, ((DbInstaller.StorageException) e).needed));
        if (e instanceof DbInstaller.ChecksumException || e instanceof com.tecush.kurdishproverbs.net.DbCrypto.BadFileException)
            return c.getString(R.string.checksum_error);
        if (e instanceof java.io.IOException) return c.getString(R.string.network_error);
        return e.getClass().getSimpleName();
    }

    private static void error(BaseActivity a, String msg) {
        new MaterialAlertDialogBuilder(a).setMessage(msg).setPositiveButton(R.string.ok, null).show();
    }

    private static void errorWithBrowser(BaseActivity a, String msg, String url) {
        new MaterialAlertDialogBuilder(a).setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.update_open_browser, (d, w) -> UiUtil.openUrl(a, url))
                .show();
    }

    // ── Progress dialog ───────────────────────────────────────────────────

    static final class Progress {
        private final AlertDialog dialog;
        private final TextView message, detail;
        private final ProgressBar bar;

        private Progress(AlertDialog d, View v) {
            dialog = d;
            message = v.findViewById(R.id.tvProgressMessage);
            detail = v.findViewById(R.id.tvProgressDetail);
            bar = v.findViewById(R.id.progressBar);
        }

        static Progress show(BaseActivity a, String msg, Cancel cancel) {
            View v = LayoutInflater.from(a).inflate(R.layout.dialog_progress, null);
            MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(a).setView(v).setCancelable(false);
            if (cancel != null) b.setNegativeButton(R.string.cancel, (DialogInterface d, int w) -> cancel.cancel());
            AlertDialog d = b.show();
            Progress p = new Progress(d, v);
            p.message.setText(msg);
            return p;
        }

        void setMessage(String s) { message.setText(s); }

        void set(long done, long total, String text) {
            if (total > 0) {
                bar.setIndeterminate(false);
                bar.setProgress((int) (done * 1000 / total));
            } else {
                bar.setIndeterminate(true);
            }
            detail.setText(text);
        }

        void dismiss() {
            try { if (dialog.isShowing()) dialog.dismiss(); } catch (Exception ignored) {}
        }
    }
}
