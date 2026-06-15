package com.google.android.youtube.pro;

import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class OfflinePlayerManager {

    private Context context;
    private FrameLayout parentContainer;
    private FrameLayout mainUI;
    private ListView listView;
    private LinearLayout miniPlayer;
    private SharedPreferences prefs;

    // Media Player Logic
    private MediaPlayer mediaPlayer;
    private ArrayList<AudioTrack> trackList;
    private ArrayList<AudioTrack> displayList; // For searching
    private int currentTrackIndex = -1;
    private boolean isPlaying = false;

    // Colors from Design
    private final String BG_DARK = "#050505";
    private final String GLASS_BG = "#121212";
    private final String NEON_LIME = "#C4F038";
    private final String TEXT_MUTED = "#888888";

    // UI Elements
    private TextView mpTitle, mpArtist, mpCurrentTime, mpTotalTime;
    private Button mpPlayPauseBtn;
    private HeartbeatSeekBar heartbeatSeekBar;
    private Handler progressHandler = new Handler(Looper.getMainLooper());
    private DJDeckListener deckListener;

    public interface DJDeckListener {
        void onLoadToDeck(String deck, Uri fileUri);
    }

    static class AudioTrack {
        long id; String title; String artist; String path; String folder; Uri uri; long duration; String durationStr;
        AudioTrack(long id, String t, String a, String p, String f, Uri u, long d) {
            this.id = id; title = t; artist = a; path = p; folder = f; uri = u; duration = d;
            long min = (duration / 1000) / 60; long sec = (duration / 1000) % 60;
            this.durationStr = String.format("%d:%02d", min, sec);
        }
    }

    public OfflinePlayerManager(Context context, FrameLayout parentContainer, DJDeckListener listener) {
        this.context = context;
        this.parentContainer = parentContainer;
        this.deckListener = listener;
        this.prefs = context.getSharedPreferences("DJ_OFFLINE_PREFS", Context.MODE_PRIVATE);
        this.trackList = new ArrayList<>();
        this.displayList = new ArrayList<>();
        initUI();
    }

    // 🎨 UI ENGINE (DITTO COPY DESIGN)
    private void initUI() {
        mainUI = new FrameLayout(context);
        mainUI.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        mainUI.setBackgroundColor(Color.parseColor(BG_DARK));
        mainUI.setVisibility(View.GONE);

        LinearLayout verticalLayout = new LinearLayout(context);
        verticalLayout.setOrientation(LinearLayout.VERTICAL);
        verticalLayout.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        verticalLayout.setPadding(30, 40, 30, 0);

        // 1. TOP HEADER (Lite Player & Settings)
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        
        TextView logoIcon = new TextView(context);
        logoIcon.setText(" ılı "); // Visualizer logo
        logoIcon.setTextColor(Color.parseColor(BG_DARK));
        logoIcon.setTextSize(18f);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setColor(Color.parseColor(NEON_LIME));
        logoBg.setCornerRadius(20f);
        logoIcon.setBackground(logoBg);
        logoIcon.setPadding(15, 10, 15, 10);
        
        LinearLayout titleBox = new LinearLayout(context);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(20, 0, 0, 0);
        
        TextView title = new TextView(context);
        title.setText("Lite Player");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        
        TextView subTitle = new TextView(context);
        subTitle.setText("Feel the music");
        subTitle.setTextColor(Color.parseColor(TEXT_MUTED));
        subTitle.setTextSize(12f);
        
        titleBox.addView(title);
        titleBox.addView(subTitle);
        
        LinearLayout.LayoutParams tParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        header.addView(logoIcon);
        header.addView(titleBox, tParams);

        Button btnSettings = createIconBtn("⚙️", GLASS_BG, Color.WHITE);
        btnSettings.setOnClickListener(v -> manageBlockedFolders()); // Blocked Folders Manager
        header.addView(btnSettings);

        // 2. SEARCH BAR (Glassy Pill)
        LinearLayout searchBox = new LinearLayout(context);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(Color.parseColor(GLASS_BG));
        searchBg.setStroke(2, Color.parseColor("#222222"));
        searchBg.setCornerRadius(60f);
        searchBox.setBackground(searchBg);
        searchBox.setPadding(40, 10, 20, 10);
        LinearLayout.LayoutParams sParams = new LinearLayout.LayoutParams(-1, 120);
        sParams.setMargins(0, 40, 0, 40);
        searchBox.setLayoutParams(sParams);

        TextView searchIcon = new TextView(context);
        searchIcon.setText("🔍");
        searchIcon.setTextSize(16f);
        
        EditText searchInput = new EditText(context);
        searchInput.setHint("Search audio...");
        searchInput.setHintTextColor(Color.parseColor(TEXT_MUTED));
        searchInput.setTextColor(Color.WHITE);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setSingleLine(true);
        searchInput.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1.0f));

        TextView filterIcon = new TextView(context);
        filterIcon.setText("🎚️");
        filterIcon.setTextColor(Color.parseColor(NEON_LIME));
        filterIcon.setTextSize(16f);

        searchBox.addView(searchIcon);
        searchBox.addView(searchInput);
        searchBox.addView(filterIcon);

        // 3. SECTION TITLE (All Audio)
        LinearLayout sectionBox = new LinearLayout(context);
        sectionBox.setOrientation(LinearLayout.HORIZONTAL);
        sectionBox.setGravity(Gravity.BOTTOM);
        sectionBox.setPadding(0, 0, 0, 20);

        TextView allAudioTxt = new TextView(context);
        allAudioTxt.setText("All Audio");
        allAudioTxt.setTextColor(Color.WHITE);
        allAudioTxt.setTextSize(18f);
        allAudioTxt.setTypeface(null, android.graphics.Typeface.BOLD);
        
        TextView sortTxt = new TextView(context);
        sortTxt.setText("Recently Added ⌄");
        sortTxt.setTextColor(Color.parseColor(NEON_LIME));
        sortTxt.setTextSize(12f);
        sortTxt.setGravity(Gravity.RIGHT);

        sectionBox.addView(allAudioTxt, new LinearLayout.LayoutParams(0, -2, 1.0f));
        sectionBox.addView(sortTxt);

        // 4. LIST VIEW
        listView = new ListView(context);
        listView.setDivider(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, 450); // Massive padding to scroll over Mini Player
        
        verticalLayout.addView(header);
        verticalLayout.addView(searchBox);
        verticalLayout.addView(sectionBox);
        verticalLayout.addView(listView, new LinearLayout.LayoutParams(-1, -1));

        setupMiniPlayer();

        mainUI.addView(verticalLayout);
        mainUI.addView(miniPlayer); 
        parentContainer.addView(mainUI);
    }

    // 🎛️ PREMIUM MINI PLAYER (Wavy Neon Glass)
    private void setupMiniPlayer() {
        miniPlayer = new LinearLayout(context);
        miniPlayer.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams mParams = new FrameLayout.LayoutParams(-1, -2);
        mParams.gravity = Gravity.BOTTOM;
        miniPlayer.setLayoutParams(mParams);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#080A04")); // Deep greenish dark
        bg.setStroke(3, Color.parseColor("#3A4A15")); // Glowing border
        bg.setCornerRadii(new float[]{80f, 80f, 80f, 80f, 0f, 0f, 0f, 0f}); // Top rounded
        miniPlayer.setBackground(bg);
        miniPlayer.setPadding(40, 50, 40, 50);

        // Top Row: Info
        LinearLayout infoRow = new LinearLayout(context);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        
        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        mpTitle = new TextView(context);
        mpTitle.setText("Not Playing");
        mpTitle.setTextColor(Color.WHITE);
        mpTitle.setTextSize(16f);
        mpTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        mpArtist = new TextView(context);
        mpArtist.setText("Select a track");
        mpArtist.setTextColor(Color.parseColor(TEXT_MUTED));
        mpArtist.setTextSize(13f);
        textLayout.addView(mpTitle);
        textLayout.addView(mpArtist);
        
        TextView visualizerIcon = new TextView(context);
        visualizerIcon.setText(" ılılı ");
        visualizerIcon.setTextColor(Color.parseColor(NEON_LIME));
        visualizerIcon.setTextSize(14f);
        GradientDrawable visBg = new GradientDrawable();
        visBg.setColor(Color.parseColor("#1A2508"));
        visBg.setCornerRadius(30f);
        visualizerIcon.setBackground(visBg);
        visualizerIcon.setPadding(20, 15, 20, 15);

        infoRow.addView(textLayout, new LinearLayout.LayoutParams(0, -2, 1.0f));
        infoRow.addView(visualizerIcon);

        // Heartbeat Seekbar & Timestamps
        heartbeatSeekBar = new HeartbeatSeekBar(context);
        LinearLayout.LayoutParams hbParams = new LinearLayout.LayoutParams(-1, 80);
        hbParams.setMargins(0, 30, 0, 10);
        
        LinearLayout timeRow = new LinearLayout(context);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        mpCurrentTime = new TextView(context);
        mpCurrentTime.setText("0:00");
        mpCurrentTime.setTextColor(Color.parseColor(NEON_LIME));
        mpCurrentTime.setTextSize(11f);
        mpTotalTime = new TextView(context);
        mpTotalTime.setText("0:00");
        mpTotalTime.setTextColor(Color.parseColor(TEXT_MUTED));
        mpTotalTime.setTextSize(11f);
        mpTotalTime.setGravity(Gravity.RIGHT);
        timeRow.addView(mpCurrentTime, new LinearLayout.LayoutParams(0, -2, 1.0f));
        timeRow.addView(mpTotalTime);

        // Control Row
        LinearLayout ctrlRow = new LinearLayout(context);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow.setGravity(Gravity.CENTER);
        ctrlRow.setPadding(0, 30, 0, 0);

        Button btnShuffle = createTextBtn("🔀", NEON_LIME);
        Button btnPrev = createTextBtn("⏮", "#FFFFFF");
        btnPrev.setOnClickListener(v -> playPrev());
        
        // Huge Neon Play Button
        mpPlayPauseBtn = new Button(context);
        mpPlayPauseBtn.setText("▶");
        mpPlayPauseBtn.setTextColor(Color.parseColor(NEON_LIME));
        mpPlayPauseBtn.setTextSize(26f);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setColor(Color.parseColor("#111A05"));
        playBg.setStroke(4, Color.parseColor(NEON_LIME));
        playBg.setShape(GradientDrawable.OVAL);
        mpPlayPauseBtn.setBackground(playBg);
        mpPlayPauseBtn.setOnClickListener(v -> togglePlayPause());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(140, 140);
        playParams.setMargins(40, 0, 40, 0);
        
        Button btnNext = createTextBtn("⏭", "#FFFFFF");
        btnNext.setOnClickListener(v -> playNext());
        Button btnRepeat = createTextBtn("🔁", NEON_LIME);

        ctrlRow.addView(btnShuffle, new LinearLayout.LayoutParams(0, -2, 1.0f));
        ctrlRow.addView(btnPrev);
        ctrlRow.addView(mpPlayPauseBtn, playParams);
        ctrlRow.addView(btnNext);
        ctrlRow.addView(btnRepeat, new LinearLayout.LayoutParams(0, -2, 1.0f));

        miniPlayer.addView(infoRow);
        miniPlayer.addView(heartbeatSeekBar, hbParams);
        miniPlayer.addView(timeRow);
        miniPlayer.addView(ctrlRow);
    }

    private Button createIconBtn(String icon, String bgColor, int textColor) {
        Button b = new Button(context);
        b.setText(icon);
        b.setTextColor(textColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgColor));
        bg.setCornerRadius(50f);
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(100, 100);
        b.setLayoutParams(p);
        return b;
    }

    private Button createTextBtn(String icon, String color) {
        Button b = new Button(context);
        b.setText(icon);
        b.setTextColor(Color.parseColor(color));
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setTextSize(20f);
        return b;
    }

    public void toggleVisibility() {
        if (mainUI.getVisibility() == View.VISIBLE) {
            mainUI.setVisibility(View.GONE);
        } else {
            mainUI.setVisibility(View.VISIBLE);
            loadAudioFiles();
        }
    }

    // 🛡️ THE AUDIO SCANNER & FOLDER BLOCKER
    private void loadAudioFiles() {
        trackList.clear();
        Set<String> blockedFolders = prefs.getStringSet("BLOCKED_FOLDERS", new HashSet<>());

        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION };
        
        try (Cursor cursor = context.getContentResolver().query(collection, proj, null, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    long id = cursor.getLong(0);
                    String title = cursor.getString(1);
                    String artist = cursor.getString(2);
                    String path = cursor.getString(3);
                    long duration = cursor.getLong(4);

                    if (path != null) {
                        File f = new File(path);
                        String folderName = f.getParentFile() != null ? f.getParentFile().getName() : "Unknown";
                        
                        // Ignore short audios & Blocked Folders
                        if (duration > 20000 && !blockedFolders.contains(folderName)) {
                            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                            trackList.add(new AudioTrack(id, title, artist, path, folderName, uri, duration));
                        }
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) { e.printStackTrace(); }

        displayList.clear();
        displayList.addAll(trackList);
        updateListView();
    }

    private void updateListView() {
        ArrayAdapter<AudioTrack> adapter = new ArrayAdapter<AudioTrack>(context, android.R.layout.simple_list_item_1, displayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                if (convertView == null) {
                    row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setPadding(30, 30, 30, 30);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(Color.parseColor(GLASS_BG));
                    bg.setCornerRadius(45f);
                    row.setBackground(bg);
                    
                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                    rowParams.setMargins(0, 0, 0, 25);
                    row.setLayoutParams(rowParams);

                    // Track Info
                    LinearLayout textLayout = new LinearLayout(context);
                    textLayout.setOrientation(LinearLayout.VERTICAL);
                    textLayout.setPadding(20, 0, 10, 0);
                    
                    TextView tName = new TextView(context);
                    tName.setId(View.generateViewId());
                    tName.setTextColor(Color.WHITE);
                    tName.setTextSize(16f);
                    tName.setTypeface(null, android.graphics.Typeface.BOLD);
                    tName.setMaxLines(1);
                    tName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    
                    TextView tArtist = new TextView(context);
                    tArtist.setId(View.generateViewId());
                    tArtist.setTextColor(Color.parseColor(TEXT_MUTED));
                    tArtist.setTextSize(12f);
                    
                    textLayout.addView(tName);
                    textLayout.addView(tArtist);
                    LinearLayout.LayoutParams txtParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
                    
                    // 🎧 L / R / PLAY Buttons (Glassy Style)
                    LinearLayout actionBox = new LinearLayout(context);
                    actionBox.setOrientation(LinearLayout.HORIZONTAL);
                    actionBox.setGravity(Gravity.CENTER_VERTICAL);
                    
                    TextView tTime = new TextView(context);
                    tTime.setId(View.generateViewId());
                    tTime.setTextColor(Color.parseColor(TEXT_MUTED));
                    tTime.setTextSize(12f);
                    tTime.setPadding(0, 0, 20, 0);

                    Button btnPlay = createRowPill("▶", NEON_LIME, "#111111");
                    Button btnLeft = createRowPill("L⬅", "#FFFFFF", "#1E1E1E");
                    Button btnRight = createRowPill("➡️R", "#FFFFFF", "#1E1E1E");

                    actionBox.addView(tTime);
                    actionBox.addView(btnLeft);
                    actionBox.addView(btnPlay);
                    actionBox.addView(btnRight);

                    row.addView(textLayout, txtParams);
                    row.addView(actionBox);
                    
                    row.setTag(new View[]{tName, tArtist, tTime, btnPlay, btnLeft, btnRight});
                } else {
                    row = (LinearLayout) convertView;
                }

                View[] views = (View[]) row.getTag();
                TextView tName = (TextView) views[0];
                TextView tArtist = (TextView) views[1];
                TextView tTime = (TextView) views[2];
                Button btnPlay = (Button) views[3];
                Button btnLeft = (Button) views[4];
                Button btnRight = (Button) views[5];

                AudioTrack track = getItem(position);
                tName.setText(track.title != null ? track.title : "Unknown Track");
                tArtist.setText(track.artist != null && !track.artist.contains("unknown") ? track.artist : "📁 " + track.folder);
                tTime.setText(track.durationStr);

                // Actions
                btnPlay.setOnClickListener(v -> playTrack(position));
                btnLeft.setOnClickListener(v -> {
                    if(deckListener != null) deckListener.onLoadToDeck("left", track.uri);
                    Toast.makeText(context, "Loaded " + track.title + " to L Deck", Toast.LENGTH_SHORT).show();
                });
                btnRight.setOnClickListener(v -> {
                    if(deckListener != null) deckListener.onLoadToDeck("right", track.uri);
                    Toast.makeText(context, "Loaded " + track.title + " to R Deck", Toast.LENGTH_SHORT).show();
                });

                // Long Press to Block Folder
                row.setOnLongClickListener(v -> {
                    showBlockDialog(track.folder);
                    return true;
                });

                return row;
            }
        };
        listView.setAdapter(adapter);
    }

    private Button createRowPill(String text, String textColor, String bgColor) {
        Button b = new Button(context);
        b.setText(text);
        b.setTextColor(Color.parseColor(textColor));
        b.setTextSize(11f);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgColor));
        bg.setCornerRadius(30f);
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, 70);
        p.setMargins(8, 0, 8, 0);
        b.setLayoutParams(p);
        b.setPadding(20, 0, 20, 0);
        return b;
    }

    private void showBlockDialog(String folder) {
        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Block Folder?")
            .setMessage("Do you want to hide all audios from '" + folder + "'? (Good for hiding call recordings)")
            .setPositiveButton("Block", (d, w) -> {
                Set<String> blocked = new HashSet<>(prefs.getStringSet("BLOCKED_FOLDERS", new HashSet<>()));
                blocked.add(folder);
                prefs.edit().putStringSet("BLOCKED_FOLDERS", blocked).apply();
                loadAudioFiles();
                Toast.makeText(context, "Folder Blocked! 🛡️", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void manageBlockedFolders() {
        Set<String> blocked = prefs.getStringSet("BLOCKED_FOLDERS", new HashSet<>());
        if (blocked.isEmpty()) {
            Toast.makeText(context, "No blocked folders.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] folders = blocked.toArray(new String[0]);
        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Unblock Folders")
            .setItems(folders, (d, which) -> {
                Set<String> updated = new HashSet<>(blocked);
                updated.remove(folders[which]);
                prefs.edit().putStringSet("BLOCKED_FOLDERS", updated).apply();
                loadAudioFiles();
                Toast.makeText(context, "Unblocked " + folders[which], Toast.LENGTH_SHORT).show();
            }).show();
    }

    // 🔄 CORE AUDIO ENGINE (Auto-Next included)
    private void playTrack(int index) {
        if (index < 0 || index >= displayList.size()) return;
        currentTrackIndex = index;
        AudioTrack track = displayList.get(index);

        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setOnCompletionListener(mp -> playNext()); // 🚀 AUTO NEXT TRIGGER
        } else {
            mediaPlayer.reset();
        }

        try {
            mediaPlayer.setDataSource(context, track.uri);
            mediaPlayer.prepare();
            mediaPlayer.start();
            isPlaying = true;
            mpPlayPauseBtn.setText("⏸");
            mpTitle.setText(track.title);
            mpArtist.setText(track.artist != null && !track.artist.contains("unknown") ? track.artist : track.folder);
            mpTotalTime.setText(track.durationStr);
            startProgressUpdater();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            mpPlayPauseBtn.setText("▶");
        } else {
            mediaPlayer.start();
            isPlaying = true;
            mpPlayPauseBtn.setText("⏸");
            startProgressUpdater();
        }
    }

    private void playNext() {
        if (displayList.isEmpty()) return;
        int next = currentTrackIndex + 1;
        if (next >= displayList.size()) next = 0; 
        playTrack(next);
    }

    private void playPrev() {
        if (displayList.isEmpty()) return;
        int prev = currentTrackIndex - 1;
        if (prev < 0) prev = displayList.size() - 1;
        playTrack(prev);
    }

    private void startProgressUpdater() {
        progressHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && isPlaying) {
                    int curr = mediaPlayer.getCurrentPosition();
                    float progress = (float) curr / mediaPlayer.getDuration();
                    heartbeatSeekBar.setProgress(progress);
                    
                    long min = (curr / 1000) / 60; 
                    long sec = (curr / 1000) % 60;
                    mpCurrentTime.setText(String.format("%d:%02d", min, sec));
                    
                    progressHandler.postDelayed(this, 100); 
                }
            }
        });
    }

    public void onDestroy() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        progressHandler.removeCallbacksAndMessages(null);
    }

    // 💓 DITTO COPY NEON WAVEFORM SEEKBAR
    class HeartbeatSeekBar extends View {
        private Paint paintPlayed, paintUnplayed, paintThumb;
        private float progress = 0f;

        public HeartbeatSeekBar(Context context) {
            super(context);
            paintPlayed = new Paint();
            paintPlayed.setColor(Color.parseColor(NEON_LIME)); // Lime Green
            paintPlayed.setStrokeWidth(8f);
            paintPlayed.setStrokeCap(Paint.Cap.ROUND);

            paintUnplayed = new Paint();
            paintUnplayed.setColor(Color.parseColor("#2A3A10")); // Dark Olive
            paintUnplayed.setStrokeWidth(8f);
            paintUnplayed.setStrokeCap(Paint.Cap.ROUND);
            
            paintThumb = new Paint();
            paintThumb.setColor(Color.WHITE);
            paintThumb.setStrokeWidth(12f);
            paintThumb.setStrokeCap(Paint.Cap.ROUND);

            setOnTouchListener((v, event) -> {
                if(mediaPlayer != null && (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE)) {
                    float newP = event.getX() / getWidth();
                    if(newP < 0) newP = 0; if(newP > 1) newP = 1;
                    mediaPlayer.seekTo((int)(newP * mediaPlayer.getDuration()));
                    setProgress(newP);
                    return true;
                }
                return false;
            });
        }

        public void setProgress(float p) {
            this.progress = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            int bars = 45; // Bars matching the image density
            float spacing = width / (float) bars;
            int centerY = height / 2;

            for (int i = 0; i < bars; i++) {
                float x = i * spacing + (spacing / 2);
                // Creating the realistic audio wave look (center is thicker, edges are random)
                float baseH = (i % 2 == 0) ? 15f : 30f;
                float barHeight = (float) (baseH + Math.abs(Math.sin(i * 0.4)) * (height - 30));
                
                Paint p = (x / width <= progress) ? paintPlayed : paintUnplayed;
                canvas.drawLine(x, centerY - barHeight / 2, x, centerY + barHeight / 2, p);
                
                // Draw thumb indicator
                if (Math.abs((x / width) - progress) < 0.02f) {
                    canvas.drawLine(x, centerY - (barHeight/2) - 10, x, centerY + (barHeight/2) + 10, paintThumb);
                }
            }
        }
    }
}