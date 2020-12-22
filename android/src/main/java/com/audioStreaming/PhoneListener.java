package com.audioStreaming;

import android.content.Intent;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

public class PhoneListener extends PhoneStateListener {

    private ReactNativeAudioStreamingModule module;

    public PhoneListener(ReactNativeAudioStreamingModule module) {
        this.module = module;
    }

    @Override
    public void onCallStateChanged(int state, String incomingNumber) {
        Intent restart;

        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE:
                //CALL_STATE_IDLE;
                //restart = new Intent(this.module.getReactApplicationContextModule(), this.module.getClassActivity());
                //restart.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                //this.module.getReactApplicationContextModule().startActivity(restart);

            //    this.module.restartPlayback();

                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                // Device call state: Off-hook. At least one call exists that is dialing, active, or on hold, and no calls are ringing or waiting.
                //CALL_STATE_OFFHOOK;
                //restart = new Intent(this.module.getReactApplicationContextModule(), this.module.getClassActivity());
                //restart.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                //this.module.getReactApplicationContextModule().startActivity(restart);

           //     this.module.stopOncall();
                break;
            case TelephonyManager.CALL_STATE_RINGING:

           //     this.module.stopOncall();
                break;
            default:
                break;
        }
        super.onCallStateChanged(state, incomingNumber);
    }
}
