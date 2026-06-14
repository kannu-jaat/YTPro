package com.google.android.youtube.pro;

import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
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
import android.os.Environment;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
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
    private YTProWebView ytHomeWeb; 
    
    private FrameLayout rootContainer;
    private FrameLayout ytWrapper;
    private LinearLayout ytHeader;
    
    // 🎵 OFFLINE PLAYER VARIABLES
    private LinearLayout offlineContainer;
    private LinearLayout offlineListLayout;
    private boolean isOfflineMode = false;

    private MediaCommandReceiver broadcastReceiver;
    private OnBackInvokedCallback backCallback;
    public BinaryStreamManager streamManager;
    private SharedPreferences prefs;

    private YTMediaSessionManager ytMediaSessionManager;

    private boolean isYtVisible = true;
    private boolean isZoomLocked = true; 

    private boolean isFullscreen = false;
    private int preFullW, preFullH;
    private float preFullX, preFullY;

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
        
        isYtVisible = prefs.getBoolean("yt_visible", true);
        isZoomLocked = prefs.getBoolean("zoom_locked", true); 

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        setupYTSessionManager();
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

    private void setupYTSessionManager() {
        ytMediaSessionManager = new YTMediaSessionManager(this, new YTMediaSessionManager.YTActionCallback() {
            @Override public void onPlay() { if(ytHomeWeb != null) ytHomeWeb.evaluateJavascript("var v = document.querySelector('video'); if(v) v.play();", null); }
            @Override public void onPause() { if(ytHomeWeb != null) ytHomeWeb.evaluateJavascript("var v = document.querySelector('video'); if(v) v.pause();", null); }
            @Override public void onNext() {
                if(ytHomeWeb != null) {
                    String js = "var nextBtn = document.querySelector('.ytp-next-button, button[aria-label=\"Next video\"], a.ytp-next-button'); " +
                                "if(nextBtn) { nextBtn.click(); } else { var nextVid = document.querySelector('ytm-video-with-context-renderer a, ytm-compact-video-renderer a'); if(nextVid) nextVid.click(); }";
                    ytHomeWeb.evaluateJavascript(js, null);
                }
            }
            @Override public void onPrev() {
                if(ytHomeWeb != null) {
                    String js = "var prevBtn = document.querySelector('.ytp-prev-button, button[aria-label=\"Previous video\"], a.ytp-prev-button'); var v = document.querySelector('video'); " +
                                "if(prevBtn && !prevBtn.disabled) { prevBtn.click(); } else if(v && v.currentTime > 5) { v.currentTime = 0; } else { window.history.back(); }";
                    ytHomeWeb.evaluateJavascript(js, null);
                }
            }
            @Override public void onClose() { if(ytHomeWeb != null) ytHomeWeb.evaluateJavascript("var v = document.querySelector('video'); if(v) v.pause();", null); }
        });
    }

    private void setupDynamicLayout() {
        rootContainer = new FrameLayout(this);
        rootContainer.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        web = new YTProWebView(this);
        web.setId(R.id.web);
        rootContainer.addView(web, new FrameLayout.LayoutParams(-1, -1));

        ytWrapper = new FrameLayout(this);
        
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int savedW = prefs.getInt("yt_w", (int)(w * 0.90));
        int savedH = prefs.getInt("yt_h", (int)(h * 0.40));
        float savedX = prefs.getFloat("yt_x", -1f);
        float savedY = prefs.getFloat("yt_y", -1f);

        FrameLayout.LayoutParams wrapParams = new FrameLayout.LayoutParams(savedW, savedH);
        if (savedX != -1f && savedY != -1f) {
            wrapParams.leftMargin = (int) savedX; wrapParams.topMargin = (int) savedY;
            wrapParams.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        } else {
            wrapParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL; wrapParams.topMargin = 150;
        }
        ytWrapper.setLayoutParams(wrapParams);
        
        GradientDrawable bg = new GradientDrawable(); bg.setColor(android.graphics.Color.parseColor("#121212"));
        bg.setCornerRadius(25f); bg.setStroke(4, android.graphics.Color.parseColor("#34d399")); 
        ytWrapper.setBackground(bg); ytWrapper.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) ytWrapper.setElevation(30f);

        LinearLayout innerVertical = new LinearLayout(this);
        innerVertical.setOrientation(LinearLayout.VERTICAL);

        ytHeader = new LinearLayout(this); ytHeader.setOrientation(LinearLayout.HORIZONTAL);
        ytHeader.setBackgroundColor(android.graphics.Color.parseColor("#1e293b"));
        ytHeader.setPadding(30, 10, 20, 10); ytHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        TextView headerTitle = new TextView(this); headerTitle.setText("🖐️ DRAG YOUTUBE");
        headerTitle.setTextColor(android.graphics.Color.WHITE); headerTitle.setTextSize(12f); headerTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        ytHeader.addView(headerTitle, new LinearLayout.LayoutParams(0, -2, 1.0f));

        // 🎵 OFFLINE TOGGLE BUTTON
        Button btnOffline = new Button(this); btnOffline.setText("🎵");
        btnOffline.setBackgroundColor(android.graphics.Color.TRANSPARENT); btnOffline.setTextColor(android.graphics.Color.WHITE); btnOffline.setPadding(0, 0, 0, 0);
        ytHeader.addView(btnOffline, new LinearLayout.LayoutParams(100, -1));

        Button btnRefresh = new Button(this); btnRefresh.setText("🔄");
        btnRefresh.setBackgroundColor(android.graphics.Color.TRANSPARENT); btnRefresh.setTextColor(android.graphics.Color.WHITE); btnRefresh.setPadding(0, 0, 0, 0);
        btnRefresh.setOnClickListener(v -> { if(ytHomeWeb != null) ytHomeWeb.evaluateJavascript("window.location.reload(true);", null); });
        ytHeader.addView(btnRefresh, new LinearLayout.LayoutParams(100, -1));

        Button btnFullscreen = new Button(this); btnFullscreen.setText("🔲");
        btnFullscreen.setBackgroundColor(android.graphics.Color.TRANSPARENT); btnFullscreen.setTextColor(android.graphics.Color.WHITE); btnFullscreen.setPadding(0, 0, 0, 0);
        ytHeader.addView(btnFullscreen, new LinearLayout.LayoutParams(100, -1));

        // 🖼️ CONTENT FRAME (Holds both YouTube and Offline Player)
        FrameLayout contentFrame = new FrameLayout(this);
        contentFrame.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));

        ytHomeWeb = new YTProWebView(this);
        ytHomeWeb.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        // 🛠️ LITE OFFLINE PLAYER UI
        offlineContainer = new LinearLayout(this);
        offlineContainer.setOrientation(LinearLayout.VERTICAL);
        offlineContainer.setBackgroundColor(android.graphics.Color.parseColor("#121212"));
        offlineContainer.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        offlineContainer.setVisibility(View.GONE);

        TextView offlineTitle = new TextView(this);
        offlineTitle.setText("💿 DJ OFFLINE CRATES"); offlineTitle.setTextColor(android.graphics.Color.parseColor("#34d399"));
        offlineTitle.setTextSize(14f); offlineTitle.setGravity(android.view.Gravity.CENTER); offlineTitle.setPadding(0, 20, 0, 20);
        offlineTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        offlineContainer.addView(offlineTitle);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        offlineListLayout = new LinearLayout(this);
        offlineListLayout.setOrientation(LinearLayout.VERTICAL);
        offlineListLayout.setPadding(15, 0, 15, 20);
        scroll.addView(offlineListLayout);
        offlineContainer.addView(scroll);

        contentFrame.addView(ytHomeWeb);
        contentFrame.addView(offlineContainer);

        innerVertical.addView(ytHeader);
        innerVertical.addView(contentFrame);
        ytWrapper.addView(innerVertical, new FrameLayout.LayoutParams(-1, -1));

        // Pink Resizers
        View rightHandle = new View(this); rightHandle.setBackgroundColor(android.graphics.Color.parseColor("#ff69b4")); 
        FrameLayout.LayoutParams rParams = new FrameLayout.LayoutParams(15, -1); rParams.gravity = android.view.Gravity.RIGHT;
        ytWrapper.addView(rightHandle, rParams);

        View bottomHandle = new View(this); bottomHandle.setBackgroundColor(android.graphics.Color.parseColor("#ff69b4")); 
        FrameLayout.LayoutParams bParams = new FrameLayout.LayoutParams(-1, 15); bParams.gravity = android.view.Gravity.BOTTOM;
        ytWrapper.addView(bottomHandle, bParams);

        View cornerHandle = new View(this); cornerHandle.setBackgroundColor(android.graphics.Color.parseColor("#ff69b4"));
        FrameLayout.LayoutParams cParams = new FrameLayout.LayoutParams(40, 40); cParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT;
        ytWrapper.addView(cornerHandle, cParams);

        rootContainer.addView(ytWrapper);
        setContentView(rootContainer);

        ytWrapper.setVisibility(isYtVisible ? View.VISIBLE : View.GONE);

        // 🎵 OFFLINE TOGGLE LOGIC
        btnOffline.setOnClickListener(v -> {
            isOfflineMode = !isOfflineMode;
            if (isOfflineMode) {
                btnOffline.setText("📺"); headerTitle.setText("💿 LOCAL TRACKS");
                ytHomeWeb.setVisibility(View.GONE); offlineContainer.setVisibility(View.VISIBLE);
                loadOfflineTracksLite();
            } else {
                btnOffline.setText("🎵"); headerTitle.setText("🖐️ DRAG YOUTUBE");
                offlineContainer.setVisibility(View.GONE); ytHomeWeb.setVisibility(View.VISIBLE);
            }
        });

        // 🔲 FULLSCREEN LOGIC (Centered & Smooth)
        btnFullscreen.setOnClickListener(v -> {
            if (!isFullscreen) {
                preFullW = ytWrapper.getWidth(); preFullH = ytWrapper.getHeight();
                preFullX = ytWrapper.getX(); preFullY = ytWrapper.getY();
                int pad = 4; int newWidth = rootContainer.getWidth() - (pad * 2); int newHeight = rootContainer.getHeight() - (pad * 2);
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) ytWrapper.getLayoutParams();
                p.width = newWidth; p.height = newHeight; p.leftMargin = 0; p.topMargin = 0;
                ytWrapper.setLayoutParams(p);
                ytWrapper.animate().x(pad).y(pad).setDuration(200).start();
                btnFullscreen.setText("🔽"); rightHandle.setVisibility(View.GONE); bottomHandle.setVisibility(View.GONE); cornerHandle.setVisibility(View.GONE);
                isFullscreen = true;
            } else {
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) ytWrapper.getLayoutParams();
                p.width = preFullW; p.height = preFullH;
                ytWrapper.setLayoutParams(p);
                ytWrapper.animate().x(preFullX).y(preFullY).setDuration(200).start();
                btnFullscreen.setText("🔲"); rightHandle.setVisibility(View.VISIBLE); bottomHandle.setVisibility(View.VISIBLE); cornerHandle.setVisibility(View.VISIBLE);
                isFullscreen = false;
            }
        });

        ytHeader.setOnTouchListener(new View.OnTouchListener() {
            float dX, dY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if(isFullscreen) return false; 
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN: dX = ytWrapper.getX() - event.getRawX(); dY = ytWrapper.getY() - event.getRawY(); return true;
                    case MotionEvent.ACTION_MOVE: ytWrapper.animate().x(event.getRawX() + dX).y(event.getRawY() + dY).setDuration(0).start(); return true;
                    case MotionEvent.ACTION_UP: prefs.edit().putFloat("yt_x", ytWrapper.getX()).putFloat("yt_y", ytWrapper.getY()).apply(); return true;
                } return false;
            }
        });

        View.OnTouchListener resizeListener = new View.OnTouchListener() {
            float initialX, initialY; int initialWidth, initialHeight;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch(event.getAction()) {
                    case MotionEvent.ACTION_DOWN: initialX = event.getRawX(); initialY = event.getRawY(); initialWidth = ytWrapper.getWidth(); initialHeight = ytWrapper.getHeight(); return true;
                    case MotionEvent.ACTION_MOVE:
                        int newWidth = initialWidth; int newHeight = initialHeight;
                        if (view == rightHandle || view == cornerHandle) newWidth = initialWidth + (int)(event.getRawX() - initialX);
                        if (view == bottomHandle || view == cornerHandle) newHeight = initialHeight + (int)(event.getRawY() - initialY);
                        if(newWidth < 400) newWidth = 400; if(newHeight < 300) newHeight = 300;
                        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) ytWrapper.getLayoutParams(); p.width = newWidth; p.height = newHeight; ytWrapper.setLayoutParams(p); return true;
                    case MotionEvent.ACTION_UP: prefs.edit().putInt("yt_w", ytWrapper.getWidth()).putInt("yt_h", ytWrapper.getHeight()).apply(); return true;
                } return false;
            }
        };

        rightHandle.setOnTouchListener(resizeListener); bottomHandle.setOnTouchListener(resizeListener); cornerHandle.setOnTouchListener(resizeListener);
    }

    // 💿 LITE OFFLINE TRACK LOADER (Direct from Phone Storage)
    private void loadOfflineTracksLite() {
        offlineListLayout.removeAllViews();
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA };
        String sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC"; 
        
        try (Cursor cursor = getContentResolver().query(collection, projection, null, null, sortOrder)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                int count = 0;
                do {
                    String name = cursor.getString(nameCol);
                    String path = cursor.getString(dataCol);
                    if (name != null && (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a"))) {
                        
                        // Create Lite UI Item for each track
                        LinearLayout itemBox = new LinearLayout(this); itemBox.setOrientation(LinearLayout.VERTICAL);
                        GradientDrawable boxBg = new GradientDrawable(); boxBg.setColor(android.graphics.Color.parseColor("#1E1E1E")); boxBg.setCornerRadius(15f);
                        itemBox.setBackground(boxBg); itemBox.setPadding(20, 20, 20, 20);
                        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(-1, -2); boxParams.setMargins(0, 0, 0, 15);
                        itemBox.setLayoutParams(boxParams);

                        TextView tvName = new TextView(this); tvName.setText(name); tvName.setTextColor(android.graphics.Color.WHITE);
                        tvName.setTextSize(14f); tvName.setSingleLine(true); tvName.setPadding(0, 0, 0, 15);

                        LinearLayout btnRow = new LinearLayout(this); btnRow.setOrientation(LinearLayout.HORIZONTAL);
                        
                        Button btnL = new Button(this); btnL.setText("🎧 L"); btnL.setTextSize(12f); btnL.setTextColor(android.graphics.Color.BLACK);
                        GradientDrawable bgL = new GradientDrawable(); bgL.setColor(android.graphics.Color.parseColor("#34d399")); bgL.setCornerRadius(8f); btnL.setBackground(bgL);
                        
                        Button btnR = new Button(this); btnR.setText("🎛️ R"); btnR.setTextSize(12f); btnR.setTextColor(android.graphics.Color.BLACK);
                        GradientDrawable bgR = new GradientDrawable(); bgR.setColor(android.graphics.Color.parseColor("#22d3ee")); bgR.setCornerRadius(8f); btnR.setBackground(bgR);

                        LinearLayout.LayoutParams bParams = new LinearLayout.LayoutParams(0, 80, 1.0f); bParams.setMargins(5, 0, 5, 0);
                        btnRow.addView(btnL, bParams); btnRow.addView(btnR, bParams);

                        itemBox.addView(tvName); itemBox.addView(btnRow);
                        offlineListLayout.addView(itemBox);

                        // Load to Netlify File Input Logic (Simulated Path passing)
                        btnL.setOnClickListener(v -> Toast.makeText(MainActivity.this, "Use Main Add Track for Local Files", Toast.LENGTH_SHORT).show());
                        btnR.setOnClickListener(v -> Toast.makeText(MainActivity.this, "Use Main Add Track for Local Files", Toast.LENGTH_SHORT).show());
                        
                        count++;
                        if(count > 50) break; // Limit to 50 for lite performance
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {}
    }

    public void load(boolean dl) {
        this.dL = dl;

        if (findViewById(999999) == null) {
            LinearLayout buttonBox = new LinearLayout(this); buttonBox.setId(999999); buttonBox.setOrientation(LinearLayout.HORIZONTAL);
            final Button btnZoomToggle = new Button(this); btnZoomToggle.setText(isZoomLocked ? "🔓" : "🔒"); btnZoomToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT); btnZoomToggle.setPadding(0, 0, 0, 0); btnZoomToggle.setTextSize(16f);
            final Button btnYtToggle = new Button(this); btnYtToggle.setText(isYtVisible ? "📺" : "🌐"); btnYtToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT); btnYtToggle.setPadding(0, 0, 0, 0); btnYtToggle.setTextSize(16f);
            LinearLayout.LayoutParams btnMargin = new LinearLayout.LayoutParams(-2, -2); btnMargin.setMargins(15, 0, 15, 0);
            buttonBox.addView(btnYtToggle, btnMargin); buttonBox.addView(btnZoomToggle, btnMargin);
            FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(-2, -2); boxParams.gravity = android.view.Gravity.TOP | android.view.Gravity.RIGHT; boxParams.setMargins(0, 45, 10, 0);
            addContentView(buttonBox, boxParams);

            applyZoomState(isZoomLocked, btnZoomToggle);

            btnZoomToggle.setOnClickListener(v -> { isZoomLocked = !isZoomLocked; applyZoomState(isZoomLocked, btnZoomToggle); prefs.edit().putBoolean("zoom_locked", isZoomLocked).apply(); });
            btnYtToggle.setOnClickListener(v -> { isYtVisible = !isYtVisible; ytWrapper.setVisibility(isYtVisible ? View.VISIBLE : View.GONE); btnYtToggle.setText(isYtVisible ? "📺" : "🌐"); prefs.edit().putBoolean("yt_visible", isYtVisible).apply(); });
        }

        ytHomeWeb.getSettings().setJavaScriptEnabled(true); ytHomeWeb.getSettings().setDomStorageEnabled(true); ytHomeWeb.getSettings().setDatabaseEnabled(true); ytHomeWeb.getSettings().setMediaPlaybackRequiresUserGesture(false); ytHomeWeb.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        ytHomeWeb.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36");
        
        ytHomeWeb.setWebViewClient(new YTProWebViewClient(this, ytHomeWeb) {
            @Override public void onPageFinished(WebView view, String url) { super.onPageFinished(view, url); injectDJButtonsSystem(); }
        });
        ytHomeWeb.setWebChromeClient(new YTProWebChromeClient(this, ytHomeWeb)); ytHomeWeb.loadUrl("https://m.youtube.com");

        web.getSettings().setJavaScriptEnabled(true); web.getSettings().setUserAgentString("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"); web.getSettings().setUseWideViewPort(true); web.getSettings().setLoadWithOverviewMode(true); web.getSettings().setAllowFileAccess(true); web.getSettings().setAllowContentAccess(true); web.getSettings().setDomStorageEnabled(true); web.getSettings().setDatabaseEnabled(true); web.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT); web.getSettings().setMediaPlaybackRequiresUserGesture(false); web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        CookieManager cookieManager = CookieManager.getInstance(); cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { cookieManager.setAcceptThirdPartyCookies(web, true); cookieManager.setAcceptThirdPartyCookies(ytHomeWeb, true); }

        web.addJavascriptInterface(new WebAppInterface(this, web), "Android");
        
        ytHomeWeb.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void sendToDeck(String deck, String videoId) {
                runOnUiThread(() -> {
                    String fullUrl = "https://www.youtube.com/watch?v=" + videoId; String inputId = "left".equals(deck) ? "leftUrl" : "rightUrl";
                    web.evaluateJavascript("var el = document.getElementById('" + inputId + "'); if(el) { el.value = '" + fullUrl + "'; el.dispatchEvent(new Event('input', { bubbles: true })); }", null);
                });
            }

            @android.webkit.JavascriptInterface
            public void showPopup(String videoId, String url) { runOnUiThread(() -> showDeckConfirmationDialog(videoId, url)); }

            @android.webkit.JavascriptInterface
            public void updateYTMedia(boolean isPlaying, String title, String artist) {
                runOnUiThread(() -> { if (ytMediaSessionManager != null) ytMediaSessionManager.updateNotification(isPlaying, title, artist, null); });
            }
            
            // ⬇️ NAYA NATIVE DOWNLOAD MANAGER BRIDGE (FAST & STABLE)
            @android.webkit.JavascriptInterface
            public void downloadNativeMP3(String videoId, String title) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Starting Fast Download: " + title, Toast.LENGTH_SHORT).show();
                    try {
                        String cleanTitle = title.replaceAll("[^a-zA-Z0-9 ]", " ").trim();
                        // ⚡ FAST STABLE API BYPASS URL
                        String downloadUrl = "https://www.yt-download.org/api/button/mp3/" + videoId;
                        
                        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
                        request.setTitle("DJ Track: " + cleanTitle);
                        request.setDescription("Downloading MP3 format...");
                        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, cleanTitle + ".mp3");
                        
                        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                        manager.enqueue(request);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Download Error", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "DJBridge");

        web.setWebChromeClient(new YTProWebChromeClient(this, web));
        web.setWebViewClient(new YTProWebViewClient(this, web) {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                web.evaluateJavascript("setTimeout(function() { if (typeof players === 'undefined' || !players.left || typeof players.left.getPlayerState !== 'function') { window.location.reload(); } }, 2500);", null);
            }
        });
        web.loadUrl("https://kannujaat.netlify.app/");

        setupReceiver(); setupBackNavigation(); streamManager = new BinaryStreamManager(web, this);

        Intent serviceIntent = new Intent(this, ForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { startForegroundService(serviceIntent); } else { startService(serviceIntent); }
    }

    public void openCustomAudioPopup(final ValueCallback<Uri[]> filePathCallback) { /* Purana logic same rahega */ }

    private void showDeckConfirmationDialog(String videoId, String originalUrl) { /* Purana logic same rahega */ }

    private void applyZoomState(boolean lock, Button btn) {
        if (lock) { web.getSettings().setSupportZoom(false); web.getSettings().setBuiltInZoomControls(false); web.evaluateJavascript("var meta = document.querySelector('meta[name=viewport]'); if(!meta){ meta = document.createElement('meta'); meta.name='viewport'; document.head.appendChild(meta); } meta.setAttribute('content', 'width=1024, user-scalable=no');", null); btn.setText("🔓"); } 
        else { web.getSettings().setSupportZoom(true); web.getSettings().setBuiltInZoomControls(true); web.evaluateJavascript("var meta = document.querySelector('meta[name=viewport]'); if(meta){ meta.setAttribute('content', 'width=1024, user-scalable=yes'); }", null); btn.setText("🔒"); }
    }

    // ⬇️ UPDATE: Added MP3 Download Button between L and R
    private void injectDJButtonsSystem() {
        String js = "if(!window.djShieldActive) { window.djShieldActive = true; document.addEventListener('click', function(e) { var customBtn = e.target.closest('.dj-btn-custom'); if(customBtn) return; var link = e.target.closest('a[href*=\"/watch?v=\"]'); if(link) { e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); var match = link.href.match(/[?&]v=([^&]+)/); if(match) { window.DJBridge.showPopup(match[1], link.href); } } }, true); }" +
                "setInterval(function() { " +
                "  var links = document.querySelectorAll('a[href*=\"/watch?v=\"]'); " +
                "  links.forEach(function(link) { " +
                "    if(link.getAttribute('dj-hooked')) return; link.setAttribute('dj-hooked', 'true'); " +
                "    if(!link.querySelector('img') && !link.querySelector('ytm-custom-thumbnail') && !link.querySelector('.yt-core-image')) return; " +
                
                "    link.style.position = 'relative'; var btnContainer = document.createElement('div'); " +
                "    btnContainer.style = 'position:absolute; bottom:0px; left:0px; right:0px; display:flex; gap:4px; padding:4px; background:rgba(0,0,0,0.6); justify-content:center; z-index:99; align-items:center;'; " +
                
                "    var bL = document.createElement('button'); bL.className='dj-btn-custom'; bL.innerText='🎧 L'; bL.style='background:rgba(52, 211, 153, 0.9); color:#000; border:none; padding:6px 10px; font-size:12px; font-weight:bold; border-radius:4px; flex:1;'; " +
                "    bL.onclick = function(e) { e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); var m = link.href.match(/[?&]v=([^&]+)/); if(m) window.DJBridge.sendToDeck('left', m[1]); }; " +
                
                // ⬇️ THE NATIVE MP3 DOWNLOAD BUTTON
                "    var bM = document.createElement('button'); bM.className='dj-btn-custom'; bM.innerText='⬇️ MP3'; bM.style='background:rgba(255, 105, 180, 0.9); color:#fff; border:none; padding:6px 10px; font-size:12px; font-weight:bold; border-radius:4px; flex:1;'; " +
                "    bM.onclick = function(e) { " +
                "       e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); " +
                "       var m = link.href.match(/[?&]v=([^&]+)/); " +
                "       var titleNode = link.closest('ytm-compact-video-renderer, ytm-video-with-context-renderer'); " +
                "       var rawTitle = titleNode ? (titleNode.querySelector('.yt-core-attributed-string') ? titleNode.querySelector('.yt-core-attributed-string').innerText : 'DJ_Track') : 'DJ_Track'; " +
                "       if(m) window.DJBridge.downloadNativeMP3(m[1], rawTitle); " +
                "    }; " +

                "    var bR = document.createElement('button'); bR.className='dj-btn-custom'; bR.innerText='🎛️ R'; bR.style='background:rgba(34, 211, 238, 0.9); color:#000; border:none; padding:6px 10px; font-size:12px; font-weight:bold; border-radius:4px; flex:1;'; " +
                "    bR.onclick = function(e) { e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); var m = link.href.match(/[?&]v=([^&]+)/); if(m) window.DJBridge.sendToDeck('right', m[1]); }; " +
                
                "    btnContainer.appendChild(bL); btnContainer.appendChild(bM); btnContainer.appendChild(bR); link.appendChild(btnContainer); " +
                "  }); " +
                
                "  var adBox = document.querySelector('.ad-showing, .ad-interrupting'); var vid = document.querySelector('video'); if(adBox && vid && !isNaN(vid.duration)) { vid.currentTime = vid.duration; } " +
                "  var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button'); if(skipBtn) { skipBtn.click(); } " +
                "  if(vid && vid.src) { var isPlaying = !vid.paused; var title = document.title ? document.title.replace(' - YouTube', '') : 'YouTube Audio'; if (window.lastYTState !== isPlaying || window.lastYTTitle !== title) { window.lastYTState = isPlaying; window.lastYTTitle = title; window.DJBridge.updateYTMedia(isPlaying, title, 'YouTube Player'); } } " +
                "}, 1000);";
        ytHomeWeb.evaluateJavascript(js, null);
    }

    private void setupReceiver() { /* Same */ }
    private void setupBackNavigation() { /* Same */ }
    private void handleBackPress() { if (isOfflineMode) { findViewById(999999).performClick(); return; } if (isYtVisible && ytHomeWeb.canGoBack()) { ytHomeWeb.goBack(); } else if (web.canGoBack()) { web.goBack(); } else { showExitDialog(); } }
    private void showExitDialog() { /* Same */ }
    @Override public void onBackPressed() { handleBackPress(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (requestCode == 101) { web.loadUrl("https://kannujaat.netlify.app/"); } }
    @Override public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) { web.evaluateJavascript(isInPictureInPictureMode ? "PIPlayer();" : "removePIP();", null); isPip = isInPictureInPictureMode; }
    @Override protected void onUserLeaveHint() { super.onUserLeaveHint(); if (Build.VERSION.SDK_INT >= 26 && web.getUrl() != null && web.getUrl().contains("watch") && isPlaying) { try { isPip = true; enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(portrait ? 9 : 16, portrait ? 16 : 9)).build()); } catch (IllegalStateException e) {} } }
    @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }
    @Override public void onDestroy() { super.onDestroy(); stopService(new Intent(getApplicationContext(), ForegroundService.class)); if (broadcastReceiver != null) unregisterReceiver(broadcastReceiver); if (streamManager != null) streamManager.cleanup(); if (ytMediaSessionManager != null) ytMediaSessionManager.destroy(); }
}