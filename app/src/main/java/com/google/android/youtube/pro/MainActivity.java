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
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

// Import components
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
    private MediaCommandReceiver broadcastReceiver;
    private OnBackInvokedCallback backCallback;
    public BinaryStreamManager streamManager;

    // Custom Audio Model
    static class AudioModel {
        String name;
        Uri uri;
        long duration;
        long dateAdded;
        String durationStr;

        AudioModel(String name, Uri uri, long duration, long dateAdded) {
            this.name = name;
            this.uri = uri;
            this.duration = duration;
            this.dateAdded = dateAdded;
            
            // Duration ko Min:Sec me convert karna
            long min = (duration / 1000) / 60;
            long sec = (duration / 1000) % 60;
            this.durationStr = String.format(Locale.US, "%02d:%02d", min, sec);
        }
        @Override
        public String toString() {
            return name + "  [" + durationStr + "]";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        SharedPreferences prefs = getSharedPreferences("YTPRO", MODE_PRIVATE);
        if (!prefs.contains("bgplay")) {
            prefs.edit().putBoolean("bgplay", true).apply();
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        // Storage Permission System
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

    public void load(boolean dl) {
        this.dL = dl;
        web = findViewById(R.id.web);

        // --- MINIMAL EMOJI ZOOM TOGGLE BUTTON ---
        if (findViewById(999999) == null) {
            final android.widget.Button btnZoomToggle = new android.widget.Button(this);
            btnZoomToggle.setId(999999);
            btnZoomToggle.setText("🔒");
            
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setCornerRadii(new float[] { 15, 15, 15, 15, 15, 15, 15, 15 });
            shape.setColor(android.graphics.Color.parseColor("#77000000"));
            shape.setStroke(1, android.graphics.Color.parseColor("#88FFFFFF"));
            btnZoomToggle.setBackground(shape);
            btnZoomToggle.setTextColor(android.graphics.Color.WHITE);
            btnZoomToggle.setTextSize(14f);
            btnZoomToggle.setPadding(15, 10, 15, 10);

            android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            );
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.RIGHT;
            params.setMargins(0, 45, 10, 0);

            addContentView(btnZoomToggle, params);

            final boolean[] isZoomLocked = {false};
            btnZoomToggle.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    if (!isZoomLocked[0]) {
                        web.getSettings().setSupportZoom(false);
                        web.getSettings().setBuiltInZoomControls(false);
                        web.evaluateJavascript("var meta = document.querySelector('meta[name=viewport]'); if(!meta){ meta = document.createElement('meta'); meta.name='viewport'; document.head.appendChild(meta); } meta.setAttribute('content', 'width=1024, user-scalable=no');", null);
                        btnZoomToggle.setText("🔓");
                        isZoomLocked[0] = true;
                    } else {
                        web.getSettings().setSupportZoom(true);
                        web.getSettings().setBuiltInZoomControls(true);
                        web.evaluateJavascript("var meta = document.querySelector('meta[name=viewport]'); if(meta){ meta.setAttribute('content', 'width=1024, user-scalable=yes'); }", null);
                        btnZoomToggle.setText("🔒");
                        isZoomLocked[0] = false;
                    }
                }
            });
        }

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setUserAgentString("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(true);
        web.getSettings().setSupportZoom(true); 
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false); 
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(web, true);
        }

        String url = "https://kannujaat.netlify.app/";
        web.addJavascriptInterface(new WebAppInterface(this, web), "Android");
        web.setWebChromeClient(new YTProWebChromeClient(this, web));
        web.setWebViewClient(new YTProWebViewClient(this, web));
        web.loadUrl(url);

        setupReceiver();
        setupBackNavigation();
        streamManager = new BinaryStreamManager(web,this);
    }

    // ---------------------------------------------------------------------
    // 🛠️ HARDCORE CUSTOM DJ AUDIO POPUP (SEARCH, SORT, DURATION, NO FILE MANAGER)
    // ---------------------------------------------------------------------
    public void openCustomAudioPopup(final ValueCallback<Uri[]> filePathCallback) {
        final ArrayList<AudioModel> allTracks = new ArrayList<>();
        final ArrayList<AudioModel> displayList = new ArrayList<>();

        // 1. Phone se saare audio tracks scan karna
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? 
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) : 
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String[] projection = { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED };
        
        try (Cursor cursor = getContentResolver().query(collection, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                do {
                    long id = cursor.getLong(idCol);
                    String name = cursor.getString(nameCol);
                    long duration = cursor.getLong(durCol);
                    long date = cursor.getLong(dateCol);
                    Uri trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    
                    if (name != null && (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a"))) {
                        AudioModel track = new AudioModel(name, trackUri, duration, date);
                        allTracks.add(track);
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        displayList.addAll(allTracks);

        // 2. Custom Dialog Builder Pure Java Code se UI Design Karna
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(android.graphics.Color.parseColor("#121212")); // Dark Background
        mainLayout.setPadding(30, 40, 30, 30);

        // Header Title
        TextView titleTv = new TextView(this);
        titleTv.setText("SELECT DJ TRACK");
        titleTv.setTextColor(android.graphics.Color.WHITE);
        titleTv.setTextSize(18f);
        titleTv.setGravity(android.view.Gravity.CENTER);
        mainLayout.addView(titleTv);

        // Search Bar
        final EditText searchBar = new EditText(this);
        searchBar.setHint("Search track name...");
        searchBar.setHintTextColor(android.graphics.Color.GRAY);
        searchBar.setTextColor(android.graphics.Color.WHITE);
        searchBar.setPadding(20, 20, 20, 20);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(android.graphics.Color.parseColor("#222222"));
        searchBg.setCornerRadius(10f);
        searchBar.setBackground(searchBg);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(0, 30, 0, 20);
        mainLayout.addView(searchBar, searchParams);

        // Filter/Sort Buttons (Horizontal Layout)
        LinearLayout filterLayout = new LinearLayout(this);
        filterLayout.setOrientation(LinearLayout.HORIZONTAL);
        
        final Button btnAtoZ = new Button(this);
        btnAtoZ.setText("A to Z");
        btnAtoZ.setTextSize(11f);
        final Button btnNewest = new Button(this);
        btnNewest.setText("Newest First");
        btnNewest.setTextSize(11f);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        btnParams.setMargins(5, 0, 5, 20);
        filterLayout.addView(btnAtoZ, btnParams);
        filterLayout.addView(btnNewest, btnParams);
        mainLayout.addView(filterLayout);

        // Tracks ListView
        final ListView listView = new ListView(this);
        final ArrayAdapter<AudioModel> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayList);
        listView.setAdapter(adapter);
        mainLayout.addView(listView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        dialog.setContentView(mainLayout);
        dialog.show();

        // 3. LOGIC: Search System implementation
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                displayList.clear();
                String query = s.toString().toLowerCase(Locale.getDefault());
                for (AudioModel track : allTracks) {
                    if (track.name.toLowerCase(Locale.getDefault()).contains(query)) {
                        displayList.add(track);
                    }
                }
                adapter.notifyDataSetChanged();
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 4. LOGIC: Sorting Functions
        btnAtoZ.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Collections.sort(displayList, new Comparator<AudioModel>() {
                    @Override
                    public int compare(AudioModel o1, AudioModel o2) {
                        return o1.name.compareToIgnoreCase(o2.name);
                    }
                });
                adapter.notifyDataSetChanged();
            }
        });

        btnNewest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Collections.sort(displayList, new Comparator<AudioModel>() {
                    @Override
                    public int compare(AudioModel o1, AudioModel o2) {
                        return Long.compare(o2.dateAdded, o1.dateAdded);
                    }
                });
                adapter.notifyDataSetChanged();
            }
        });

        // Default sort: Newest First
        btnNewest.performClick();

        // 5. Item Selection -> Pass data back to Netlify Website
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AudioModel selectedTrack = displayList.get(position);
                filePathCallback.onReceiveValue(new Uri[]{selectedTrack.uri});
                dialog.dismiss();
            }
        });

        // Handle Back button/Cancel popup without freezing WebView
        dialog.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(android.content.DialogInterface dialog) {
                filePathCallback.onReceiveValue(null);
            }
        });
    }
    // ---------------------------------------------------------------------

    private void setupReceiver() {
        broadcastReceiver = new MediaCommandReceiver(web);
        if (Build.VERSION.SDK_INT >= 34 && getApplicationInfo().targetSdkVersion >= 34) {
            registerReceiver(broadcastReceiver, new IntentFilter("TRACKS_TRACKS"), RECEIVER_EXPORTED);
        } else {
            registerReceiver(broadcastReceiver, new IntentFilter("TRACKS_TRACKS"));
        }
    }

    private void setupBackNavigation() {
        if (Build.VERSION.SDK_INT >= 33) {
            OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
            backCallback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    handleBackPress();
                }
            };
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    private void handleBackPress() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBackPress();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            web.loadUrl("https://kannujaat.netlify.app/");
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        web.evaluateJavascript(isInPictureInPictureMode ? "PIPlayer();" : "removePIP();", null);
        isPip = isInPictureInPictureMode;
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= 26 && web.getUrl() != null && web.getUrl().contains("watch")) {
            if (isPlaying) {
                try {
                    isPip = true;
                    PictureInPictureParams params = new PictureInPictureParams.Builder()
                            .setAspectRatio(new Rational(portrait ? 9 : 16, portrait ? 16 : 9))
                            .build();
                    enterPictureInPictureMode(params);
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopService(new Intent(getApplicationContext(), ForegroundService.class));
        if (broadcastReceiver != null) unregisterReceiver(broadcastReceiver);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (streamManager != null) {
            streamManager.cleanup();
        }
    }
}
