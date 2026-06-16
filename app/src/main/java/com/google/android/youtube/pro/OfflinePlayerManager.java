package com.google.android.youtube.pro;

import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button; // 🛠️ FIX 1: Missing Import Added
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
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
    private WavyMiniPlayer miniPlayer;
    private SharedPreferences prefs;

    // Media & Data
    private MediaPlayer mediaPlayer;
    private ArrayList<AudioTrack> allTracksList;
    private ArrayList<AudioTrack> displayList;
    private ArrayAdapter<AudioTrack> adapter;
    private int currentTrackIndex = -1;
    private boolean isPlaying = false;
    private String currentSortMode = "Recent"; 

    // Mini Player UI
    private TextView mpTitle, mpArtist, tvCurrentTime, tvTotalTime;
    private DrawnIconBtn btnPlayPause;
    private WaveformSeekBar waveformSeekBar;
    private Handler progressHandler = new Handler(Looper.getMainLooper());

    // 🛠️ FIX 2: Renamed back to DJDeckListener
    private DJDeckListener deckListener;

    public interface DJDeckListener {
        void onLoadToDeck(String deck, Uri fileUri);
    }

    static class AudioTrack {
        long id; String title; String artist; String path; String folder; Uri uri; long duration;
        AudioTrack(long id, String t, String a, String p, String f, Uri u, long d) {
            this.id = id; title = t; artist = a; path = p; folder = f; uri = u; duration = d;
        }
    }

    public OfflinePlayerManager(Context context, FrameLayout parentContainer, DJDeckListener listener) {
        this.context = context;
        this.parentContainer = parentContainer;
        this.deckListener = listener;
        this.prefs = context.getSharedPreferences("LITE_PLAYER_PREFS", Context.MODE_PRIVATE);
        this.allTracksList = new ArrayList<>();
        this.displayList = new ArrayList<>();
        initUI();
    }

    private void initUI() {
        mainUI = new FrameLayout(context);
        mainUI.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        mainUI.setBackgroundColor(Color.parseColor("#050505")); 
        mainUI.setVisibility(View.GONE);

        LinearLayout verticalLayout = new LinearLayout(context);
        verticalLayout.setOrientation(LinearLayout.VERTICAL);
        verticalLayout.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        verticalLayout.setPadding(30, 40, 30, 0);

        // --- TOP HEADER ---
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        
        LinearLayout titleBox = new LinearLayout(context);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("Lite Player");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView subTitle = new TextView(context);
        subTitle.setText("Feel the music");
        subTitle.setTextColor(Color.parseColor("#888888"));
        subTitle.setTextSize(12f);
        titleBox.addView(title); titleBox.addView(subTitle);
        LinearLayout.LayoutParams tParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        header.addView(titleBox, tParams);

        DrawnIconBtn btnSettings = new DrawnIconBtn(context, "settings", "#FFFFFF");
        btnSettings.setOnClickListener(v -> openSettingsDialog());
        header.addView(btnSettings);

        // --- SEARCH BAR ---
        LinearLayout searchBox = new LinearLayout(context);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(Color.parseColor("#151515"));
        searchBg.setCornerRadius(50f);
        searchBox.setBackground(searchBg);
        searchBox.setPadding(30, 10, 30, 10);
        LinearLayout.LayoutParams sParams = new LinearLayout.LayoutParams(-1, 120);
        sParams.setMargins(0, 40, 0, 40);
        searchBox.setLayoutParams(sParams);

        DrawnIconBtn searchIcon = new DrawnIconBtn(context, "search", "#888888");
        EditText etSearch = new EditText(context);
        etSearch.setHint("Search audio...");
        etSearch.setHintTextColor(Color.parseColor("#888888"));
        etSearch.setTextColor(Color.WHITE);
        etSearch.setBackgroundColor(Color.TRANSPARENT);
        etSearch.setTextSize(16f);
        etSearch.setMaxLines(1);
        etSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(0, -1, 1.0f);
        etParams.setMargins(20, 0, 20, 0);

        DrawnIconBtn filterIcon = new DrawnIconBtn(context, "filter", "#CCFF00");

        searchBox.addView(searchIcon);
        searchBox.addView(etSearch, etParams);
        searchBox.addView(filterIcon);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterTracks(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        // --- SORTING HEADER ---
        LinearLayout sortHeader = new LinearLayout(context);
        sortHeader.setOrientation(LinearLayout.HORIZONTAL);
        sortHeader.setGravity(Gravity.CENTER_VERTICAL);
        sortHeader.setPadding(0, 0, 0, 20);

        TextView allAudioTxt = new TextView(context);
        allAudioTxt.setText("All Audio");
        allAudioTxt.setTextColor(Color.WHITE);
        allAudioTxt.setTextSize(18f);
        allAudioTxt.setTypeface(null, android.graphics.Typeface.BOLD);
        
        TextView sortTxt = new TextView(context);
        sortTxt.setText("Recently Added ˅");
        sortTxt.setTextColor(Color.parseColor("#CCFF00"));
        sortTxt.setTextSize(14f);
        sortTxt.setGravity(Gravity.RIGHT);
        sortTxt.setOnClickListener(v -> openSortDialog(sortTxt));
        
        sortHeader.addView(allAudioTxt, new LinearLayout.LayoutParams(0, -2, 1.0f));
        sortHeader.addView(sortTxt);

        // --- LIST VIEW ---
        listView = new ListView(context);
        listView.setDivider(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, 350); 
        setupListViewAdapter();

        verticalLayout.addView(header);
        verticalLayout.addView(searchBox);
        verticalLayout.addView(sortHeader);
        verticalLayout.addView(listView, new LinearLayout.LayoutParams(-1, -1));
        
        // --- WAVY MINI PLAYER ---
        setupWavyMiniPlayer();

        mainUI.addView(verticalLayout);
        mainUI.addView(miniPlayer);
        parentContainer.addView(mainUI);
    }

    private void setupWavyMiniPlayer() {
        miniPlayer = new WavyMiniPlayer(context);
        FrameLayout.LayoutParams mParams = new FrameLayout.LayoutParams(-1, 320); 
        mParams.gravity = Gravity.BOTTOM;
        miniPlayer.setLayoutParams(mParams);
        miniPlayer.setPadding(40, 60, 40, 30); 

        LinearLayout vBox = new LinearLayout(context);
        vBox.setOrientation(LinearLayout.VERTICAL);

        LinearLayout infoRow = new LinearLayout(context);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        
        FrameLayout artBox = new FrameLayout(context);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setColor(Color.parseColor("#222222"));
        artBg.setCornerRadius(20f);
        artBox.setBackground(artBg);
        artBox.setLayoutParams(new LinearLayout.LayoutParams(100, 100));

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        textLayout.setPadding(30, 0, 0, 0);
        mpTitle = new TextView(context);
        mpTitle.setText("Not Playing");
        mpTitle.setTextColor(Color.WHITE);
        mpTitle.setTextSize(16f);
        mpTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        mpArtist = new TextView(context);
        mpArtist.setText("--");
        mpArtist.setTextColor(Color.parseColor("#888888"));
        mpArtist.setTextSize(12f);
        textLayout.addView(mpTitle);
        textLayout.addView(mpArtist);
        
        DrawnIconBtn eqIcon = new DrawnIconBtn(context, "eq", "#CCFF00");

        infoRow.addView(artBox);
        infoRow.addView(textLayout, new LinearLayout.LayoutParams(0, -2, 1.0f));
        infoRow.addView(eqIcon);

        waveformSeekBar = new WaveformSeekBar(context);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(-1, 60);
        waveParams.setMargins(0, 30, 0, 10);

        LinearLayout timeRow = new LinearLayout(context);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        tvCurrentTime = new TextView(context); tvCurrentTime.setText("0:00"); tvCurrentTime.setTextColor(Color.parseColor("#CCFF00")); tvCurrentTime.setTextSize(10f);
        tvTotalTime = new TextView(context); tvTotalTime.setText("0:00"); tvTotalTime.setTextColor(Color.parseColor("#888888")); tvTotalTime.setTextSize(10f); tvTotalTime.setGravity(Gravity.RIGHT);
        timeRow.addView(tvCurrentTime, new LinearLayout.LayoutParams(0, -2, 1.0f));
        timeRow.addView(tvTotalTime, new LinearLayout.LayoutParams(0, -2, 1.0f));

        LinearLayout controlsRow = new LinearLayout(context);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER);
        controlsRow.setPadding(0, 10, 0, 0);

        DrawnIconBtn btnShuffle = new DrawnIconBtn(context, "shuffle", "#CCFF00");
        DrawnIconBtn btnPrev = new DrawnIconBtn(context, "prev", "#FFFFFF");
        
        btnPlayPause = new DrawnIconBtn(context, "play", "#CCFF00");
        btnPlayPause.setDrawCircle(true);
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        
        DrawnIconBtn btnNext = new DrawnIconBtn(context, "next", "#FFFFFF");
        DrawnIconBtn btnRepeat = new DrawnIconBtn(context, "repeat", "#CCFF00");

        btnPrev.setOnClickListener(v -> playPrev());
        btnNext.setOnClickListener(v -> playNext());

        LinearLayout.LayoutParams cParams = new LinearLayout.LayoutParams(0, 100, 1.0f);
        controlsRow.addView(btnShuffle, cParams);
        controlsRow.addView(btnPrev, cParams);
        controlsRow.addView(btnPlayPause, new LinearLayout.LayoutParams(140, 140)); 
        controlsRow.addView(btnNext, cParams);
        controlsRow.addView(btnRepeat, cParams);

        vBox.addView(infoRow);
        vBox.addView(waveformSeekBar, waveParams);
        vBox.addView(timeRow);
        vBox.addView(controlsRow);
        
        miniPlayer.addView(vBox);
    }

    private void setupListViewAdapter() {
        adapter = new ArrayAdapter<AudioTrack>(context, 0, displayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                if (convertView == null) {
                    row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(Color.parseColor("#111111")); 
                    bg.setCornerRadius(40f);
                    row.setBackground(bg);
                    row.setPadding(30, 20, 20, 20);
                    
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                    rp.setMargins(0, 0, 0, 25);
                    row.setLayoutParams(rp);

                    FrameLayout artBox = new FrameLayout(context);
                    GradientDrawable artBg = new GradientDrawable();
                    artBg.setColor(Color.parseColor("#222222"));
                    artBg.setCornerRadius(20f);
                    artBox.setBackground(artBg);
                    artBox.setLayoutParams(new LinearLayout.LayoutParams(110, 110));

                    LinearLayout txtBox = new LinearLayout(context);
                    txtBox.setOrientation(LinearLayout.VERTICAL);
                    txtBox.setPadding(30, 0, 10, 0);
                    
                    TextView tName = new TextView(context);
                    tName.setId(View.generateViewId());
                    tName.setTextColor(Color.WHITE);
                    tName.setTextSize(15f);
                    tName.setMaxLines(1);
                    tName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    
                    TextView tArtist = new TextView(context);
                    tArtist.setId(View.generateViewId());
                    tArtist.setTextColor(Color.parseColor("#888888"));
                    tArtist.setTextSize(12f);
                    tArtist.setMaxLines(1);
                    
                    txtBox.addView(tName);
                    txtBox.addView(tArtist);

                    TextView tDur = new TextView(context);
                    tDur.setId(View.generateViewId());
                    tDur.setTextColor(Color.parseColor("#888888"));
                    tDur.setTextSize(12f);
                    
                    DrawnIconBtn btnDots = new DrawnIconBtn(context, "dots", "#FFFFFF");
                    btnDots.setLayoutParams(new LinearLayout.LayoutParams(80, 80));

                    row.addView(artBox);
                    row.addView(txtBox, new LinearLayout.LayoutParams(0, -2, 1.0f));
                    row.addView(tDur);
                    row.addView(btnDots);
                    
                    row.setTag(new View[]{tName, tArtist, tDur, btnDots});
                } else {
                    row = (LinearLayout) convertView;
                }

                View[] views = (View[]) row.getTag();
                TextView tName = (TextView) views[0];
                TextView tArtist = (TextView) views[1];
                TextView tDur = (TextView) views[2];
                DrawnIconBtn btnDots = (DrawnIconBtn) views[3];

                AudioTrack track = getItem(position);
                tName.setText(track.title);
                tArtist.setText(track.artist != null ? track.artist : track.folder);
                tDur.setText(formatTime(track.duration));

                row.setOnClickListener(v -> playTrack(track));
                btnDots.setOnClickListener(v -> openTrackMenu(track));

                return row;
            }
        };
        listView.setAdapter(adapter);
    }

    public void toggleVisibility() {
        if (mainUI.getVisibility() == View.VISIBLE) {
            mainUI.setVisibility(View.GONE);
        } else {
            mainUI.setVisibility(View.VISIBLE);
            loadAudioFiles(); 
        }
    }

    private void filterTracks(String query) {
        displayList.clear();
        if (query.trim().isEmpty()) {
            displayList.addAll(allTracksList);
        } else {
            String q = query.toLowerCase();
            for (AudioTrack t : allTracksList) {
                if (t.title.toLowerCase().contains(q) || (t.artist != null && t.artist.toLowerCase().contains(q))) {
                    displayList.add(t);
                }
            }
        }
        applySorting();
        adapter.notifyDataSetChanged();
    }

    private void loadAudioFiles() {
        allTracksList.clear();
        displayList.clear();
        Set<String> blockedFolders = prefs.getStringSet("BLOCKED_FOLDERS", new HashSet<>());
        Set<String> blockedAudios = prefs.getStringSet("BLOCKED_AUDIOS", new HashSet<>());
        int minDuration = prefs.getInt("MIN_DURATION_SEC", 10) * 1000; 

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
                        
                        if (duration >= minDuration && !blockedFolders.contains(folderName) && !blockedAudios.contains(String.valueOf(id))) {
                            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                            AudioTrack t = new AudioTrack(id, title, artist, path, folderName, uri, duration);
                            allTracksList.add(t);
                        }
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) { e.printStackTrace(); }

        displayList.addAll(allTracksList);
        applySorting();
        adapter.notifyDataSetChanged();
    }

    private void openSortDialog(TextView sortBtn) {
        String[] options = {"Recently Added", "Name (A-Z)", "Duration"};
        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sort By")
            .setItems(options, (d, w) -> {
                if(w == 0) currentSortMode = "Recent";
                else if(w == 1) currentSortMode = "Name";
                else if(w == 2) currentSortMode = "Duration";
                sortBtn.setText(options[w] + " ˅");
                applySorting();
                adapter.notifyDataSetChanged();
            }).show();
    }

    private void applySorting() {
        if (currentSortMode.equals("Name")) {
            Collections.sort(displayList, (a, b) -> a.title.compareToIgnoreCase(b.title));
        } else if (currentSortMode.equals("Duration")) {
            Collections.sort(displayList, (a, b) -> Long.compare(b.duration, a.duration));
        } else {
            Collections.sort(displayList, (a, b) -> Long.compare(b.id, a.id));
        }
    }

    private void openSettingsDialog() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);

        TextView t1 = new TextView(context); t1.setText("Hide audio shorter than (seconds):"); t1.setTextColor(Color.WHITE);
        EditText etMin = new EditText(context);
        etMin.setInputType(InputType.TYPE_CLASS_NUMBER);
        etMin.setText(String.valueOf(prefs.getInt("MIN_DURATION_SEC", 10)));
        etMin.setTextColor(Color.parseColor("#CCFF00"));
        
        Button btnUnblockFolder = new Button(context); btnUnblockFolder.setText("Manage Blocked Folders");
        btnUnblockFolder.setOnClickListener(v -> unblockItem("BLOCKED_FOLDERS", "Folders"));
        
        Button btnUnblockAudio = new Button(context); btnUnblockAudio.setText("Manage Blocked Audios");
        btnUnblockAudio.setOnClickListener(v -> unblockItem("BLOCKED_AUDIOS", "Audios"));

        layout.addView(t1); layout.addView(etMin); layout.addView(btnUnblockFolder); layout.addView(btnUnblockAudio);

        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("⚙️ Settings")
            .setView(layout)
            .setPositiveButton("Save", (d, w) -> {
                try {
                    int sec = Integer.parseInt(etMin.getText().toString());
                    prefs.edit().putInt("MIN_DURATION_SEC", sec).apply();
                    loadAudioFiles();
                } catch(Exception e){}
            }).show();
    }

    private void unblockItem(String prefKey, String title) {
        Set<String> blocked = prefs.getStringSet(prefKey, new HashSet<>());
        if(blocked.isEmpty()){ Toast.makeText(context, "Nothing blocked here.", Toast.LENGTH_SHORT).show(); return; }
        String[] items = blocked.toArray(new String[0]);
        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Unblock " + title)
            .setItems(items, (d, w) -> {
                Set<String> updated = new HashSet<>(blocked);
                updated.remove(items[w]);
                prefs.edit().putStringSet(prefKey, updated).apply();
                loadAudioFiles();
                Toast.makeText(context, "Unblocked!", Toast.LENGTH_SHORT).show();
            }).show();
    }

    // 🛠️ FIX 3: Updated to match MainActivity Deck Listener Names
    private void openTrackMenu(AudioTrack track) {
        String[] options = {"🎧 Load to Deck A (Left)", "🎛️ Load to Deck B (Right)", "🚫 Block this Audio", "📁 Block entire Folder: " + track.folder};
        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(track.title)
            .setItems(options, (d, w) -> {
                if (w == 0) {
                    if(deckListener != null) deckListener.onLoadToDeck("left", track.uri);
                    Toast.makeText(context, "Loaded to L Deck 🎧", Toast.LENGTH_SHORT).show();
                } else if (w == 1) {
                    if(deckListener != null) deckListener.onLoadToDeck("right", track.uri);
                    Toast.makeText(context, "Loaded to R Deck 🎛️", Toast.LENGTH_SHORT).show();
                } else if (w == 2) {
                    Set<String> b = new HashSet<>(prefs.getStringSet("BLOCKED_AUDIOS", new HashSet<>()));
                    b.add(String.valueOf(track.id));
                    prefs.edit().putStringSet("BLOCKED_AUDIOS", b).apply();
                    loadAudioFiles();
                } else if (w == 3) {
                    Set<String> b = new HashSet<>(prefs.getStringSet("BLOCKED_FOLDERS", new HashSet<>()));
                    b.add(track.folder);
                    prefs.edit().putStringSet("BLOCKED_FOLDERS", b).apply();
                    loadAudioFiles();
                }
            }).show();
    }

    private void playTrack(AudioTrack track) {
        currentTrackIndex = displayList.indexOf(track);
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setOnCompletionListener(mp -> playNext()); 
        } else {
            mediaPlayer.reset();
        }
        try {
            mediaPlayer.setDataSource(context, track.uri);
            mediaPlayer.prepare();
            mediaPlayer.start();
            isPlaying = true;
            btnPlayPause.setIcon("pause"); 
            mpTitle.setText(track.title);
            mpArtist.setText(track.artist != null ? track.artist : "Unknown");
            tvTotalTime.setText(formatTime(track.duration));
            startProgressUpdater();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            btnPlayPause.setIcon("play");
        } else {
            mediaPlayer.start();
            isPlaying = true;
            btnPlayPause.setIcon("pause");
            startProgressUpdater();
        }
    }

    private void playNext() {
        if (displayList.isEmpty()) return;
        int next = currentTrackIndex + 1;
        if (next >= displayList.size()) next = 0;
        playTrack(displayList.get(next));
    }

    private void playPrev() {
        if (displayList.isEmpty()) return;
        int prev = currentTrackIndex - 1;
        if (prev < 0) prev = displayList.size() - 1;
        playTrack(displayList.get(prev));
    }

    private void startProgressUpdater() {
        progressHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && isPlaying) {
                    int curr = mediaPlayer.getCurrentPosition();
                    tvCurrentTime.setText(formatTime(curr));
                    float progress = (float) curr / mediaPlayer.getDuration();
                    waveformSeekBar.setProgress(progress);
                    progressHandler.postDelayed(this, 100); 
                }
            }
        });
    }

    public void onDestroy() {
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        progressHandler.removeCallbacksAndMessages(null);
    }

    private String formatTime(long ms) {
        long sec = (ms / 1000) % 60;
        long min = (ms / 1000) / 60;
        return String.format(Locale.US, "%d:%02d", min, sec);
    }

    // ==========================================
    // 🎨 CUSTOM DRAWN CLASSES
    // ==========================================

    class WavyMiniPlayer extends FrameLayout {
        private Paint paint, strokePaint;
        private Path path;

        public WavyMiniPlayer(Context context) {
            super(context);
            setWillNotDraw(false);
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.parseColor("#151515")); 
            paint.setAlpha(240); 

            strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setColor(Color.parseColor("#34D399")); 
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(3f);
            strokePaint.setShadowLayer(15f, 0, -5, Color.parseColor("#34D399")); 
            setLayerType(LAYER_TYPE_SOFTWARE, strokePaint); 
            
            path = new Path();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(); int h = getHeight();
            path.reset();
            path.moveTo(0, 50);
            path.cubicTo(w * 0.25f, 0, w * 0.4f, 80, w * 0.6f, 60);
            path.cubicTo(w * 0.8f, 40, w * 0.9f, 20, w, 40);
            path.lineTo(w, h); path.lineTo(0, h); path.close();

            canvas.drawPath(path, paint);
            canvas.drawPath(path, strokePaint); 
            super.onDraw(canvas);
        }
    }

    class WaveformSeekBar extends View {
        private Paint paintPlayed, paintUnplayed;
        private float progress = 0f;
        private float[] randomHeights; 

        public WaveformSeekBar(Context context) {
            super(context);
            paintPlayed = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintPlayed.setColor(Color.parseColor("#CCFF00")); 
            paintPlayed.setStrokeWidth(6f);
            paintPlayed.setStrokeCap(Paint.Cap.ROUND);

            paintUnplayed = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintUnplayed.setColor(Color.parseColor("#444444")); 
            paintUnplayed.setStrokeWidth(6f);
            paintUnplayed.setStrokeCap(Paint.Cap.ROUND);

            randomHeights = new float[50];
            for(int i=0; i<50; i++) randomHeights[i] = (float)(Math.random() * 0.8 + 0.2); 
            
            setOnTouchListener((v, e) -> {
                if(mediaPlayer != null && (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE)) {
                    float p = e.getX() / getWidth();
                    if(p < 0) p = 0; if(p > 1) p = 1;
                    mediaPlayer.seekTo((int)(p * mediaPlayer.getDuration()));
                    setProgress(p);
                    return true;
                }
                return false;
            });
        }

        public void setProgress(float p) { this.progress = p; invalidate(); }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(); int h = getHeight(); int bars = 50;
            float spacing = w / (float)bars; int cy = h / 2;

            for (int i = 0; i < bars; i++) {
                float x = i * spacing + (spacing / 2f);
                float barH = randomHeights[i] * h;
                Paint p = (x / w <= progress) ? paintPlayed : paintUnplayed;
                if (Math.abs((x/w) - progress) < 0.02f) barH = h; 
                canvas.drawLine(x, cy - barH/2, x, cy + barH/2, p);
            }
        }
    }

    class DrawnIconBtn extends View {
        private String type;
        private Paint paint;
        private boolean drawCircle = false;

        public DrawnIconBtn(Context context, String type, String hexColor) {
            super(context);
            this.type = type;
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.parseColor(hexColor));
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        public void setIcon(String newType) { this.type = newType; invalidate(); }
        public void setDrawCircle(boolean b) { this.drawCircle = b; }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(); int h = getHeight();
            int cx = w/2; int cy = h/2;
            int size = Math.min(w, h) / 3;

            if (drawCircle) {
                Paint circleP = new Paint(Paint.ANTI_ALIAS_FLAG);
                circleP.setColor(paint.getColor()); circleP.setStyle(Paint.Style.STROKE); circleP.setStrokeWidth(4f);
                canvas.drawCircle(cx, cy, Math.min(w,h)/2f - 5f, circleP);
            }

            Path path = new Path();
            if (type.equals("play")) {
                path.moveTo(cx - size/2f, cy - size); path.lineTo(cx + size, cy); path.lineTo(cx - size/2f, cy + size); path.close();
                canvas.drawPath(path, paint);
            } else if (type.equals("pause")) {
                canvas.drawRoundRect(new RectF(cx - size, cy - size, cx - size/3f, cy + size), 5, 5, paint);
                canvas.drawRoundRect(new RectF(cx + size/3f, cy - size, cx + size, cy + size), 5, 5, paint);
            } else if (type.equals("next")) {
                path.moveTo(cx - size, cy - size/1.5f); path.lineTo(cx, cy); path.lineTo(cx - size, cy + size/1.5f); path.close();
                canvas.drawPath(path, paint);
                canvas.drawRect(cx + size/3f, cy - size/1.5f, cx + size/1.5f, cy + size/1.5f, paint);
            } else if (type.equals("prev")) {
                path.moveTo(cx + size, cy - size/1.5f); path.lineTo(cx, cy); path.lineTo(cx + size, cy + size/1.5f); path.close();
                canvas.drawPath(path, paint);
                canvas.drawRect(cx - size/1.5f, cy - size/1.5f, cx - size/3f, cy + size/1.5f, paint);
            } else if (type.equals("dots")) {
                canvas.drawCircle(cx, cy - size/1.2f, size/4f, paint);
                canvas.drawCircle(cx, cy, size/4f, paint);
                canvas.drawCircle(cx, cy + size/1.2f, size/4f, paint);
            } else if (type.equals("settings")) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5f);
                canvas.drawCircle(cx, cy, size/1.5f, paint);
                for(int i=0; i<8; i++) {
                    canvas.drawLine(cx, cy - size/1.2f, cx, cy - size*1.2f, paint);
                    canvas.rotate(45, cx, cy);
                }
                paint.setStyle(Paint.Style.FILL);
            } else if (type.equals("search")) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5f);
                canvas.drawCircle(cx - size/4f, cy - size/4f, size/1.5f, paint);
                canvas.drawLine(cx + size/4f, cy + size/4f, cx + size, cy + size, paint);
                paint.setStyle(Paint.Style.FILL);
            } else if (type.equals("filter")) {
                paint.setStrokeWidth(4f);
                canvas.drawLine(cx - size, cy - size/2f, cx + size, cy - size/2f, paint);
                canvas.drawCircle(cx - size/2f, cy - size/2f, size/3f, paint);
                canvas.drawLine(cx - size, cy + size/2f, cx + size, cy + size/2f, paint);
                canvas.drawCircle(cx + size/2f, cy + size/2f, size/3f, paint);
            } else if (type.equals("eq")) { 
                paint.setStrokeWidth(6f); paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(cx - size/1.5f, cy + size, cx - size/1.5f, cy + size/3f, paint);
                canvas.drawLine(cx, cy + size, cx, cy - size/1.5f, paint);
                canvas.drawLine(cx + size/1.5f, cy + size, cx + size/1.5f, cy, paint);
            } else if (type.equals("shuffle") || type.equals("repeat")) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4f);
                canvas.drawRoundRect(new RectF(cx - size, cy - size/2f, cx + size, cy + size/2f), 10, 10, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }
}