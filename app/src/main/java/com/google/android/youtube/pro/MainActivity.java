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

    private YTProWebView web; // Netlify Web
    private WebView ytHomeWeb; // Naya YouTube Web
    private View dragHandle;  // Resizer Line
    private LinearLayout rootContainer;

    private MediaCommandReceiver broadcastReceiver;
    private OnBackInvokedCallback backCallback;
    public BinaryStreamManager streamManager;
    private SharedPreferences prefs;

    // Default Height Configuration (In Pixels)
    private int ytHeight = 500; 
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
        
        // Local Storage se states nikalna
        ytHeight = prefs.getInt("yt_height", 600);
        isYtVisible = prefs.getBoolean("yt_visible", true);
        isZoomLocked = prefs.getBoolean("zoom_locked", false);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        // UI Container Setup
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

        // 1. Asli YouTube Home Web View
        ytHomeWeb = new WebView(this);
        LinearLayout.LayoutParams ytParams = new LinearLayout.LayoutParams(-1, ytHeight);
        ytHomeWeb.setLayoutParams(ytParams);
        
        // 2. Drag Handle Divider
        dragHandle = new View(this);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(-1, 25); // 25px thick click area
        dragHandle.setLayoutParams(handleParams);
        GradientDrawable handleLine = new GradientDrawable();
        handleLine.setColor(android.graphics.Color.parseColor("#444444"));
        handleLine.setCornerRadius(5f);
        dragHandle.setBackground(handleLine);

        // 3. Netlify Mixer Web View
        web = new YTProWebView(this);
        web.setId(R.id.web);
        LinearLayout.LayoutParams netlifyParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        web.setLayoutParams(netlifyParams);

        // Adding to layout hierarchy
        rootContainer.addView(ytHomeWeb);
        rootContainer.addView(dragHandle);
        rootContainer.addView(web);
        setContentView(rootContainer);

        // Visibility Apply Karna Base States Se
        ytHomeWeb.setVisibility(isYtVisible ? View.VISIBLE : View.GONE);
        dragHandle.setVisibility(isYtVisible ? View.VISIBLE : View.GONE);

        // 🛠️ DRAG SYSTEM IMPLEMENTATION (Upar-Niche Resize Script)
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
                        
                        // Limits: 200px se chhota aur Screen size se bada na ho sake
                        if (newHeight > 200 && newHeight < (rootContainer.getHeight() - 300)) {
                            ytHeight = newHeight;
                            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) ytHomeWeb.getLayoutParams();
                            p.height = newHeight;
                            ytHomeWeb.setLayoutParams(p);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        // Height setting local storage me save karna
                        prefs.edit().putInt("yt_height", ytHeight).apply();
                        return true;
                }
                return false;
            }
        });
    }

    public void load(boolean dl) {
        this.dL = dl;

        // --- 📺 INTERACTIVE EMOJI TOGGLE CONTROLS ---
        if (findViewById(999999) == null) {
            LinearLayout buttonBox = new LinearLayout(this);
            buttonBox.setId(999999);
            buttonBox.setOrientation(LinearLayout.HORIZONTAL);
            
            // A. Zoom Toggle Button (Bina Background/Padding)
            final Button btnZoomToggle = new Button(this);
            btnZoomToggle.setText(isZoomLocked ? "🔓" : "🔒");
            btnZoomToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            btnZoomToggle.setPadding(0, 0, 0, 0);
            btnZoomToggle.setTextSize(16f);

            // B. YouTube Overlay Toggle Button (Bina Background/Padding)
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

            // Initial Settings Apply
            applyZoomState(isZoomLocked, btnZoomToggle);

            // Zoom Listener
            btnZoomToggle.setOnClickListener(v -> {
                isZoomLocked = !isZoomLocked;
                applyZoomState(isZoomLocked, btnZoomToggle);
                prefs.edit().putBoolean("zoom_locked", isZoomLocked).apply();
            });

            // YouTube Toggle Listener
            btnYtToggle.setOnClickListener(v -> {
                isYtVisible = !isYtVisible;
                ytHomeWeb.setVisibility(isYtVisible ? View.VISIBLE : View.GONE);
                dragHandle.setVisibility(isYtVisible ? View.VISIBLE : View.GONE);
                btnYtToggle.setText(isYtVisible ? "📺" : "🌐");
                prefs.edit().putBoolean("yt_visible", isYtVisible).apply();
            });
        }

        // --- YT HOME WEB SETTINGS ---
        ytHomeWeb.getSettings().setJavaScriptEnabled(true);
        ytHomeWeb.getSettings().setUserAgentString("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        ytHomeWeb.getSettings().setDomStorageEnabled(true);
        ytHomeWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // ⚡ JADOOR SCRIPT INJECTION: Har video thumbnail par Load Left/Right button chipkana
                injectDJButtonsSystem();
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
        
        // ⚡ NAYA WEB BRIDGE INTERFACE: YouTube web se video id Netlify me bypass karne ke liye
        ytHomeWeb.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void sendToDeck(String deck, String videoId) {
                runOnUiThread(() -> {
                    String fullUrl = "https://www.youtube.com/watch?v=" + videoId;
                    if ("left".equals(deck)) {
                        // Netlify ke Left Box me text bhej kar automatic load karwana
                        web.evaluateJavascript("var leftInput = document.querySelector('input[placeholder*=\"Paste YouTube URL\"], [placeholder*=\"left\"]'); " +
                                "if(leftInput) { leftInput.value = '" + fullUrl + "'; leftInput.dispatchEvent(new Event('input', { bubbles: true })); }", null);
                    } else {
                        // Netlify ke Right Box me text bhej kar automatic load karwana
                        web.evaluateJavascript("var rightInput = document.querySelector('input[placeholder*=\"Paste YouTube URL\"], [placeholder*=\"right\"]'); " +
                                "if(rightInput) { rightInput.value = '" + fullUrl + "'; rightInput.dispatchEvent(new Event('input', { bubbles: true })); }", null);
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

        // DJ Foreground service push
        Intent serviceIntent = new Intent(this, ForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
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

    private void injectDJButtonsSystem() {
        // Yeh high-grade script har video card ko scan karke usme Left/Right trigger daal degi
        String js = "setInterval(function() { " +
                "  var videos = document.querySelectorAll('a[href*=\"/watch?v=\"]'); " +
                "  videos.forEach(function(v) { " +
                "    if(v.getAttribute('dj-hooked')) return; " +
                "    v.setAttribute('dj-hooked', 'true'); " +
                "    var urlObj = new URL(v.href); var vId = urlObj.searchParams.get('v'); " +
                "    if(!vId) return; " +
                "    var btnContainer = document.createElement('div'); " +
                "    btnContainer.style = 'display:flex; gap:5px; padding:5px; background:#111; justify-content:center;'; " +
                "    var bL = document.createElement('button'); bL.innerText='🎧 L'; bL.style='background:#34d399; color:#000; border:none; padding:4px 8px; font-size:11px; font-weight:bold; border-radius:4px;'; " +
                "    bL.onclick = function(e) { e.preventDefault(); e.stopPropagation(); window.DJBridge.sendToDeck('left', vId); }; " +
                "    var bR = document.createElement('button'); bR.innerText='🎛️ R'; bR.style='background:#22d3ee; color:#000; border:none; padding:4px 8px; font-size:11px; font-weight:bold; border-radius:4px;'; " +
                "    bR.onclick = function(e) { e.preventDefault(); e.stopPropagation(); window.DJBridge.sendToDeck('right', vId); }; " +
                "    btnContainer.appendChild(bL); btnContainer.appendChild(bR); " +
                "    v.parentNode.insertBefore(btnContainer, v.nextSibling); " +
                "  }); " +
                "}, 2000);";
        ytHomeWeb.evaluateJavascript(js, null);
    }

    public void openCustomAudioPopup(final ValueCallback<Uri[]> filePathCallback) {
        final ArrayList<AudioModel> allTracks = new ArrayList<>();
        final ArrayList<AudioModel> displayList = new ArrayList<>();
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED };
        try (Cursor cursor = getContentResolver().query(collection, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                do {
                    long id = cursor.getLong(idCol); String name = cursor.getString(nameCol);
                    long duration = cursor.getLong(durCol); long date = cursor.getLong(dateCol);
                    Uri trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    if (name != null && (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a"))) {
                        displayList.add(new AudioModel(name, trackUri, duration, date));
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {}
        allTracks.addAll(displayList);

        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout mainLayout = new LinearLayout(this); mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(android.graphics.Color.parseColor("#121212")); mainLayout.setPadding(30, 40, 30, 30);
        TextView titleTv = new TextView(this); titleTv.setText("SELECT DJ TRACK"); titleTv.setTextColor(android.graphics.Color.WHITE); titleTv.setTextSize(18f); titleTv.setGravity(android.view.Gravity.CENTER); mainLayout.addView(titleTv);
        final EditText searchBar = new EditText(this); searchBar.setHint("Search track..."); searchBar.setHintTextColor(android.graphics.Color.GRAY); searchBar.setTextColor(android.graphics.Color.WHITE); searchBar.setPadding(20, 20, 20, 20);
        GradientDrawable searchBg = new GradientDrawable(); searchBg.setColor(android.graphics.Color.parseColor("#222222")); searchBg.setCornerRadius(10f); searchBar.setBackground(searchBg);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2); searchParams.setMargins(0, 30, 0, 20); mainLayout.addView(searchBar, searchParams);
        LinearLayout filterLayout = new LinearLayout(this); filterLayout.setOrientation(LinearLayout.HORIZONTAL);
        final Button btnAtoZ = new Button(this); btnAtoZ.setText("A to Z"); btnAtoZ.setTextSize(11f); final Button btnNewest = new Button(this); btnNewest.setText("Newest"); btnNewest.setTextSize(11f);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, -2, 1.0f); btnParams.setMargins(5, 0, 5, 20); filterLayout.addView(btnAtoZ, btnParams); filterLayout.addView(btnNewest, btnParams); mainLayout.addView(filterLayout);
        final ListView listView = new ListView(this); final ArrayAdapter<AudioModel> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayList); listView.setAdapter(adapter); mainLayout.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        dialog.setContentView(mainLayout); dialog.show();

        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                displayList.clear(); for (AudioModel t : allTracks) { if (t.name.toLowerCase().contains(s.toString().toLowerCase())) displayList.add(t); } adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        btnAtoZ.setOnClickListener(v -> { Collections.sort(displayList, (o1, o2) -> o1.name.compareToIgnoreCase(o2.name)); adapter.notifyDataSetChanged(); });
        btnNewest.setOnClickListener(v -> { Collections.sort(displayList, (o1, o2) -> Long.compare(o2.dateAdded, o1.dateAdded)); adapter.notifyDataSetChanged(); });
        btnNewest.performClick();
        listView.setOnItemClickListener((parent, view, position, id) -> { filePathCallback.onReceiveValue(new Uri[]{displayList.get(position).uri}); dialog.dismiss(); });
        dialog.setOnCancelListener(d -> filePathCallback.onReceiveValue(null));
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
        // Pehle check karega ki splitscreen wala YouTube back ja sakta hai kya
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
    
    @Override 
    public void onDestroy() { 
        super.onDestroy(); 
        stopService(new Intent(getApplicationContext(), ForegroundService.class)); 
        if (broadcastReceiver != null) unregisterReceiver(broadcastReceiver); 
        if (streamManager != null) streamManager.cleanup(); 
    }
}
