package com.google.android.youtube.pro.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.youtube.pro.webview.YTProWebView;

public class MediaCommandReceiver extends BroadcastReceiver {
    private final YTProWebView web;

    // DJ States - yeh track karenge ki kaunsa deck chal raha hai aur fader kahan hai
    private boolean leftPlaying = false;
    private boolean rightPlaying = false;
    private boolean faderLeft = false;
    private boolean faderRight = false;

    public MediaCommandReceiver(YTProWebView web) {
        this.web = web;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Naye system me action direct aayega
        String action = intent.getAction();
        
        // Agar purane system se koi kachra aata hai toh usko bhi pakad lenge
        if (action == null && intent.getExtras() != null) {
            action = intent.getExtras().getString("actionname");
        }
        if (action == null) return;

        Log.e("DJ_Action", "Panel Button Clicked: " + action);

        // 1. JavaScript Commands (Website ko bhejna)
        if (action.equals("LEFT_TOGGLE")) {
            leftPlaying = !leftPlaying; // Toggle karna (On ko Off, Off ko On)
            web.evaluateJavascript(leftPlaying ? "playDeck('left');" : "pauseDeck('left');", null);
        } 
        else if (action.equals("RIGHT_TOGGLE")) {
            rightPlaying = !rightPlaying;
            web.evaluateJavascript(rightPlaying ? "playDeck('right');" : "pauseDeck('right');", null);
        } 
        else if (action.equals("XFADER_LEFT")) {
            web.evaluateJavascript("fadeTo(0);", null);
            faderLeft = true;
            faderRight = false;
        } 
        else if (action.equals("XFADER_RIGHT")) {
            web.evaluateJavascript("fadeTo(100);", null);
            faderLeft = false;
            faderRight = true;
        }

        // 2. ForegroundService ko Notification UI (Icons/Text) update karne ka order dena
        Intent updateIntent = new Intent("UPDATE_DJ_NOTIF");
        updateIntent.putExtra("lPlay", leftPlaying);
        updateIntent.putExtra("rPlay", rightPlaying);
        updateIntent.putExtra("fLeft", faderLeft);
        updateIntent.putExtra("fRight", faderRight);
        
        context.sendBroadcast(updateIntent);
    }
}
