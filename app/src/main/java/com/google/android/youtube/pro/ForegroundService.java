package com.google.android.youtube.pro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;

public class ForegroundService extends Service {

    public static final String CHANNEL_ID = "dj_channel";
    
    // Naye DJ Actions
    public static final String LEFT_TOGGLE = "LEFT_TOGGLE";
    public static final String RIGHT_TOGGLE = "RIGHT_TOGGLE";
    public static final String XFADER_LEFT = "XFADER_LEFT";
    public static final String XFADER_RIGHT = "XFADER_RIGHT";

    // Update Notification receiver ke liye custom action
    public static final String ACTION_UPDATE_DJ_NOTIF = "UPDATE_DJ_NOTIF";

    private NotificationManager notificationManager;
    private BroadcastReceiver djUpdateReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        registerDJUpdateReceiver();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "DJ Controls",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Shuru me default states bhejenge (sab false)
        buildAndShowNotification(false, false, false, false);
        return START_NOT_STICKY;
    }

    // 🔥 Naya Core Function jo Notification banayega aur update karega
    private void buildAndShowNotification(boolean leftPlaying, boolean rightPlaying, boolean faderLeft, boolean faderRight) {
        
        int pendingFlag = Build.VERSION.SDK_INT >= 23 ? 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : 
                PendingIntent.FLAG_UPDATE_CURRENT;

        // Pending Intents for DJ Buttons
        PendingIntent leftPI = PendingIntent.getBroadcast(this, 1, new Intent(LEFT_TOGGLE), pendingFlag);
        PendingIntent rightPI = PendingIntent.getBroadcast(this, 2, new Intent(RIGHT_TOGGLE), pendingFlag);
        PendingIntent xfLeftPI = PendingIntent.getBroadcast(this, 3, new Intent(XFADER_LEFT), pendingFlag);
        PendingIntent xfRightPI = PendingIntent.getBroadcast(this, 4, new Intent(XFADER_RIGHT), pendingFlag);

        // App kholne ka intent
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, pendingFlag);

        String faderStatus = "Center ⚖️";
        if (faderLeft) faderStatus = "⬅️ Left";
        if (faderRight) faderStatus = "Right ➡️";

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? 
                new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);

        builder.setVisibility(Notification.VISIBILITY_PUBLIC)
                .setSmallIcon(android.R.drawable.ic_media_play) // Chhota DJ Icon
                .setContentTitle("KK Mixer Active")
                .setContentText("Fader: " + faderStatus)
                .setContentIntent(openAppPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        // Sketchware wala custom Neon Green Color
        if (Build.VERSION.SDK_INT >= 21) {
            builder.setColor(Color.parseColor("#34d399"));
        }

        // Dynamic 4 Buttons add karna
        builder.addAction(
            leftPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, 
            "L", leftPI
        );
        builder.addAction(
            rightPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, 
            "R", rightPI
        );
        builder.addAction(
            faderLeft ? android.R.drawable.ic_media_previous : android.R.drawable.ic_media_rew, 
            "◀", xfLeftPI
        );
        builder.addAction(
            faderRight ? android.R.drawable.ic_media_next : android.R.drawable.ic_media_ff, 
            "▶", xfRightPI
        );

        if (Build.VERSION.SDK_INT >= 24) {
            builder.setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 3));
        }

        Notification notification = builder.build();
        
        // Ensure it runs in foreground
        startForeground(999, notification);
    }

    // Yeh Receiver MainActivity/MediaCommandReceiver se naye states sunega
    private void registerDJUpdateReceiver() {
        djUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_UPDATE_DJ_NOTIF.equals(intent.getAction())) {
                    boolean lPlay = intent.getBooleanExtra("lPlay", false);
                    boolean rPlay = intent.getBooleanExtra("rPlay", false);
                    boolean fLeft = intent.getBooleanExtra("fLeft", false);
                    boolean fRight = intent.getBooleanExtra("fRight", false);
                    
                    buildAndShowNotification(lPlay, rPlay, fLeft, fRight);
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_UPDATE_DJ_NOTIF);
        if (Build.VERSION.SDK_INT >= 34 && getApplicationInfo().targetSdkVersion >= 34) {
            registerReceiver(djUpdateReceiver, filter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(djUpdateReceiver, filter);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (djUpdateReceiver != null) {
            unregisterReceiver(djUpdateReceiver);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
