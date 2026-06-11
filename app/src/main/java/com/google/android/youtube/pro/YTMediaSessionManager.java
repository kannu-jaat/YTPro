package com.google.android.youtube.pro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;

public class YTMediaSessionManager {

    private static final String CHANNEL_ID = "yt_media_channel";
    private static final int NOTIFICATION_ID = 2002;

    public static final String ACTION_YT_PLAY = "YT_PLAY";
    public static final String ACTION_YT_PAUSE = "YT_PAUSE";
    public static final String ACTION_YT_NEXT = "YT_NEXT";
    public static final String ACTION_YT_PREV = "YT_PREV";
    public static final String ACTION_YT_CLOSE = "YT_CLOSE";

    private Context context;
    private NotificationManager notificationManager;
    private MediaSession mediaSession;
    private BroadcastReceiver actionReceiver;
    private YTActionCallback callback;

    public interface YTActionCallback {
        void onPlay();
        void onPause();
        void onNext();
        void onPrev();
        void onClose();
    }

    public YTMediaSessionManager(Context context, YTActionCallback callback) {
        this.context = context;
        this.callback = callback;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        createChannel();
        initMediaSession();
        registerReceiver();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "YouTube Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void initMediaSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession = new MediaSession(context, "YT_SESSION");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            
            // 🛠️ FIX: BLUETOOTH & HARDWARE BUTTON INTERCEPTOR
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() { if(callback != null) callback.onPlay(); }
                @Override
                public void onPause() { if(callback != null) callback.onPause(); }
                @Override
                public void onSkipToNext() { if(callback != null) callback.onNext(); }
                @Override
                public void onSkipToPrevious() { if(callback != null) callback.onPrev(); }
            });

            mediaSession.setActive(true);
        }
    }

    public void updateNotification(boolean isPlaying, String title, String artist, Bitmap art) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

        PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS);
        stateBuilder.setState(isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f);
        mediaSession.setPlaybackState(stateBuilder.build());

        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
        if (art != null) {
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        }
        mediaSession.setMetadata(metadataBuilder.build());

        int pendingFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : 
                PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent playPI = PendingIntent.getBroadcast(context, 1, new Intent(ACTION_YT_PLAY), pendingFlag);
        PendingIntent pausePI = PendingIntent.getBroadcast(context, 2, new Intent(ACTION_YT_PAUSE), pendingFlag);
        PendingIntent nextPI = PendingIntent.getBroadcast(context, 3, new Intent(ACTION_YT_NEXT), pendingFlag);
        PendingIntent prevPI = PendingIntent.getBroadcast(context, 4, new Intent(ACTION_YT_PREV), pendingFlag);
        PendingIntent closePI = PendingIntent.getBroadcast(context, 5, new Intent(ACTION_YT_CLOSE), pendingFlag);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 
                new Notification.Builder(context, CHANNEL_ID) : new Notification.Builder(context);

        builder.setSmallIcon(android.R.drawable.ic_media_play)
               .setContentTitle(title)
               .setContentText(artist)
               .setLargeIcon(art)
               .setOngoing(isPlaying) 
               .setDeleteIntent(closePI)
               .setVisibility(Notification.VISIBILITY_PUBLIC);

        builder.addAction(android.R.drawable.ic_media_previous, "Previous", prevPI);
        if (isPlaying) {
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePI);
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "Play", playPI);
        }
        builder.addAction(android.R.drawable.ic_media_next, "Next", nextPI);
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", closePI);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void registerReceiver() {
        actionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (callback == null) return;
                String action = intent.getAction();
                if (ACTION_YT_PLAY.equals(action)) callback.onPlay();
                else if (ACTION_YT_PAUSE.equals(action)) callback.onPause();
                else if (ACTION_YT_NEXT.equals(action)) callback.onNext();
                else if (ACTION_YT_PREV.equals(action)) callback.onPrev();
                else if (ACTION_YT_CLOSE.equals(action)) {
                    callback.onClose();
                    cancelNotification();
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_YT_PLAY);
        filter.addAction(ACTION_YT_PAUSE);
        filter.addAction(ACTION_YT_NEXT);
        filter.addAction(ACTION_YT_PREV);
        filter.addAction(ACTION_YT_CLOSE);

        if (Build.VERSION.SDK_INT >= 34 && context.getApplicationInfo().targetSdkVersion >= 34) {
            context.registerReceiver(actionReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(actionReceiver, filter);
        }
    }

    public void cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID);
        if (mediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession.setActive(false);
        }
    }

    public void destroy() {
        cancelNotification();
        if (actionReceiver != null) {
            try {
                context.unregisterReceiver(actionReceiver);
            } catch (Exception e) {}
        }
        if (mediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession.release();
        }
    }
}