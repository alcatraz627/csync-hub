package com.csync.hub;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Signing in to Instagram so the Pi can read posts that need a login.
 *
 * Instagram's own login page opens here. Once it hands back a session, its cookies for
 * instagram.com are sent to the Pi, which keeps them owner-only, and the page closes.
 * Nothing else is read from the page and no script is injected into it.
 */
public final class InstagramSignIn extends Activity {
    private static final String LOGIN = "https://www.instagram.com/accounts/login/";
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean sending;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }

    @Override protected void onCreate(Bundle b) {
        Appearance.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_instagram_sign_in);
        Appearance.edgeToEdge(this, null);
        Kit.pageTop(findViewById(R.id.ig_top), this::finish,
            new Kit.Crumb(R.drawable.csi_link, "Sign in to Instagram", null));
        WebView web = findViewById(R.id.ig_web);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) { check(); }
        });
        web.loadUrl(LOGIN);
    }

    /** Once Instagram has set a session, hand it to the Pi. */
    private void check() {
        String cookies = CookieManager.getInstance().getCookie("https://www.instagram.com");
        if (sending || cookies == null || !cookies.contains("sessionid=")) return;
        sending = true;
        StringBuilder file = new StringBuilder();
        for (String pair : cookies.split(";")) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length != 2 || kv[0].isEmpty()) continue;
            file.append(".instagram.com\tTRUE\t/\tTRUE\t0\t").append(kv[0]).append('\t').append(kv[1]).append('\n');
        }
        String host = Prefs.assistIp(this), token = Prefs.token(this);
        new Thread(() -> {
            String problem = null;
            try {
                new MediaClient(host, token).put("/v1/instagram/session", new JSONObject().put("cookies", file.toString()));
            } catch (Exception failed) {
                problem = failed.getMessage() == null ? "The Pi did not answer" : failed.getMessage();
            }
            String failure = problem;
            main.post(() -> {
                if (failure != null) {
                    sending = false;
                    Toast.makeText(this, "Signed in, but the Pi did not keep it. " + failure, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, "Signed in to Instagram", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        }, "instagram-session").start();
    }
}
