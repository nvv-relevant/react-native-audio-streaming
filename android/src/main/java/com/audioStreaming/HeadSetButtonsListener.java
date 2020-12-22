package com.audioStreaming;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.view.KeyEvent;

public class HeadSetButtonsListener extends BroadcastReceiver {
    static int clicks = 0;
    static long lastClickTime = 0;
    static final int TIMEOUT = 500;
    static final int CLICK_TIMEOUT = 100;

    public HeadSetButtonsListener() {
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        String intentAction = intent.getAction();
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intentAction)) {
            return;
        }
        KeyEvent event = (KeyEvent) intent
                .getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null) {
            return;
        }

        int action = event.getAction();
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (action == KeyEvent.ACTION_DOWN) {
                    clicks++;
                    Handler handler = new Handler();
                    Runnable r = new Runnable() {
                        @Override
                        public void run() {
                            if (clicks == 1) {
                                context.sendBroadcast(new Intent(Signal.BROADCAST_HEADSET_PLAY));
                            }
                            if (clicks == 2) {
                                context.sendBroadcast(new Intent(Signal.BROADCAST_PLAYBACK_NEXT));
                            }
                            clicks = 0;
                        }
                    };
                    if (clicks == 1) {
                        handler.postDelayed(r, TIMEOUT);
                    }
                }
                break;
        }

        long now = System.currentTimeMillis();
        if(now - lastClickTime < TIMEOUT) {
            return;
        }
        lastClickTime = now;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                context.sendBroadcast(new Intent(Signal.BROADCAST_HEADSET_PLAY));
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                context.sendBroadcast(new Intent(Signal.BROADCAST_PLAYBACK_STOP));
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                context.sendBroadcast(new Intent(Signal.BROADCAST_PLAYBACK_NEXT));
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                context.sendBroadcast(new Intent(Signal.BROADCAST_PLAYBACK_PREV));
                break;
        }


    }
}
