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
    private YTProWebView ytHomeWeb; 
    
    private FrameLayout rootContainer;
    private FrameLayout ytWrapper;
    private LinearLayout ytHeader;

    private MediaCommandReceiver broadcastReceiver;
    private OnBackInvokedCallback backCallback;
    public BinaryStreamManager streamManager;
    private SharedPreferences prefs;

    private YTMediaSessionManager ytMediaSessionManager;

    private boolean isYtVisible = true;
    private boolean isZoomLocked = true; 

    // 💾 MEMORY & FULLSCREEN VARIABLES
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

    // 🔥 DYNAMIC LAYOUT WITH MEMORY, FULLSCREEN & REFRESH
    private void setupDynamicLayout() {
        rootContainer = new FrameLayout(this);
        rootContainer.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        web = new YTProWebView(this);
        web.setId(R.id.web);
        rootContainer.addView(web, new FrameLayout.LayoutParams(-1, -1));

        ytWrapper = new FrameLayout(this);
        
        // 💾 FETCH MEMORY STATE
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int savedW = prefs.getInt("yt_w", (int)(w * 0.90));
        int savedH = prefs.getInt("yt_h", (int)(h * 0.40));
        float savedX = prefs.getFloat("yt_x", -1f);
        float savedY = prefs.getFloat("yt_y", -1f);

        FrameLayout.LayoutParams wrapParams = new FrameLayout.LayoutParams(savedW, savedH);
        if (savedX != -1f && savedY != -1f) {
            wrapParams.leftMargin = (int) savedX;
            wrapParams.topMargin = (int) savedY;
            wrapParams.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        } else {
            wrapParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            wrapParams.topMargin = 150;
        }
        ytWrapper.setLayoutParams(wrapParams);
        
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(android.graphics.Color.parseColor("#121212"));
        bg.setCornerRadius(25f);
        bg.setStroke(4, android.graphics.Color.parseColor("#34d399")); 
        ytWrapper.setBackground(bg);
        ytWrapper.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) ytWrapper.setElevation(30f);

        LinearLayout innerVertical = new LinearLayout(this);
        innerVertical.setOrientation(LinearLayout.VERTICAL);

        ytHeader = new LinearLayout(this);
        ytHeader.setOrientation(LinearLayout.HORIZONTAL);
        ytHeader.setBackgroundColor(android.graphics.Color.parseColor("#1e293b"));
        ytHeader.setPadding(30, 10, 20, 10);
        ytHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        TextView headerTitle = new TextView(this);
        headerTitle.setText("🖐️ DRAG YOUTUBE");
        headerTitle.setTextColor(android.graphics.Color.WHITE);
        headerTitle.setTextSize(12f);
        headerTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        ytHeader.addView(headerTitle, titleParams);

        // 🔄 REFRESH BUTTON
        Button btnRefresh = new Button(this);
        btnRefresh.setText("🔄");
        btnRefresh.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btnRefresh.setTextColor(android.graphics.Color.WHITE);
        btnRefresh.setPadding(0, 0, 0, 0);
        btnRefresh.setOnClickListener(v -> {
            if(ytHomeWeb != null) ytHomeWeb.reload();
        });
        ytHeader.addView(btnRefresh, new LinearLayout.LayoutParams(100, -1));

        // 🔲 FULLSCREEN BUTTON
        Button btnFullscreen = new Button(this);
        btnFullscreen.setText("🔲");
        btnFullscreen.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btnFullscreen.setTextColor(android.graphics.Color.WHITE);
        btnFullscreen.setPadding(0, 0, 0, 0);
        ytHeader.addView(btnFullscreen, new LinearLayout.LayoutParams(100, -1));

        ytHomeWeb = new YTProWebView(this);
        ytHomeWeb.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));

        innerVertical.addView(ytHeader);
        innerVertical.addView(ytHomeWeb);
        ytWrapper.addView(innerVertical, new FrameLayout.LayoutParams(-1, -1));

        // 🛠️ PINK RESIZE HANDLES
        View rightHandle = new View(this);
        rightHandle.setBackgroundColor(android.graphics.Color.parseColor("#ff69b4")); 
        FrameLayout.LayoutParams rParams = new FrameLayout.LayoutParams(15, -1); 
        rParams.gravity = android.view.Gravity.RIGHT;
        ytWrapper.addView(rightHandle, rParams);

        View bottomHandle = new View(this);
        bottomHandle.setBackgroundColor(android.graphics.Color.parseColor("#ff69b4")); 
        FrameLayout.LayoutParams bParams = new FrameLayout.LayoutParams(-1, 15); 
        bParams.gravity = android.view.Gravity.BOTTOM;
        ytWrapper.addView(bottomHandle, bParams);

        View cornerHandle = new View(this); 
        cornerHandle.setBackgroundColor(android.graphics.Color.parseColor("#ff69b4"));
        FrameLayout.LayoutParams cParams = new FrameLayout.LayoutParams(40, 40);
        cParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT;
        ytWrapper.addView(cornerHandle, cParams);

        rootContainer.addView(ytWrapper);
        setContentView(rootContainer);

        ytWrapper.setVisibility(isYtVisible ? View.VISIBLE : View.GONE);

        // 🔲 FULLSCREEN LOGIC
        btnFullscreen.setOnClickListener(v -> {
            if (!isFullscreen) {
                // Save current state before going fullscreen
                preFullW = ytWrapper.getWidth();
                preFullH = ytWrapper.getHeight();
                preFullX = ytWrapper.getX();
                preFullY = ytWrapper.getY();
                
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) ytWrapper.getLayoutParams();
                p.width = FrameLayout.LayoutParams.MATCH_PARENT;
                p.height = FrameLayout.LayoutParams.MATCH_PARENT;
                p.leftMargin = 0; p.topMargin = 0;
                ytWrapper.setLayoutParams(p);
                ytWrapper.setX(0); ytWrapper.setY(0);
                
                btnFullscreen.setText("🔽");
                rightHandle.setVisibility(View.GONE);
                bottomHandle.setVisibility(View.GONE);
                cornerHandle.setVisibility(View.GONE);
                isFullscreen = true;
            } else {
                // Restore previous state
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) ytWrapper.getLayoutParams();
                p.width = preFullW;
                p.height = preFullH;
                ytWrapper.setLayoutParams(p);
                ytWrapper.setX(preFullX); ytWrapper.setY(preFullY);
                
                btnFullscreen.setText("🔲");
                rightHandle.setVisibility(View.VISIBLE);
                bottomHandle.setVisibility(View.VISIBLE);
                cornerHandle.setVisibility(View.VISIBLE);
                isFullscreen = false;
            }
        });

        // 🖱️ DRAG LOGIC (With Save Memory)
        ytHeader.setOnTouchListener(new View.OnTouchListener() {
            float dX, dY;
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if(isFullscreen) return false; // Prevent drag in fullscreen
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = ytWrapper.getX() - event.getRawX();
                        dY = ytWrapper.getY() - event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        ytWrapper.animate().x(event.getRawX() + dX).y(event.getRawY() + dY).setDuration(0).start();
                        return true;
                    case MotionEvent.ACTION_UP:
                        prefs.edit().putFloat("yt_x", ytWrapper.getX()).putFloat("yt_y", ytWrapper.getY()).apply();
                        return true;
                }
                return false;
            }
        });

        // 📐 RESIZE LOGIC (With Save Memory)
        View.OnTouchListener resizeListener = new View.OnTouchListener() {
            float initialX, initialY;
            int initialWidth, initialHeight;
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch(event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = event.getRawX();
                        initialY = event.getRawY();
                        initialWidth = ytWrapper.getWidth();
                        initialHeight = ytWrapper.getHeight();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int newWidth = initialWidth;
                        int newHeight = initialHeight;
                        
                        if (view == rightHandle || view == cornerHandle) newWidth = initialWidth + (int)(event.getRawX() - initialX);
                        if (view == bottomHandle || view == cornerHandle) newHeight = initialHeight + (int)(event.getRawY() - initialY);
                        
                        if(newWidth < 400) newWidth = 400;
                        if(newHeight < 300) newHeight = 300;
                        
                        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) ytWrapper.getLayoutParams();
                        p.width = newWidth;
                        p.height = newHeight;
                        ytWrapper.setLayoutParams(p);
                        return true;
                    case MotionEvent.ACTION_UP:
                        prefs.edit().putInt("yt_w", ytWrapper.getWidth()).putInt("yt_h", ytWrapper.getHeight()).apply();
                        return true;
                }
                return false;
            }
        };

        rightHandle.setOnTouchListener(resizeListener);
        bottomHandle.setOnTouchListener(resizeListener);
        cornerHandle.setOnTouchListener(resizeListener);
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
                ytWrapper.setVisibility(isYtVisible ? View.VISIBLE : View.GONE);
                btnYtToggle.setText(isYtVisible ? "📺" : "🌐");
                prefs.edit().putBoolean("yt_visible", isYtVisible).apply();
            });
        }

        ytHomeWeb.getSettings().setJavaScriptEnabled(true);
        ytHomeWeb.getSettings().setDomStorageEnabled(true);
        ytHomeWeb.getSettings().setDatabaseEnabled(true);
        ytHomeWeb.getSettings().setMediaPlaybackRequiresUserGesture(false); 
        
        ytHomeWeb.setWebViewClient(new YTProWebViewClient(this, ytHomeWeb) {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectDJButtonsSystem();
            }
        });
        ytHomeWeb.setWebChromeClient(new YTProWebChromeClient(this, ytHomeWeb));
        ytHomeWeb.loadUrl("https://m.youtube.com");

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setUserAgentString("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false); 
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(web, true);
        }

        web.addJavascriptInterface(new WebAppInterface(this, web), "Android");
        
        ytHomeWeb.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void sendToDeck(String deck, String videoId) {
                runOnUiThread(() -> {
                    String fullUrl = "https://www.youtube.com/watch?v=" + videoId;
                    String inputId = "left".equals(deck) ? "leftUrl" : "rightUrl";
                    web.evaluateJavascript(
                        "var el = document.getElementById('" + inputId + "'); " +
                        "if(el) { el.value = '" + fullUrl + "'; el.dispatchEvent(new Event('input', { bubbles: true })); }", null);
                });
            }

            @android.webkit.JavascriptInterface
            public void showPopup(String videoId, String url) {
                runOnUiThread(() -> showDeckConfirmationDialog(videoId, url));
            }

            @android.webkit.JavascriptInterface
            public void updateYTMedia(boolean isPlaying, String title, String artist) {
                runOnUiThread(() -> {
                    if (ytMediaSessionManager != null) {
                        ytMediaSessionManager.updateNotification(isPlaying, title, artist, null);
                    }
                });
            }
        }, "DJBridge");

        web.setWebChromeClient(new YTProWebChromeClient(this, web));
        web.setWebViewClient(new YTProWebViewClient(this, web) {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                web.evaluateJavascript(
                    "setTimeout(function() { " +
                    "  if (typeof players === 'undefined' || !players.left || typeof players.left.getPlayerState !== 'function') { " +
                    "      window.location.reload(); " +
                    "  } " +
                    "}, 2500);", null);
            }
        });
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
        bg.setStroke(3, android.graphics.Color.parseColor("#ffb6c1")); 
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
        btnLeft.setText("🎧 L"); 
        btnLeft.setTextColor(android.graphics.Color.BLACK);
        btnLeft.setTextSize(14f);
        GradientDrawable bL = new GradientDrawable(); bL.setColor(android.graphics.Color.parseColor("#34d399")); bL.setCornerRadius(12f);
        btnLeft.setBackground(bL);

        Button btnRight = new Button(this);
        btnRight.setText("🎛️ R"); 
        btnRight.setTextColor(android.graphics.Color.BLACK);
        btnRight.setTextSize(14f);
        GradientDrawable bR = new GradientDrawable(); bR.setColor(android.graphics.Color.parseColor("#22d3ee")); bR.setCornerRadius(12f);
        btnRight.setBackground(bR);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1.0f);
        p.setMargins(8, 0, 8, 0);
        btnLayout.addView(btnLeft, p);
        btnLayout.addView(btnRight, p);
        mainLayout.addView(btnLayout);

        Button btnWatch = new Button(this);
        btnWatch.setText("▶️ Play on YouTube");
        btnWatch.setTextColor(android.graphics.Color.WHITE);
        btnWatch.setTextSize(14f);
        GradientDrawable bW = new GradientDrawable(); bW.setColor(android.graphics.Color.parseColor("#333333")); bW.setCornerRadius(12f);
        btnWatch.setBackground(bW);
        mainLayout.addView(btnWatch, new LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(mainLayout);

        btnLeft.setOnClickListener(v -> {
            ytHomeWeb.evaluateJavascript("window.DJBridge.sendToDeck('left', '" + videoId + "');", null);
            dialog.dismiss();
        });

        btnRight.setOnClickListener(v -> {
            ytHomeWeb.evaluateJavascript("window.DJBridge.sendToDeck('right', '" + videoId + "');", null);
            dialog.dismiss();
        });

        btnWatch.setOnClickListener(v -> {
            dialog.dismiss();
            ytHomeWeb.loadUrl(originalUrl); 
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

    private void injectDJButtonsSystem() {
        String js = "if(!window.djShieldActive) { " +
                "  window.djShieldActive = true; " +
                "  document.addEventListener('click', function(e) { " +
                "    var customBtn = e.target.closest('.dj-btn-custom'); " +
                "    if(customBtn) return; " + 
                "    var link = e.target.closest('a[href*=\"/watch?v=\"]'); " +
                "    if(link) { " +
                "      e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); " +
                "      var match = link.href.match(/[?&]v=([^&]+)/); " +
                "      if(match) { window.DJBridge.showPopup(match[1], link.href); } " +
                "    } " +
                "  }, true); " +
                "}" +
                
                "setInterval(function() { " +
                "  var links = document.querySelectorAll('a[href*=\"/watch?v=\"]'); " +
                "  links.forEach(function(link) { " +
                "    if(link.getAttribute('dj-hooked')) return; " +
                "    link.setAttribute('dj-hooked', 'true'); " +
                "    if(!link.querySelector('img') && !link.querySelector('ytm-custom-thumbnail') && !link.querySelector('.yt-core-image')) return; " +
                
                "    link.style.position = 'relative'; " +
                "    var btnContainer = document.createElement('div'); " +
                "    btnContainer.style = 'position:absolute; bottom:0px; left:0px; right:0px; display:flex; gap:4px; padding:4px; background:rgba(0,0,0,0.5); justify-content:center; z-index:99;'; " +
                
                "    var bL = document.createElement('button'); bL.className='dj-btn-custom'; bL.innerText='🎧 L'; bL.style='background:rgba(52, 211, 153, 0.9); color:#000; border:none; padding:4px 8px; font-size:12px; font-weight:bold; border-radius:4px; flex:1;'; " +
                "    bL.onclick = function(e) { " +
                "       e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); " +
                "       var m = link.href.match(/[?&]v=([^&]+)/); " +
                "       if(m) window.DJBridge.sendToDeck('left', m[1]); " +
                "    }; " +
                
                "    var bR = document.createElement('button'); bR.className='dj-btn-custom'; bR.innerText='🎛️ R'; bR.style='background:rgba(34, 211, 238, 0.9); color:#000; border:none; padding:4px 8px; font-size:12px; font-weight:bold; border-radius:4px; flex:1;'; " +
                "    bR.onclick = function(e) { " +
                "       e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); " +
                "       var m = link.href.match(/[?&]v=([^&]+)/); " +
                "       if(m) window.DJBridge.sendToDeck('right', m[1]); " +
                "    }; " +
                
                "    btnContainer.appendChild(bL); btnContainer.appendChild(bR); " +
                "    link.appendChild(btnContainer); " +
                "  }); " +
                
                "  var adBox = document.querySelector('.ad-showing, .ad-interrupting'); " +
                "  var vid = document.querySelector('video'); " +
                "  if(adBox && vid && !isNaN(vid.duration)) { vid.currentTime = vid.duration; } " +
                "  var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button'); " +
                "  if(skipBtn) { skipBtn.click(); } " +
                
                "  if(vid && vid.src) { " +
                "     var isPlaying = !vid.paused; " +
                "     var title = document.title ? document.title.replace(' - YouTube', '') : 'YouTube Audio'; " +
                "     if (window.lastYTState !== isPlaying || window.lastYTTitle !== title) { " +
                "         window.lastYTState = isPlaying; " +
                "         window.lastYTTitle = title; " +
                "         window.DJBridge.updateYTMedia(isPlaying, title, 'YouTube Player'); " +
                "     } " +
                "  } " +
                "}, 1000);";
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
    
    @Override 
    protected void onUserLeaveHint() { 
        super.onUserLeaveHint(); 
        if (Build.VERSION.SDK_INT >= 26 && web.getUrl() != null && web.getUrl().contains("watch") && isPlaying) { 
            try { isPip = true; enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(portrait ? 9 : 16, portrait ? 16 : 9)).build()); } catch (IllegalStateException e) {} 
        } 
    }
    
    @Override 
    protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }
    
    @Override 
    public void onDestroy() { 
        super.onDestroy(); 
        stopService(new Intent(getApplicationContext(), ForegroundService.class)); 
        if (broadcastReceiver != null) unregisterReceiver(broadcastReceiver); 
        if (streamManager != null) streamManager.cleanup(); 
        if (ytMediaSessionManager != null) ytMediaSessionManager.destroy();
    }
}
