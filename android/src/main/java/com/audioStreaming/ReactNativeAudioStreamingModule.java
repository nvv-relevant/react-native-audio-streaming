package com.audioStreaming;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.util.ArrayList;

import javax.annotation.Nullable;

public class ReactNativeAudioStreamingModule extends ReactContextBaseJavaModule
        implements ServiceConnection {

    public static final String SHOULD_SHOW_NOTIFICATION = "showInAndroidNotifications";
    private final FirebaseAnalytics firebaseAnalytics;
    private ReactApplicationContext context;
    private Class<?> clsActivity;
    private static Signal signal;
    private Intent bindIntent;
    private boolean shouldShowNotification;
    public boolean statePlayOnFocus = false;
    private int currentStation = 1;

    public ReactNativeAudioStreamingModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.context = reactContext;
        firebaseAnalytics = FirebaseAnalytics.getInstance(reactContext.getApplicationContext());
    }

    public ReactApplicationContext getReactApplicationContextModule() {
        return this.context;
    }

    public Class<?> getClassActivity() {
        if (this.clsActivity == null) {
            if (getCurrentActivity() != null) {
                this.clsActivity = getCurrentActivity().getClass();
            }
        }
        return this.clsActivity;
    }

    public void stopOnFocus() { // When phone call received or initiated
        if (shouldShowNotification) {
            this.destroyNotification();
        }

        if (signal != null) {
            statePlayOnFocus = signal.isPlaying;
            signal.stop();
        }
    }

    public Signal getSignal() {
        return signal;
    }

    public void sendEvent(ReactContext reactContext, String eventName, @Nullable WritableMap params) {
        //Log.d("sendEvent",eventName);
        this.context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    @Override
    public String getName() {
        return "ReactNativeAudioStreaming";
    }

    @Override
    public void initialize() {
        super.initialize();

        try {
            bindIntent = new Intent(this.context, Signal.class);
            this.context.startService(bindIntent);
            this.context.bindService(bindIntent, this, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e("ERROR", e.getMessage());
        }
    }

    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
        signal = ((Signal.RadioBinder) service).getService();
        signal.setData(this.context, this);
        WritableMap params = Arguments.createMap();
        sendEvent(this.getReactApplicationContextModule(), "streamingOpen", params);
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
        Log.d("onServiceDisconnected", "Service disconnected");
        if (shouldShowNotification) {
            this.destroyNotification();
        }
        signal = null;
    }

    @ReactMethod
    public void sendFireBaseEvent(String eventName) {
        Log.d("Analytics", eventName);
        firebaseAnalytics.logEvent(eventName, null);
    }

    private AudioManager.OnAudioFocusChangeListener focusChangeListener =
            new AudioManager.OnAudioFocusChangeListener() {
                public void onAudioFocusChange(int focusChange) {
                    AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                    switch (focusChange) {
                        case (AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK):
                        case (AudioManager.AUDIOFOCUS_LOSS_TRANSIENT):
                        case (AudioManager.AUDIOFOCUS_LOSS):
                            stopOnFocus();
                            break;

                        case (AudioManager.AUDIOFOCUS_GAIN):
                            restartPlayback();
                            break;
                        default:
                            break;
                    }
                }
            };


    @ReactMethod
    public void play(ReadableArray streams, int playIndex, String metaUrl, ReadableMap options) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int amResult = am.requestAudioFocus(focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);

        if (amResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            this.shouldShowNotification = options.hasKey(SHOULD_SHOW_NOTIFICATION) && options.getBoolean(SHOULD_SHOW_NOTIFICATION);
            String stationId = options.hasKey("stationId") ? options.getString("stationId") : "";

            ArrayList<Signal.Stream> nativeStreams = new ArrayList<>();
            for (int i = 0; i < streams.size(); i++) {
                ReadableMap map = streams.getMap(i);
                Signal.Stream stream = new Signal.Stream(map.getString("url"), map.getInt("bitrate"));
                nativeStreams.add(stream);
            }
            if(signal != null) {
                signal.setURLStreaming(nativeStreams, playIndex, metaUrl, stationId);
            }

            statePlayOnFocus = true;
            playInternal();
        }
    }

    // Made for PhoneListener to restart playback after phone call
    public void restartPlayback() {
        if (statePlayOnFocus) {
            playInternal();
        }
    }


    private void playInternal() {
        if(signal == null) {
            return;
        }
        signal.play();

        signal.showNotification();

    }

    @ReactMethod
    public void stop() {
        if(signal == null) {
            return;
        }

        Log.d("STOP", "User pressed STOP!");
        statePlayOnFocus = false;

        signal.stop();
        destroyNotification();
    }

    @ReactMethod
    public void pause() {
        if(signal == null) {
            return;
        }
        // Not implemented on aac
        signal.stop();

        statePlayOnFocus = false;
    }

    @ReactMethod
    public void resume() {
        // Not implemented on aac
        playInternal();
    }

    // more.fm special method
    @ReactMethod
    public void showTextNotification(String text) {
        signal.showTextNotification(text);
    }

    @ReactMethod
    public void nowPlayInfo(String coverURL, String channel, String text) {
        if (signal != null) {
            signal.nowPlayInfo(coverURL, channel, text);
        }
    }

    @ReactMethod
    public void setCurrentStation(int currentStation) {
        this.currentStation = currentStation;
    }

    @ReactMethod
    public void destroyNotification() {
        if(signal != null) {
            signal.exitNotification();
        }
    }

    @ReactMethod
    public void getStatus(Callback callback) {
        WritableMap state = Arguments.createMap();
        state.putString("status", signal != null && signal.isPlaying ? Mode.PLAYING : Mode.STOPPED);
        state.putInt("currentStation", currentStation);
        state.putInt("index", currentStation);

        callback.invoke(null, state);
        if (signal != null) {
            signal.sendNowPlayingEvent();
        }
    }

}
