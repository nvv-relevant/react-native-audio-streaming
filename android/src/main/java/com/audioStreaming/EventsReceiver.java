package com.audioStreaming;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;

import java.io.*;
import android.util.Log;


public class EventsReceiver extends BroadcastReceiver {
    private ReactNativeAudioStreamingModule module;

    public EventsReceiver(ReactNativeAudioStreamingModule module) {
        this.module = module;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        WritableMap params = Arguments.createMap();
        params.putString("status", intent.getAction());

        if (intent.getAction().equals(Mode.METADATA_UPDATED))
        {
            params.putString("stationId", intent.getStringExtra("stationId"));
            params.putString("key", intent.getStringExtra("key"));
            params.putString("value", intent.getStringExtra("value"));
        } else if(intent.getAction().equals(Mode.ADAPTIVE_TRACK_CHANGED)) {
            params.putInt("bitrate", intent.getIntExtra("bitrate", 128000));
        }
        this.module.sendEvent(this.module.getReactApplicationContextModule(), "AudioBridgeEvent", params);
    }

}
