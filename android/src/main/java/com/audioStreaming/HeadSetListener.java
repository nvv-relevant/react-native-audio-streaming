package com.audioStreaming;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

public class HeadSetListener extends BroadcastReceiver {

    private ReactNativeAudioStreamingModule module;

    public HeadSetListener(ReactNativeAudioStreamingModule module) {
        this.module = module;
    }

    public HeadSetListener() {
    }

    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
            int state = intent.getIntExtra("state", -1);
            switch (state) {
                case 0:
                    module.pause();
                    break;
            }
        }

        if (action.equals(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)) {
            final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, BluetoothAdapter.ERROR);
            switch (state) {
                case BluetoothAdapter.STATE_DISCONNECTED:
                case BluetoothAdapter.STATE_DISCONNECTING:
                    module.pause();
                    break;
            }
        }
    }
}
