package com.tecush.kurdishproverbs.license;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.tecush.kurdishproverbs.BuildConfig;
import com.tecush.kurdishproverbs.net.Http;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * One-time registration with name + email, confirmed by a 6-digit code sent by email.
 * Same idea as the desktop app (machine fingerprint + HMAC-signed local record),
 * talking to server/android_registration.gs (Google Apps Script).
 *
 *   request_code → server emails a code
 *   verify       → server records the user in the Google Sheet and returns
 *                  token = HMAC(secret, email|device)
 *   checkin      → once a week: last-seen date and app version in the sheet
 *
 * After registration the app works fully offline; nothing is checked at startup
 * except the local signature, so a phone without internet is never locked out.
 */
public final class LicenseManager {

    private static final String TAG = "KP-License";
    private static final String PREFS = "kp_license";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    public static final class ServerException extends Exception {
        public final String code;
        public ServerException(String code) { super(code); this.code = code; }
    }

    private static LicenseManager instance;

    public static synchronized LicenseManager get(Context ctx) {
        if (instance == null) instance = new LicenseManager(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences sp;

    private LicenseManager(Context app) {
        this.app = app;
        this.sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** False while secrets.properties still has the placeholder: registration is then skipped. */
    public static boolean isConfigured() {
        String url = BuildConfig.REGISTRATION_URL;
        return url.startsWith("https://") && !BuildConfig.HMAC_SECRET.isEmpty();
    }

    /** True when a Telegram bot is set (kp.telegramBot in secrets.properties). */
    public static boolean isTelegramConfigured() {
        return isConfigured() && !BuildConfig.TELEGRAM_BOT.isEmpty()
                && BuildConfig.TELEGRAM_SERVER_URL.startsWith("https://");
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL.matcher(email.trim()).matches();
    }

    // ── Local state ──────────────────────────────────────────────────────

    public boolean isRegistered() {
        if (!isConfigured()) return true;
        String email = sp.getString("email", "");
        String name = sp.getString("name", "");
        String token = sp.getString("token", "");
        String sig = sp.getString("sig", "");
        if (email.isEmpty() || token.isEmpty()) return false;
        String device = deviceId();
        return token.equals(hmac(email + "|" + device)) && sig.equals(hmac("local|" + email + "|" + name + "|" + device + "|" + token));
    }

    public String registeredEmail() { return sp.getString("email", ""); }
    /** "Telegram" for Telegram registrations, otherwise the email address. */
    public String registeredVia() {
        String e = registeredEmail();
        return e.startsWith("tg:") ? "Telegram" : e;
    }
    public String registeredName()  { return sp.getString("name", ""); }

    /** Stable id for this app on this phone (survives reinstall; differs between phones). */
    @SuppressLint("HardwareIds")
    public String deviceId() {
        String androidId = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null) androidId = "unknown";
        return sha256("kp|" + androidId + "|" + Build.MANUFACTURER + "|" + Build.MODEL).substring(0, 32);
    }

    // ── Server calls (run on a background thread) ────────────────────────

    public void requestCode(String name, String email, String lang) throws Exception {
        JSONObject body = base("request_code", email);
        body.put("name", name.trim());
        body.put("lang", lang);
        call(body);
    }

    /** @throws ServerException("bad_code") if the code is wrong or expired */
    public void verify(String name, String email, String code, String lang) throws Exception {
        String normEmail = email.trim().toLowerCase(Locale.ROOT);
        JSONObject body = base("verify", normEmail);
        body.put("name", name.trim());
        body.put("code", code.trim());
        body.put("lang", lang);
        JSONObject res = call(body);
        String token = res.optString("token", "");
        if (!token.equals(hmac(normEmail + "|" + deviceId()))) throw new ServerException("bad_token");
        save(normEmail, name.trim(), token);
    }

    /**
     * Telegram registration — same as the desktop program (shared spec):
     * the user taps Start in @bot, the bot sends a 6-digit code, the code is checked by the
     * DESKTOP registration script (action "telegram_verify"), which owns the bot's webhook.
     * Replies: {"status":"approved","name":…,"email":"tg:<id>"} | wrong_code | code_expired | revoked
     */
    public void verifyTelegram(String code, String lang) throws Exception {
        JSONObject body = new JSONObject();
        body.put("action", "telegram_verify");
        body.put("machine_id", deviceId());
        body.put("code", code.trim());
        body.put("version", "android-" + BuildConfig.VERSION_NAME);
        body.put("hostname", Build.MANUFACTURER + " " + Build.MODEL);
        body.put("platform", "android");
        body.put("lang", lang);
        JSONObject res = Http.postJson(BuildConfig.TELEGRAM_SERVER_URL, body);
        String status = res.optString("status", "");
        if (!"approved".equals(status)) {
            String msg = res.optString("message", "");
            throw new ServerException(status.isEmpty() ? "server_error" : (msg.isEmpty() ? status : status + ": " + msg));
        }
        String identity = res.optString("email", "");
        if (!identity.startsWith("tg:")) throw new ServerException("server_error");
        String name = res.optString("name", "Telegram");
        save(identity, name, hmac(identity + "|" + deviceId()));
    }

    private void save(String identity, String name, String token) {
        String device = deviceId();
        sp.edit()
                .putString("email", identity)
                .putString("name", name)
                .putString("token", token)
                .putString("sig", hmac("local|" + identity + "|" + name + "|" + device + "|" + token))
                .putLong("registered_at", System.currentTimeMillis())
                .apply();
    }

    /** Silent weekly "still using the app" signal. Never throws. (Telegram users: kept by the desktop script.) */
    public void checkIn(String dbVersion) {
        if (!isConfigured() || registeredEmail().isEmpty() || registeredEmail().startsWith("tg:")) return;
        try {
            JSONObject body = base("checkin", registeredEmail());
            body.put("token", sp.getString("token", ""));
            body.put("db_version", dbVersion);
            call(body);
        } catch (Exception e) {
            Log.i(TAG, "checkin skipped: " + e.getMessage());
        }
    }

    private JSONObject base(String action, String email) throws Exception {
        JSONObject o = new JSONObject();
        o.put("action", action);
        o.put("email", email.trim().toLowerCase(Locale.ROOT));
        o.put("device_id", deviceId());
        o.put("device_model", Build.MANUFACTURER + " " + Build.MODEL);
        o.put("android", Build.VERSION.RELEASE);
        o.put("app_version", BuildConfig.VERSION_NAME);
        o.put("platform", "android");
        return o;
    }

    private JSONObject call(JSONObject body) throws Exception {
        JSONObject res = Http.postJson(BuildConfig.REGISTRATION_URL, body);
        if (!res.optBoolean("ok", false)) throw new ServerException(res.optString("error", "server_error"));
        return res;
    }

    // ── Crypto helpers ───────────────────────────────────────────────────

    /** HMAC-SHA256 as lowercase hex — identical to hmacHex_() in the Apps Script. */
    static String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(BuildConfig.HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Http.hex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Http.hex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "0000000000000000000000000000000000000000";
        }
    }
}
