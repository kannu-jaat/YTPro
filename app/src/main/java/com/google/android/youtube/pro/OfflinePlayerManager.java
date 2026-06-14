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
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
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

    // Media Player
    private MediaPlayer mediaPlayer;
    private ArrayList<AudioTrack> trackList;
    private int currentTrackIndex = -1;
    private boolean isPlaying = false;

    // Mini Player UI
    private TextView mpTitle, mpArtist;
    private Button mpPlayPauseBtn;
    private HeartbeatSeekBar heartbeatSeekBar;
    private Handler progressHandler = new Handler(Looper.getMainLooper());

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
        this.prefs = context.getSharedPreferences("DJ_OFFLINE_PREFS", Context.MODE_PRIVATE);
        this.trackList = new ArrayList<>();
        initUI();
    }

    // 🔥 THE GLASSMORPHISM & NEON UI ENGINE
    private void initUI() {
        mainUI = new FrameLayout(context);
        mainUI.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        mainUI.setBackgroundColor(android.graphics.Color.parseColor("#0B0F19")); // Deep Dark Theme
        mainUI.setVisibility(View.GONE);

        LinearLayout verticalLayout = new LinearLayout(context);
        verticalLayout.setOrientation(LinearLayout.VERTICAL);
        verticalLayout.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        // 🛡️ HEADER: Title & Blocked Folders Mgr
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(40, 40, 40, 20);
        header.setGravity(Gravity.CENTER_VERTICAL);
        
        TextView title = new TextView(context);
        title.setText("🎵 Local Library");
        title.setTextColor(android.graphics.Color.WHITE);
        title.setTextSize(22f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        header.addView(title, tParams);

        Button btnUnblock = new Button(context);
        btnUnblock.setText("🛡️ Unblock");
        btnUnblock.setTextColor(android.graphics.Color.parseColor("#A78BFA")); // Purple Tint
        btnUnblock.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btnUnblock.setOnClickListener(v -> manageBlockedFolders());
        header.addView(btnUnblock);

        // 🎶 LIST VIEW (Track List)
        listView = new ListView(context);
        listView.setDivider(null);
        listView.setPadding(20, 0, 20, 200); // Bottom padding for mini player
        listView.setClipToPadding(false);
        LinearLayout.LayoutParams lParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        
        // 🎛️ MINI PLAYER (Bottom Fixed)
        setupMiniPlayer();

        verticalLayout.addView(header);
        verticalLayout.addView(listView, lParams);
        
        mainUI.addView(verticalLayout);
        mainUI.addView(miniPlayer); // Add mini player on top of everything at bottom
        parentContainer.addView(mainUI);
    }

    private void setupMiniPlayer() {
        miniPlayer = new LinearLayout(context);
        miniPlayer.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams mParams = new FrameLayout.LayoutParams(-1, -2);
        mParams.gravity = Gravity.BOTTOM;
        mParams.setMargins(30, 0, 30, 30);
        miniPlayer.setLayoutParams(mParams);

        // Glassmorphic Background for Mini Player
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(android.graphics.Color.parseColor("#20FFFFFF")); // 12% White
        bg.setCornerRadius(40f);
        bg.setStroke(2, android.graphics.Color.parseColor("#30FFFFFF"));
        miniPlayer.setBackground(bg);
        miniPlayer.setPadding(40, 30, 40, 30);

        // Info Row
        LinearLayout infoRow = new LinearLayout(context);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        mpTitle = new TextView(context);
        mpTitle.setText("Not Playing");
        mpTitle.setTextColor(android.graphics.Color.WHITE);
        mpTitle.setTextSize(16f);
        mpTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        mpArtist = new TextView(context);
        mpArtist.setText("--");
        mpArtist.setTextColor(android.graphics.Color.LTGRAY);
        mpArtist.setTextSize(12f);
        textLayout.addView(mpTitle);
        textLayout.addView(mpArtist);
        LinearLayout.LayoutParams txtParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        infoRow.addView(textLayout, txtParams);

        // Controls
        Button btnPrev = createMiniBtn("⏮️");
        btnPrev.setOnClickListener(v -> playPrev());
        mpPlayPauseBtn = createMiniBtn("▶️");
        mpPlayPauseBtn.setOnClickListener(v -> togglePlayPause());
        Button btnNext = createMiniBtn("⏭️");
        btnNext.setOnClickListener(v -> playNext());

        infoRow.addView(btnPrev);
        infoRow.addView(mpPlayPauseBtn);
        infoRow.addView(btnNext);

        // 💓 HEARTBEAT SEEKBAR (Sci-Fi)
        heartbeatSeekBar = new HeartbeatSeekBar(context);
        LinearLayout.LayoutParams hbParams = new LinearLayout.LayoutParams(-1, 60);
        hbParams.setMargins(0, 20, 0, 0);
        
        miniPlayer.addView(infoRow);
        miniPlayer.addView(heartbeatSeekBar, hbParams);
    }

    private Button createMiniBtn(String icon) {
        Button b = new Button(context);
        b.setText(icon);
        b.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        b.setTextSize(20f);
        b.setPadding(10, 0, 10, 0);
        return b;
    }

    public void toggleVisibility() {
        if (mainUI.getVisibility() == View.VISIBLE) {
            mainUI.setVisibility(View.GONE);
        } else {
            mainUI.setVisibility(View.VISIBLE);
            loadAudioFiles(); // Refresh list when opened
        }
    }

    // 🚫 SMART FOLDER BLOCKER LOGIC
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
                        if (duration > 30000 && !blockedFolders.contains(folderName)) {
                            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                            trackList.add(new AudioTrack(id, title, artist, path, folderName, uri, duration));
                        }
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) { e.printStackTrace(); }

        updateListView();
    }

    private void updateListView() {
        ArrayAdapter<AudioTrack> adapter = new ArrayAdapter<AudioTrack>(context, android.R.layout.simple_list_item_1, trackList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                if (convertView == null) {
                    row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setPadding(20, 20, 20, 20);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    
                    // Glassmorphism Row Background
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(android.graphics.Color.parseColor("#151E2E"));
                    bg.setCornerRadius(25f);
                    row.setBackground(bg);
                    
                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                    rowParams.setMargins(0, 0, 0, 15);
                    row.setLayoutParams(rowParams);

                    // Track Info
                    LinearLayout textLayout = new LinearLayout(context);
                    textLayout.setOrientation(LinearLayout.VERTICAL);
                    TextView tName = new TextView(context);
                    tName.setId(View.generateViewId());
                    tName.setTextColor(android.graphics.Color.WHITE);
                    tName.setTextSize(15f);
                    tName.setMaxLines(1);
                    tName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    
                    TextView tFolder = new TextView(context);
                    tFolder.setId(View.generateViewId());
                    tFolder.setTextColor(android.graphics.Color.GRAY);
                    tFolder.setTextSize(11f);
                    
                    textLayout.addView(tName);
                    textLayout.addView(tFolder);
                    LinearLayout.LayoutParams txtParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
                    row.addView(textLayout, txtParams);

                    // 🎛️ The 3 Master Buttons
                    Button btnPlay = createRowBtn("▶️", "#34D399"); // Neon Green
                    Button btnLeft = createRowBtn("L", "#A78BFA");  // Purple
                    Button btnRight = createRowBtn("R", "#38BDF8"); // Blue

                    row.addView(btnPlay);
                    row.addView(btnLeft);
                    row.addView(btnRight);
                    
                    row.setTag(new View[]{tName, tFolder, btnPlay, btnLeft, btnRight});
                } else {
                    row = (LinearLayout) convertView;
                }

                View[] views = (View[]) row.getTag();
                TextView tName = (TextView) views[0];
                TextView tFolder = (TextView) views[1];
                Button btnPlay = (Button) views[2];
                Button btnLeft = (Button) views[3];
                Button btnRight = (Button) views[4];

                AudioTrack track = getItem(position);
                tName.setText(track.title != null ? track.title : "Unknown Track");
                tFolder.setText("📁 " + track.folder);

                // Play Audio in App
                btnPlay.setOnClickListener(v -> playTrack(position));
                
                // Load to Deck L/R
                btnLeft.setOnClickListener(v -> {
                    if(deckListener != null) deckListener.onLoadToDeck("left", track.uri);
                    Toast.makeText(context, "Loaded to L Deck 🎧", Toast.LENGTH_SHORT).show();
                });
                btnRight.setOnClickListener(v -> {
                    if(deckListener != null) deckListener.onLoadToDeck("right", track.uri);
                    Toast.makeText(context, "Loaded to R Deck 🎛️", Toast.LENGTH_SHORT).show();
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

    private Button createRowBtn(String text, String colorHex) {
        Button b = new Button(context);
        b.setText(text);
        b.setTextColor(android.graphics.Color.BLACK);
        b.setTextSize(12f);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(android.graphics.Color.parseColor(colorHex));
        bg.setCornerRadius(15f);
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(90, 90);
        p.setMargins(10, 0, 0, 0);
        b.setLayoutParams(p);
        return b;
    }

    private void showBlockDialog(String folder) {
        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Block Folder?")
            .setMessage("Hide all audios from '" + folder + "'?")
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
            Toast.makeText(context, "No folders blocked.", Toast.LENGTH_SHORT).show();
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

    // 🔄 MEDIA PLAYER ENGINE & AUTO-NEXT
    private void playTrack(int index) {
        if (index < 0 || index >= trackList.size()) return;
        currentTrackIndex = index;
        AudioTrack track = trackList.get(index);

        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
            // AUTO-NEXT LOGIC
            mediaPlayer.setOnCompletionListener(mp -> playNext());
        } else {
            mediaPlayer.reset();
        }

        try {
            mediaPlayer.setDataSource(context, track.uri);
            mediaPlayer.prepare();
            mediaPlayer.start();
            isPlaying = true;
            mpPlayPauseBtn.setText("⏸️");
            mpTitle.setText(track.title);
            mpArtist.setText(track.artist != null ? track.artist : "Local File");
            startProgressUpdater();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            mpPlayPauseBtn.setText("▶️");
        } else {
            mediaPlayer.start();
            isPlaying = true;
            mpPlayPauseBtn.setText("⏸️");
            startProgressUpdater();
        }
    }

    private void playNext() {
        if (trackList.isEmpty()) return;
        int next = currentTrackIndex + 1;
        if (next >= trackList.size()) next = 0; // Loop back
        playTrack(next);
    }

    private void playPrev() {
        if (trackList.isEmpty()) return;
        int prev = currentTrackIndex - 1;
        if (prev < 0) prev = trackList.size() - 1;
        playTrack(prev);
    }

    private void startProgressUpdater() {
        progressHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && isPlaying) {
                    float progress = (float) mediaPlayer.getCurrentPosition() / mediaPlayer.getDuration();
                    heartbeatSeekBar.setProgress(progress);
                    progressHandler.postDelayed(this, 100); // 10fps smooth update
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

    // 💓 CUSTOM HEARTBEAT SEEKBAR (Sci-Fi Design)
    class HeartbeatSeekBar extends View {
        private Paint paintPlayed, paintUnplayed;
        private float progress = 0f;

        public HeartbeatSeekBar(Context context) {
            super(context);
            paintPlayed = new Paint();
            paintPlayed.setColor(android.graphics.Color.parseColor("#34D399")); // Neon Green
            paintPlayed.setStrokeWidth(6f);
            paintPlayed.setStrokeCap(Paint.Cap.ROUND);

            paintUnplayed = new Paint();
            paintUnplayed.setColor(android.graphics.Color.parseColor("#444444")); // Dark Gray
            paintUnplayed.setStrokeWidth(6f);
            paintUnplayed.setStrokeCap(Paint.Cap.ROUND);
            
            // Allow user to seek by touching
            setOnTouchListener((v, event) -> {
                if(mediaPlayer != null && event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                    float touchX = event.getX();
                    float newProgress = touchX / getWidth();
                    if(newProgress < 0) newProgress = 0;
                    if(newProgress > 1) newProgress = 1;
                    mediaPlayer.seekTo((int)(newProgress * mediaPlayer.getDuration()));
                    setProgress(newProgress);
                    return true;
                }
                return false;
            });
        }

        public void setProgress(float p) {
            this.progress = p;
            invalidate(); // Redraw
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            int bars = 40; // Number of heartbeat lines
            float spacing = width / (float) bars;
            int centerY = height / 2;

            for (int i = 0; i < bars; i++) {
                float x = i * spacing + (spacing / 2);
                // Create random-looking heights for waveform effect
                float barHeight = (float) (10 + Math.abs(Math.sin(i * 0.5)) * (height - 20));
                
                Paint p = (x / width <= progress) ? paintPlayed : paintUnplayed;
                canvas.drawLine(x, centerY - barHeight / 2, x, centerY + barHeight / 2, p);
            }
        }
    }
}