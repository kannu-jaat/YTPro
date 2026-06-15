package com.google.android.youtube.pro;

import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class OfflinePlayerManager {

    private Context context;
    private FrameLayout parentContainer;
    private FrameLayout mainUI;
    private ListView listView;
    private LinearLayout miniPlayer;
    private SharedPreferences prefs;

    private MediaPlayer mediaPlayer;
    private ArrayList<AudioTrack> allTracks;
    private ArrayList<AudioTrack> filteredTracks;
    private CustomAudioAdapter adapter;
    private int currentTrackIndex = -1;
    private boolean isPlaying = false;

    // UI Elements
    private TextView mpTitle, mpArtist, mpCurrTime, mpTotalTime, sortText;
    private Button mpPlayPauseBtn;
    private WaveSeekBar waveSeekBar;
    private Handler handler = new Handler(Looper.getMainLooper());

    private DJDeckListener deckListener;
    private String currentSort = "Date Added"; 

    public interface DJDeckListener {
        void onLoadToDeck(String deck, Uri fileUri);
    }

    static class AudioTrack {
        long id; String title; String artist; String path; String folder; Uri uri; long duration; long albumId; long dateAdded;
        AudioTrack(long id, String t, String a, String p, String f, Uri u, long d, long al, long da) {
            this.id = id; title = t; artist = a; path = p; folder = f; uri = u; duration = d; albumId = al; dateAdded = da;
        }
        public String getDurationStr() {
            long min = (duration / 1000) / 60; long sec = (duration / 1000) % 60;
            return String.format(Locale.US, "%d:%02d", min, sec);
        }
    }

    public OfflinePlayerManager(Context context, FrameLayout parentContainer, DJDeckListener listener) {
        this.context = context;
        this.parentContainer = parentContainer;
        this.deckListener = listener;
        this.prefs = context.getSharedPreferences("DJ_OFFLINE_PREFS", Context.MODE_PRIVATE);
        this.allTracks = new ArrayList<>();
        this.filteredTracks = new ArrayList<>();
        initUI();
    }

    // ================= UI ENGINE =================
    private void initUI() {
        mainUI = new FrameLayout(context);
        mainUI.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        mainUI.setBackgroundColor(android.graphics.Color.parseColor("#060906")); // Pure Dark UI
        mainUI.setVisibility(View.GONE);

        LinearLayout verticalLayout = new LinearLayout(context);
        verticalLayout.setOrientation(LinearLayout.VERTICAL);
        verticalLayout.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        // 1. HEADER ROW
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(40, 50, 40, 20);
        header.setGravity(Gravity.CENTER_VERTICAL);
        
        LinearLayout titleBox = new LinearLayout(context);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView mainTitle = new TextView(context);
        mainTitle.setText("Lite Player"); mainTitle.setTextColor(android.graphics.Color.WHITE);
        mainTitle.setTextSize(24f); mainTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView subTitle = new TextView(context);
        subTitle.setText("Feel the music"); subTitle.setTextColor(android.graphics.Color.GRAY); subTitle.setTextSize(12f);
        titleBox.addView(mainTitle); titleBox.addView(subTitle);
        LinearLayout.LayoutParams tParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        header.addView(titleBox, tParams);

        Button btnSettings = createIconBtn("⚙️");
        btnSettings.setOnClickListener(v -> openSettingsDialog());
        header.addView(btnSettings);

        // 2. LIVE SEARCH BAR
        LinearLayout searchBox = new LinearLayout(context);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sParams = new LinearLayout.LayoutParams(-1, 120);
        sParams.setMargins(40, 20, 40, 20);
        searchBox.setLayoutParams(sParams);
        GradientDrawable sBg = new GradientDrawable();
        sBg.setColor(android.graphics.Color.parseColor("#151815"));
        sBg.setCornerRadius(60f);
        searchBox.setBackground(sBg);
        searchBox.setPadding(30, 0, 30, 0);

        TextView searchIcon = new TextView(context); searchIcon.setText("🔍 "); searchIcon.setTextSize(16f);
        EditText etSearch = new EditText(context);
        etSearch.setHint("Search audio..."); etSearch.setHintTextColor(android.graphics.Color.GRAY);
        etSearch.setTextColor(android.graphics.Color.WHITE); etSearch.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        etSearch.setSingleLine(true);
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(0, -1, 1.0f);
        
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filterList(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        Button btnFilter = createIconBtn("🎛️");
        btnFilter.setTextColor(android.graphics.Color.parseColor("#A2F42B"));
        btnFilter.setOnClickListener(v -> manageBlockedItems());

        searchBox.addView(searchIcon); searchBox.addView(etSearch, etParams); searchBox.addView(btnFilter);

        // 3. SORTING ROW
        LinearLayout sortRow = new LinearLayout(context);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setPadding(40, 20, 40, 20);
        TextView allAudioTxt = new TextView(context);
        allAudioTxt.setText("All Audio"); allAudioTxt.setTextColor(android.graphics.Color.WHITE);
        allAudioTxt.setTextSize(16f); allAudioTxt.setTypeface(null, android.graphics.Typeface.BOLD);
        
        sortText = new TextView(context);
        sortText.setText(currentSort + " ∨"); sortText.setTextColor(android.graphics.Color.parseColor("#A2F42B"));
        sortText.setTextSize(14f); sortText.setGravity(Gravity.RIGHT);
        sortText.setOnClickListener(v -> openSortMenu(sortText));

        LinearLayout.LayoutParams srParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        sortRow.addView(allAudioTxt, srParams); sortRow.addView(sortText);

        // 4. LISTVIEW
        listView = new ListView(context);
        listView.setDivider(null);
        listView.setPadding(40, 0, 40, 300); 
        listView.setClipToPadding(false);
        adapter = new CustomAudioAdapter();
        listView.setAdapter(adapter);
        LinearLayout.LayoutParams lParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);

        // 5. MINI PLAYER
        setupMiniPlayer();

        verticalLayout.addView(header);
        verticalLayout.addView(searchBox);
        verticalLayout.addView(sortRow);
        verticalLayout.addView(listView, lParams);
        
        mainUI.addView(verticalLayout);
        mainUI.addView(miniPlayer); 
        parentContainer.addView(mainUI);
    }

    private Button createIconBtn(String icon) {
        Button b = new Button(context); b.setText(icon); b.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        b.setTextSize(20f); b.setLayoutParams(new LinearLayout.LayoutParams(100, 100));
        return b;
    }

    // ================= MINI PLAYER ENGINE =================
    private void setupMiniPlayer() {
        miniPlayer = new LinearLayout(context);
        miniPlayer.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams mParams = new FrameLayout.LayoutParams(-1, -2);
        mParams.gravity = Gravity.BOTTOM; mParams.setMargins(20, 0, 20, 20);
        miniPlayer.setLayoutParams(mParams);

        // Neon Glow Glassmorphic Shape
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(android.graphics.Color.parseColor("#1A1F1A")); // Semi-dark green tint
        bg.setCornerRadius(50f);
        bg.setStroke(3, android.graphics.Color.parseColor("#A2F42B")); // Neon Green Glow
        miniPlayer.setBackground(bg);
        miniPlayer.setPadding(30, 30, 30, 30);
        miniPlayer.setElevation(20f);

        // Top Row: Info & Visualizer
        LinearLayout infoRow = new LinearLayout(context);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        
        // Mock Cover Art in Mini Player
        TextView mockArt = new TextView(context);
        mockArt.setBackgroundColor(android.graphics.Color.parseColor("#252A25"));
        mockArt.setText("🎵"); mockArt.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams artP = new LinearLayout.LayoutParams(100, 100);
        artP.setMargins(0,0,20,0);
        mockArt.setLayoutParams(artP);

        LinearLayout txtBox = new LinearLayout(context);
        txtBox.setOrientation(LinearLayout.VERTICAL);
        mpTitle = new TextView(context); mpTitle.setText("Not Playing"); mpTitle.setTextColor(android.graphics.Color.WHITE); mpTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        mpArtist = new TextView(context); mpArtist.setText("--"); mpArtist.setTextColor(android.graphics.Color.GRAY); mpArtist.setTextSize(12f);
        txtBox.addView(mpTitle); txtBox.addView(mpArtist);
        
        TextView visualizerIcon = new TextView(context); visualizerIcon.setText("🎛️"); visualizerIcon.setTextSize(24f); visualizerIcon.setGravity(Gravity.RIGHT);

        infoRow.addView(mockArt); infoRow.addView(txtBox, new LinearLayout.LayoutParams(0, -2, 1.0f)); infoRow.addView(visualizerIcon);

        // Wave Seekbar
        waveSeekBar = new WaveSeekBar(context);
        LinearLayout.LayoutParams wParams = new LinearLayout.LayoutParams(-1, 80);
        wParams.setMargins(0, 20, 0, 10);
        
        // Timestamps
        LinearLayout timeRow = new LinearLayout(context);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        mpCurrTime = new TextView(context); mpCurrTime.setText("0:00"); mpCurrTime.setTextColor(android.graphics.Color.parseColor("#A2F42B")); mpCurrTime.setTextSize(12f);
        mpTotalTime = new TextView(context); mpTotalTime.setText("0:00"); mpTotalTime.setTextColor(android.graphics.Color.GRAY); mpTotalTime.setTextSize(12f); mpTotalTime.setGravity(Gravity.RIGHT);
        timeRow.addView(mpCurrTime, new LinearLayout.LayoutParams(0, -2, 1.0f)); timeRow.addView(mpTotalTime, new LinearLayout.LayoutParams(0, -2, 1.0f));

        // Controls
        LinearLayout controlRow = new LinearLayout(context);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.setGravity(Gravity.CENTER);
        controlRow.setPadding(0, 20, 0, 0);

        Button btnShuf = createIconBtn("🔀"); btnShuf.setTextColor(android.graphics.Color.parseColor("#A2F42B"));
        Button btnPrev = createIconBtn("⏮️"); btnPrev.setOnClickListener(v -> playPrev());
        
        // Play Button with Circle
        mpPlayPauseBtn = new Button(context);
        mpPlayPauseBtn.setText("▶️");
        GradientDrawable playBg = new GradientDrawable(); playBg.setShape(GradientDrawable.OVAL); playBg.setStroke(4, android.graphics.Color.parseColor("#A2F42B")); playBg.setColor(android.graphics.Color.parseColor("#151815"));
        mpPlayPauseBtn.setBackground(playBg);
        mpPlayPauseBtn.setLayoutParams(new LinearLayout.LayoutParams(130, 130));
        mpPlayPauseBtn.setOnClickListener(v -> togglePlayPause());

        Button btnNext = createIconBtn("⏭️"); btnNext.setOnClickListener(v -> playNext());
        Button btnRep = createIconBtn("🔁"); btnRep.setTextColor(android.graphics.Color.parseColor("#A2F42B"));

        controlRow.addView(btnShuf); controlRow.addView(btnPrev); controlRow.addView(mpPlayPauseBtn); controlRow.addView(btnNext); controlRow.addView(btnRep);

        miniPlayer.addView(infoRow);
        miniPlayer.addView(waveSeekBar, wParams);
        miniPlayer.addView(timeRow);
        miniPlayer.addView(controlRow);
    }

    public void toggleVisibility() {
        if (mainUI.getVisibility() == View.VISIBLE) mainUI.setVisibility(View.GONE);
        else { mainUI.setVisibility(View.VISIBLE); loadAudioData(); }
    }

    // ================= DATA LOGIC (Search, Sort, Block, Load) =================
    private void loadAudioData() {
        allTracks.clear();
        Set<String> blockedFiles = prefs.getStringSet("BLOCKED_FILES", new HashSet<>());
        Set<String> blockedFolders = prefs.getStringSet("BLOCKED_FOLDERS", new HashSet<>());
        int minSecs = prefs.getInt("MIN_AUDIO_SEC", 30); // Default 30s
        long minMillis = minSecs * 1000L;

        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATE_ADDED };
        
        try (Cursor c = context.getContentResolver().query(collection, proj, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                do {
                    long id = c.getLong(0); String title = c.getString(1); String artist = c.getString(2);
                    String path = c.getString(3); long dur = c.getLong(4); long aId = c.getLong(5); long date = c.getLong(6);

                    if (path != null) {
                        File f = new File(path);
                        String folder = f.getParentFile() != null ? f.getParentFile().getName() : "Unknown";
                        if (title == null || title.trim().isEmpty()) title = f.getName(); // Fallback to filename

                        // 🛡️ BLOCKING LOGIC CHECK
                        if (dur >= minMillis && !blockedFolders.contains(folder) && !blockedFiles.contains(path)) {
                            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                            allTracks.add(new AudioTrack(id, title, artist, path, folder, uri, dur, aId, date));
                        }
                    }
                } while (c.moveToNext());
            }
        } catch (Exception e) {}
        
        applySort(currentSort);
    }

    private void filterList(String query) {
        filteredTracks.clear();
        if (query.isEmpty()) filteredTracks.addAll(allTracks);
        else {
            String q = query.toLowerCase();
            for (AudioTrack t : allTracks) {
                if (t.title.toLowerCase().contains(q) || (t.artist != null && t.artist.toLowerCase().contains(q))) {
                    filteredTracks.add(t);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void openSortMenu(View anchor) {
        PopupMenu popup = new PopupMenu(context, anchor);
        popup.getMenu().add("Date Added");
        popup.getMenu().add("Title (A-Z)");
        popup.getMenu().add("Duration (Longest)");
        popup.setOnMenuItemClickListener(item -> {
            currentSort = item.getTitle().toString();
            sortText.setText(currentSort + " ∨");
            applySort(currentSort);
            return true;
        });
        popup.show();
    }

    private void applySort(String type) {
        if (type.equals("Title (A-Z)")) Collections.sort(allTracks, (a, b) -> a.title.compareToIgnoreCase(b.title));
        else if (type.equals("Duration (Longest)")) Collections.sort(allTracks, (a, b) -> Long.compare(b.duration, a.duration));
        else Collections.sort(allTracks, (a, b) -> Long.compare(b.dateAdded, a.dateAdded)); // Date Added (Default)
        
        filterList(""); // Update UI
    }

    // ================= CUSTOM ADAPTER (List Items) =================
    class CustomAudioAdapter extends ArrayAdapter<AudioTrack> {
        public CustomAudioAdapter() { super(context, 0, filteredTracks); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView == null) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(20, 20, 20, 20);
                row.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable bg = new GradientDrawable(); bg.setColor(android.graphics.Color.parseColor("#151815")); bg.setCornerRadius(30f);
                row.setBackground(bg);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, 20); row.setLayoutParams(p);

                // Mock Cover
                TextView cover = new TextView(context); cover.setText("🎵"); cover.setBackgroundColor(android.graphics.Color.parseColor("#252A25")); cover.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams cvP = new LinearLayout.LayoutParams(120, 120); cvP.setMargins(0,0,20,0); cover.setLayoutParams(cvP);

                // Text Info
                LinearLayout txtBox = new LinearLayout(context); txtBox.setOrientation(LinearLayout.VERTICAL);
                TextView title = new TextView(context); title.setTextColor(android.graphics.Color.WHITE); title.setTextSize(16f); title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                TextView artist = new TextView(context); artist.setTextColor(android.graphics.Color.GRAY); artist.setTextSize(12f);
                txtBox.addView(title); txtBox.addView(artist);
                
                TextView duration = new TextView(context); duration.setTextColor(android.graphics.Color.GRAY); duration.setTextSize(12f); duration.setGravity(Gravity.RIGHT);

                // 3-Dot Menu
                TextView menuBtn = new TextView(context); menuBtn.setText("⋮"); menuBtn.setTextColor(android.graphics.Color.WHITE); menuBtn.setTextSize(24f); menuBtn.setPadding(30, 0, 10, 0);

                row.addView(cover); row.addView(txtBox, new LinearLayout.LayoutParams(0, -2, 1.0f)); row.addView(duration); row.addView(menuBtn);
                row.setTag(new View[]{title, artist, duration, menuBtn});
            } else { row = (LinearLayout) convertView; }

            View[] views = (View[]) row.getTag();
            AudioTrack track = getItem(position);
            ((TextView)views[0]).setText(track.title);
            ((TextView)views[1]).setText(track.artist != null ? track.artist : track.folder);
            ((TextView)views[2]).setText(track.getDurationStr());

            row.setOnClickListener(v -> playTrack(track));

            ((TextView)views[3]).setOnClickListener(v -> {
                PopupMenu pop = new PopupMenu(context, v);
                pop.getMenu().add("🎧 Load to L Deck");
                pop.getMenu().add("🎛️ Load to R Deck");
                pop.getMenu().add("🚫 Block Audio");
                pop.getMenu().add("📁 Block Folder");
                pop.setOnMenuItemClickListener(item -> {
                    String title = item.getTitle().toString();
                    if(title.contains("L Deck")) { if(deckListener!=null) deckListener.onLoadToDeck("left", track.uri); Toast.makeText(context,"Loaded Left",0).show(); }
                    else if(title.contains("R Deck")) { if(deckListener!=null) deckListener.onLoadToDeck("right", track.uri); Toast.makeText(context,"Loaded Right",0).show(); }
                    else if(title.contains("Audio")) blockFile(track.path);
                    else if(title.contains("Folder")) blockFolder(track.folder);
                    return true;
                });
                pop.show();
            });
            return row;
        }
    }

    // ================= SETTINGS & BLOCKING =================
    private void openSettingsDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("⚙️ Settings");
        LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(50,20,50,20);
        TextView t = new TextView(context); t.setText("Hide audio shorter than (seconds):"); t.setTextColor(android.graphics.Color.WHITE);
        EditText e = new EditText(context); e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); e.setTextColor(android.graphics.Color.WHITE);
        e.setText(String.valueOf(prefs.getInt("MIN_AUDIO_SEC", 30)));
        l.addView(t); l.addView(e); b.setView(l);
        b.setPositiveButton("Save", (d,w) -> {
            try { prefs.edit().putInt("MIN_AUDIO_SEC", Integer.parseInt(e.getText().toString())).apply(); loadAudioData(); Toast.makeText(context, "Saved", 0).show(); } catch (Exception ex){}
        });
        b.show();
    }

    private void blockFile(String path) {
        Set<String> files = new HashSet<>(prefs.getStringSet("BLOCKED_FILES", new HashSet<>()));
        files.add(path); prefs.edit().putStringSet("BLOCKED_FILES", files).apply(); loadAudioData(); Toast.makeText(context, "Audio Blocked", 0).show();
    }
    private void blockFolder(String folder) {
        Set<String> folders = new HashSet<>(prefs.getStringSet("BLOCKED_FOLDERS", new HashSet<>()));
        folders.add(folder); prefs.edit().putStringSet("BLOCKED_FOLDERS", folders).apply(); loadAudioData(); Toast.makeText(context, "Folder Blocked", 0).show();
    }
    private void manageBlockedItems() {
        Toast.makeText(context, "Clearing all Blocks for now!", Toast.LENGTH_SHORT).show();
        prefs.edit().putStringSet("BLOCKED_FILES", new HashSet<>()).putStringSet("BLOCKED_FOLDERS", new HashSet<>()).apply();
        loadAudioData();
    }

    // ================= AUDIO PLAYER ENGINE =================
    private void playTrack(AudioTrack track) {
        currentTrackIndex = filteredTracks.indexOf(track);
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setOnCompletionListener(mp -> playNext()); // AUTO-NEXT
        } else mediaPlayer.reset();

        try {
            mediaPlayer.setDataSource(context, track.uri);
            mediaPlayer.prepare(); mediaPlayer.start();
            isPlaying = true;
            mpPlayPauseBtn.setText("⏸️"); mpTitle.setText(track.title); mpArtist.setText(track.artist != null ? track.artist : track.folder);
            mpTotalTime.setText(track.getDurationStr());
            startWaveUpdater();
        } catch (Exception e) {}
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) { mediaPlayer.pause(); isPlaying = false; mpPlayPauseBtn.setText("▶️"); } 
        else { mediaPlayer.start(); isPlaying = true; mpPlayPauseBtn.setText("⏸️"); startWaveUpdater(); }
    }

    private void playNext() {
        if (filteredTracks.isEmpty()) return;
        int next = currentTrackIndex + 1; if (next >= filteredTracks.size()) next = 0;
        playTrack(filteredTracks.get(next));
    }

    private void playPrev() {
        if (filteredTracks.isEmpty()) return;
        int prev = currentTrackIndex - 1; if (prev < 0) prev = filteredTracks.size() - 1;
        playTrack(filteredTracks.get(prev));
    }

    private void startWaveUpdater() {
        handler.post(new Runnable() {
            @Override public void run() {
                if (mediaPlayer != null && isPlaying) {
                    float prog = (float) mediaPlayer.getCurrentPosition() / mediaPlayer.getDuration();
                    waveSeekBar.setProgress(prog);
                    
                    long m = (mediaPlayer.getCurrentPosition() / 1000) / 60; long s = (mediaPlayer.getCurrentPosition() / 1000) % 60;
                    mpCurrTime.setText(String.format(Locale.US, "%d:%02d", m, s));
                    handler.postDelayed(this, 100);
                }
            }
        });
    }

    public void onDestroy() {
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        handler.removeCallbacksAndMessages(null);
    }

    // ================= REAL WAVE SEEKBAR (Sci-Fi Look) =================
    class WaveSeekBar extends View {
        private Paint paintPlayed, paintUnplayed;
        private float progress = 0f;
        private float[] randomHeights; // Store static heights so wave doesn't jitter randomly

        public WaveSeekBar(Context context) {
            super(context);
            paintPlayed = new Paint(); paintPlayed.setColor(android.graphics.Color.parseColor("#A2F42B")); paintPlayed.setStrokeWidth(5f); paintPlayed.setStrokeCap(Paint.Cap.ROUND);
            paintUnplayed = new Paint(); paintUnplayed.setColor(android.graphics.Color.parseColor("#334433")); paintUnplayed.setStrokeWidth(5f); paintUnplayed.setStrokeCap(Paint.Cap.ROUND);
            
            randomHeights = new float[60];
            for(int i=0; i<60; i++) randomHeights[i] = (float) (0.2 + Math.random() * 0.8); // Random wave look
            
            setOnTouchListener((v, e) -> {
                if(mediaPlayer != null && (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE)) {
                    float touchX = e.getX(); float newP = touchX / getWidth();
                    if(newP < 0) newP = 0; if(newP > 1) newP = 1;
                    mediaPlayer.seekTo((int)(newP * mediaPlayer.getDuration())); setProgress(newP); return true;
                } return false;
            });
        }
        public void setProgress(float p) { this.progress = p; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int bars = 60; float spacing = getWidth() / (float) bars; int centerY = getHeight() / 2;
            for (int i = 0; i < bars; i++) {
                float x = i * spacing + (spacing / 2);
                float h = randomHeights[i] * (getHeight() - 20);
                Paint p = (x / getWidth() <= progress) ? paintPlayed : paintUnplayed;
                canvas.drawLine(x, centerY - h / 2, x, centerY + h / 2, p);
            }
        }
    }
}