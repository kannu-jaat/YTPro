package com.google.android.youtube.pro;

import android.app.Activity;
import android.app.Dialog;
import android.app.PictureInPictureParams;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Rational;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

import com.google.android.youtube.pro.webview.YTProWebView;
import com.google.android.youtube.pro.webview.YTProWebViewClient;
import com.google.android.youtube.pro.webview.YTProWebChromeClient;
import com.google.android.youtube.pro.webview.WebAppInterface;
import com.google.android.youtube.pro.webview.BinaryStreamManager;
import com.google.android.youtube.pro.receivers.MediaCommandReceiver;

public class MainActivity extends Activity {

    public boolean portrait = false;
    public boolean isPlaying = false;
    public boolean mediaSession = false;
    public boolean isPip = false;
    public boolean dL = false;

    private YTProWebView web; 
    private WebView ytHomeWeb; 
    private View dragHandle;  
    private LinearLayout rootContainer;

    private MediaCommandReceiver broadcastReceiver;
    private OnBackInvokedCallback backCallback;
    public BinaryStreamManager streamManager;
    private SharedPreferences prefs;

    private int ytHeight = 600; 
    private boolean isYtVisible = true;
    private boolean isZoomLocked = false;

    static class AudioModel {
        String name; Uri uri; long duration; long dateAdded; String durationStr;
        AudioModel(String name, Uri uri, long duration, long dateAdded) {
            this.name = name; this.uri = uri; this.duration = duration; this.dateAdded = dateAdded;
            long min = (duration / 1000) / 60; long sec = (duration / 1000) % 60;
            this.durationStr = String.format(Locale.US, "%02d:%02d", min, sec);
        }
        @Override public String toString() { return name + "  [" + durationStr + "]"; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        prefs = getSharedPreferences("YTPRO", MODE_PRIVATE);
        if (!prefs.contains("bgplay")) prefs.edit().putBoolean("bgplay", true).apply();
        
        ytHeight = prefs.getInt("yt_height", 600);
        isYtVisible = prefs.getBoolean("yt_visible", true);
        isZoomLocked = prefs.getBoolean("zoom_locked", false);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setupDynamicLayout();

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_AUDIO}, 101);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
            }
        }
        
        load(false);
    }

    private void setupDynamicLayout() {
        rootContainer = new LinearLayout(this);
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        rootContainer.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));

        ytHomeWeb = new WebView(this);
        LinearLayout.LayoutParams ytParams = new LinearLayout.LayoutParams(-1, ytHeight);
        ytHomeWeb.setLayoutParams(ytParams);
        
        // 🛠️ PINK DIVIDER FIX: Halka Pink (#ffb6c1) Rang Daal Diya
        dragHandle = new View(this);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(-1, 20); 
        dragHandle.setLayoutParams(handleParams);
        GradientDrawable handleLine = new GradientDrawable();
        handleLine.setColor(android.graphics.Color.parseColor("#ffb6c1"));
        handleLine.setCornerRadius(5f);
        dragHandle.setBackground(handleLine);

        web = new YTProWebView(this);
        web.setId(R.id.web);
        LinearLayout.LayoutParams netlifyParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        web.setLayoutParams(netlifyParams);

        rootContainer.addView(ytHomeWeb);
        rootContainer.addView(dragHandle);
        rootContainer.addView(web);
        setContentView(rootContainer);

        ytHomeWeb.setVisibility(isYtVisible ? View.VISIBLE : View.GONE);
        dragHandle.setVisibility(isYtVisible ? View.VISIBLE : View.GONE);

        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private float initialY;
            private int initialHeight;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialY = event.getRawY();
                        initialHeight = ytHomeWeb.getHeight();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float deltaY = event.getRawY() - initialY;
                        int newHeight = (int) (initialHeight + deltaY);
                        if (newHeight > 200 && newHeight < (rootContainer.getHeight() - 300)) {
                            ytHeight = newHeight;
                            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) ytHomeWeb.getLayoutParams();
                            p.height = newHeight;
                            ytHomeWeb.setLayoutParams(p);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        prefs.edit().putInt("yt_height", ytHeight).apply();
                        return true;
                }
                return false;
            }
        });
    }

    public void load(boolean dl) {
        this.dL = dl;

        if (findViewById(999999) == null) {
            LinearLayout buttonBox = new LinearLayout(this);
            buttonBox.setId(999999);
            buttonBox.setOrientation(LinearLayout.HORIZONTAL);
            
            final Button btnZoomToggle = new Button(this);
            btnZoomToggle.setText(isZoomLocked ? "🔓" : "🔒");
            btnZoomToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            btnZoomToggle.setPadding(0, 0, 0, 0);
            btnZoomToggle.setTextSize(16f);

            final Button btnYtToggle = new Button(this);
            btnYtToggle.setText(isYtVisible ? "📺" : "🌐");
            btnYtToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            btnYtToggle.setPadding(0, 0, 0, 0);
            btnYtToggle.setTextSize(16f);

            LinearLayout.LayoutParams btnMargin = new LinearLayout.LayoutParams(-2, -2);
            btnMargin.setMargins(15, 0, 15, 0);
            buttonBox.addView(btnYtToggle, btnMargin);
            buttonBox.addView(btnZoomToggle, btnMargin);

            FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(-2, -2);
            boxParams.gravity = android.view.Gravity.TOP | android.view.Gravity.RIGHT;
            boxParams.setMargins(0, 45, 10, 0);
            addContentView(buttonBox, boxParams);

            applyZoomState(isZoomLocked, btnZoomToggle);

            btnZoomToggle.setOnClickListener(v -> {
                isZoomLocked = !isZoomLocked;
                applyZoomState(isZoomLocked, btnZoomToggle);
                prefs.edit().putBoolean("zoom_locked", isZoomLocked).apply();
            });

            btnYtToggle.setOnClickListener(v -> {
                isYtVisible = !isYtVisible;
                ytHomeWeb.setVisibility(isYtVisible ? View.VISIBLE : View.GONE);
                dragHandle.setVisibility(isYtVisible ? View.VISIBLE : View.GONE);
                btnYtToggle.setText(isYtVisible ? "📺" : "🌐");
                prefs.edit().putBoolean("yt_visible", isYtVisible).apply();
            });
        }

        // --- 🛠️ MOBILE MODE FIX: Desktop UserAgent Hata Diya (Ekdum Clean Mobile UI Khulega) ---
        ytHomeWeb.getSettings().setJavaScriptEnabled(true);
        ytHomeWeb.getSettings().setDomStorageEnabled(true);
        ytHomeWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectDJButtonsSystem();
            }

            // 🛠️ CONFIRMATION POPUP ON CLICK: Galti se video click hone par hijacking popup
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.contains("watch?v=")) {
                    Uri uri = Uri.parse(url);
                    String videoId = uri.getQueryParameter("v");
                    if (videoId != null) {
                        showDeckConfirmationDialog(videoId, url);
                        return true; // Click ko block karke apna popup dikhayenge
                    }
                }
                return false;
            }
        });
        ytHomeWeb.loadUrl("https://m.youtube.com");

        // --- NETLIFY WEB SETTINGS ---
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setUserAgentString("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false); 
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(web, true);
        }

        web.addJavascriptInterface(new WebAppInterface(this, web), "Android");
        
        // ⚡ FIXED BRIDGE: Static query selectors badal diye hmesha correct boxes pakadne ke liye
        ytHomeWeb.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void sendToDeck(String deck, String videoId) {
                runOnUiThread(() -> {
                    String fullUrl = "https://www.youtube.com/watch?v=" + videoId;
                    if ("left".equals(deck)) {
                        web.evaluateJavascript(
                            "(function() { " +
                            "  var inputs = document.querySelectorAll('input'); " +
                            "  for(var i=0; i<inputs.length; i++) { " +
                            "    if(inputs[i].placeholder && (inputs[i].placeholder.toLowerCase().includes('left') || inputs[i].parentNode.innerText.toLowerCase().includes('left'))) { " +
                            "      inputs[i].value = '" + fullUrl + "'; " +
                            "      inputs[i].dispatchEvent(new Event('input', { bubbles: true })); " +
                            "      break; " +
                            "    } " +
                            "  } " +
                            "})();", null);
                    } else {
                        web.evaluateJavascript(
                            "(function() { " +
                            "  var inputs = document.querySelectorAll('input'); " +
                            "  for(var i=0; i<inputs.length; i++) { " +
                            "    if(inputs[i].placeholder && (inputs[i].placeholder.toLowerCase().includes('right') || inputs[i].parentNode.innerText.toLowerCase().includes('right'))) { " +
                            "      inputs[i].value = '" + fullUrl + "'; " +
                            "      inputs[i].dispatchEvent(new Event('input', { bubbles: true })); " +
                            "      break; " +
                            "    } " +
                            "  } " +
                            "})();", null);
                    }
                });
            }
        }, "DJBridge");

        web.setWebChromeClient(new YTProWebChromeClient(this, web));
        web.setWebViewClient(new YTProWebViewClient(this, web));
        web.loadUrl("https://kannujaat.netlify.app/");

        setupReceiver();
        setupBackNavigation();
        streamManager = new BinaryStreamManager(web, this);

        Intent serviceIntent = new Intent(this, ForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    // 🛠️ ASALI POPUP JADOOR: Thumbnail click hone par aane wala Pink Master Dialogue
    private void showDeckConfirmationDialog(String videoId, String originalUrl) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(android.graphics.Color.parseColor("#1A1A1A"));
        bg.setCornerRadius(25f);
        bg.setStroke(3, android.graphics.Color.parseColor("#ffb6c1")); // Matching Pink Border
        mainLayout.setBackground(bg);
        mainLayout.setPadding(50, 50, 50, 50);

        TextView titleTv = new TextView(this);
        titleTv.setText("🎛️ DJ LOAD CONTROLLER");
        titleTv.setTextColor(android.graphics.Color.WHITE);
        titleTv.setTextSize(16f);
        titleTv.setGravity(android.view.Gravity.CENTER);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        mainLayout.addView(titleTv);

        LinearLayout btnLayout = new LinearLayout(this);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setPadding(0, 30, 0, 20);

        Button btnLeft = new Button(this);
        btnLeft.setText("🎧 LEFT");
        btnLeft.setTextColor(android.graphics.Color.BLACK);
        GradientDrawable bL = new GradientDrawable(); bL.setColor(android.graphics.Color.parseColor("#34d399")); bL.setCornerRadius(12f);
        btnLeft.setBackground(bL);

        Button btnRight = new Button(this);
        btnRight.setText("🎛️ RIGHT");
        btnRight.setTextColor(android.graphics.Color.BLACK);
        GradientDrawable bR = new GradientDrawable(); bR.setColor(android.graphics.Color.parseColor("#22d3ee")); bR.setCornerRadius(12f);
        btnRight.setBackground(bR);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1.0f);
        p.setMargins(8, 0, 8, 0);
        btnLayout.addView(btnLeft, p);
        btnLayout.addView(btnRight, p);
        mainLayout.addView(btnLayout);

        Button btnWatch = new Button(this);
        btnWatch.setText("▶️ Play inside YouTube");
        btnWatch.setTextColor(android.graphics.Color.WHITE);
        btnWatch.setTextSize(12f);
        GradientDrawable bW = new GradientDrawable(); bW.setColor(android.graphics.Color.parseColor("#333333")); bW.setCornerRadius(12f);
        btnWatch.setBackground(bW);
        mainLayout.addView(btnWatch, new LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(mainLayout);

        btnLeft.setOnClickListener(v -> {
            // Direct Bridge bypass to left input box
            ytHomeWeb.evaluateJavascript("window.DJBridge.sendToDeck('left', '" + videoId + "');", null);
            dialog.dismiss();
        });

        btnRight.setOnClickListener(v -> {
            // Direct Bridge bypass to right input box
            ytHomeWeb.evaluateJavascript("window.DJBridge.sendToDeck('right', '" + videoId + "');", null);
            dialog.dismiss();
        });

        btnWatch.setOnClickListener(v -> {
            dialog.dismiss();
            ytHomeWeb.setWebViewClient(new WebViewClient()); // Temp bypass to load raw video
            ytHomeWeb.loadUrl(originalUrl);
            ytHomeWeb.setWebViewClient(new WebViewClient() { // Restore hijack system
                @Override public void onPageFinished(WebView view, String url) { injectDJButtonsSystem(); }
                @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    if (url.contains("watch?v=")) {
                        Uri uri = Uri.parse(url); String vId = uri.getQueryParameter("v");
                        if (vId != null) { showDeckConfirmationDialog(vId, url); return true; }
                    }
                    return false;
                }
            });
        });

        dialog.show();
    }

    private void applyZoomState(boolean lock, Button btn) {
        if (lock) {
            web.getSettings().setSupportZoom(false);
            web.getSettings().setBuiltInZoomControls(false);
            web.evaluateJavascript("var meta = document.querySelector('meta[name=viewport]'); if(!meta){ meta = document.createElement('meta'); meta.name='viewport'; document.head.appendChild(meta); } meta.setAttribute('content', 'width=1024, user-scalable=no');", null);
            btn.setText("🔓");
        } else {
            web.getSettings().setSupportZoom(true);
            web.getSettings().setBuiltInZoomControls(true);
            web.evaluateJavascript("var meta = document.querySelector('meta[name=viewport]'); if(meta){ meta.setAttribute('content', 'width=1024, user-scalable=yes'); }", null);
            btn.setText("🔒");
        }
    }

    // ⚡ FIXED INJECTION SCRIPT: Mobile mode ke elements ke sath sync kar diya
    private void injectDJButtonsSystem() {
        String js = "setInterval(function() { " +
                "  var videos = document.querySelectorAll('a[href*=\"/watch?v=\"]'); " +
                "  videos.forEach(function(v) { " +
                "    if(v.getAttribute('dj-hooked')) return; " +
                "    v.setAttribute('dj-hooked', 'true'); " +
                "    var hrefText = v.href; " +
                "    if(!hrefText.contains('watch?v=')) return; " +
                "    var parts = hrefText.split('v='); if(parts.length < 2) return; " +
                "    var vId = parts[1].split('&')[0]; " +
                "    var btnContainer = document.createElement('div'); " +
                "    btnContainer.style = 'display:flex; gap:15px; padding:8px; background:#181818; justify-content:center; width:100%; margin-top:2px; margin-bottom:5px; border-radius:6px;'; " +
                "    var bL = document.createElement('button'); bL.innerText='🎧 Load Left'; bL.style='background:#34d399; color:#000; border:none; padding:6px 14px; font-size:12px; font-weight:bold; border-radius:6px; flex:1;'; " +
                "    bL.onclick = function(e) { e.preventDefault(); e.stopPropagation(); window.DJBridge.sendToDeck('left', vId); }; " +
                "    var bR = document.createElement('button'); bR.innerText='🎛️ Load Right'; bR.style='background:#22d3ee; color:#000; border:none; padding:6px 14px; font-size:12px; font-weight:bold; border-radius:6px; flex:1;'; " +
                "    bR.onclick = function(e) { e.preventDefault(); e.stopPropagation(); window.DJBridge.sendToDeck('right', vId); }; " +
                "    btnContainer.appendChild(bL); btnContainer.appendChild(bR); " +
                "    v.parentNode.insertBefore(btnContainer, v.nextSibling); " +
                "  }); " +
                "}, 2000);";
        ytHomeWeb.evaluateJavascript(js, null);
    }

    private void setupReceiver() {
        broadcastReceiver = new MediaCommandReceiver(web);
        IntentFilter filter = new IntentFilter();
        filter.addAction("LEFT_TOGGLE"); filter.addAction("RIGHT_TOGGLE"); filter.addAction("XFADER_LEFT"); filter.addAction("XFADER_RIGHT");
        if (Build.VERSION.SDK_INT >= 34 && getApplicationInfo().targetSdkVersion >= 34) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(broadcastReceiver, filter);
        }
    }

    private void setupBackNavigation() {
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, new OnBackInvokedCallback() {
                @Override public void onBackInvoked() { handleBackPress(); }
            });
        }
    }

    private void handleBackPress() {
        if (isYtVisible && ytHomeWeb.canGoBack()) {
            ytHomeWeb.goBack();
        } else if (web.canGoBack()) { 
            web.goBack(); 
        } else { 
            showExitDialog(); 
        }
    }

    private void showExitDialog() {
        final Dialog dialog = new Dialog(this); dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        LinearLayout mainLayout = new LinearLayout(this); mainLayout.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(android.graphics.Color.parseColor("#1A1A1A")); bg.setCornerRadius(30f); bg.setStroke(3, android.graphics.Color.parseColor("#34d399"));
        mainLayout.setBackground(bg); mainLayout.setPadding(60, 60, 60, 60);
        TextView titleTv = new TextView(this); titleTv.setText("Close KK Mixer?"); titleTv.setTextColor(android.graphics.Color.WHITE); titleTv.setTextSize(20f); titleTv.setGravity(android.view.Gravity.CENTER); titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView subTv = new TextView(this); subTv.setText("Keep playing music in background or exit completely?"); subTv.setTextColor(android.graphics.Color.LTGRAY); subTv.setTextSize(14f); subTv.setGravity(android.view.Gravity.CENTER); subTv.setPadding(0, 20, 0, 40);
        LinearLayout btnLayout = new LinearLayout(this); btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        Button btnMinimize = new Button(this); btnMinimize.setText("MINIMIZE"); btnMinimize.setTextColor(android.graphics.Color.WHITE); GradientDrawable btnBgMin = new GradientDrawable(); btnBgMin.setColor(android.graphics.Color.parseColor("#333333")); btnBgMin.setCornerRadius(15f); btnMinimize.setBackground(btnBgMin);
        Button btnExit = new Button(this); btnExit.setText("EXIT"); btnExit.setTextColor(android.graphics.Color.BLACK); GradientDrawable btnBgExt = new GradientDrawable(); btnBgExt.setColor(android.graphics.Color.parseColor("#34d399")); btnBgExt.setCornerRadius(15f); btnExit.setBackground(btnBgExt);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1.0f); params.setMargins(10, 0, 10, 0);
        btnLayout.addView(btnMinimize, params); btnLayout.addView(btnExit, params);
        mainLayout.addView(titleTv); mainLayout.addView(subTv); mainLayout.addView(btnLayout); dialog.setContentView(mainLayout);
        btnMinimize.setOnClickListener(v -> { dialog.dismiss(); moveTaskToBack(true); });
        btnExit.setOnClickListener(v -> { dialog.dismiss(); finish(); });
        dialog.show();
    }

    @Override public void onBackPressed() { handleBackPress(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (requestCode == 101) { web.loadUrl("https://kannujaat.netlify.app/"); } }
    @Override public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) { web.evaluateJavascript(isInPictureInPictureMode ? "PIPlayer();" : "removePIP();", null); isPip = isInPictureInPictureMode; }
    @Override protected void onUserLeaveHint() { super.onUserLeaveHint(); if (Build.VERSION.SDK_INT >= 26 && web.getUrl() != null && web.getUrl().contains("watch") && isPlaying) { try { isPip = true; enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(portrait ? 9 : 16, portrait ? 16 : 9)).build()); } catch (IllegalStateException e) {} } }
    @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }
    @Override public void onDestroy() { super.onDestroy(); stopService(new Intent(getApplicationContext(), ForegroundService.class)); if (broadcastReceiver != null) unregisterReceiver(broadcastReceiver); if (streamManager != null) streamManager.cleanup(); }
}
