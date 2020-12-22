package com.audioStreaming;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

class SignalReceiver extends BroadcastReceiver {
    private Signal signal;

    public SignalReceiver(Signal signal) {
        super();
        this.signal = signal;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if(action == null) {
            return;
        }
        if (action.equals(Signal.BROADCAST_PLAYBACK_PLAY)) {
            if (!this.signal.isPlaying) {
                this.signal.play();
                this.signal.sendFireBaseEvent("click_play");
            } else {
                this.signal.stop();
                this.signal.sendFireBaseEvent("click_pause");
            }
        }  else  if (action.equals(Signal.BROADCAST_PLAYBACK_STOP)) {
            if (this.signal.isPlaying) {
                this.signal.stop();
                this.signal.sendFireBaseEvent("click_pause");
            }
        }
        else if (action.equals(Signal.BROADCAST_EXIT)) {
            if (this.signal.getNotifyManager() != null) {
                this.signal.getNotifyManager().cancelAll();
            }
            this.signal.stopByBroadcastExit();
            this.signal.exitNotification();
        }
    }
}
