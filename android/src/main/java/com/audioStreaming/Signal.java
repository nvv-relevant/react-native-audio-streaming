package com.audioStreaming;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import com.audioStreaming.ExoPlayer.IcyMetaData;
import com.audioStreaming.ExoPlayer.Player;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.squareup.picasso.Picasso;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Map;


public class Signal extends Service implements Player.Callback {
    private static final String AVRCP_PLAYSTATE_CHANGED = "com.android.music.playstatechanged";
    private static final String AVRCP_META_CHANGED = "com.android.music.metachanged";

    public static final int ADAPTIVE_MODE = -1;

    private static final long TIMER_UPDATE_META = 2000L;
    // Notification
    private static final int NOTIFY_ME_ID = 696969;
    private Notification.Builder notifyBuilder;
    private NotificationManager notifyManager = null;
    private Player player;
    private FirebaseAnalytics firebaseAnalytics;

    public static class Stream {
        public String url;
        public int bitrate;

        public Stream(String url, int bitrate) {
            this.url = url;
            this.bitrate = bitrate;
        }
    }

    public static final String BROADCAST_PLAYBACK_STOP = "stop",
            BROADCAST_PLAYBACK_PLAY = "pause",
            BROADCAST_PLAYBACK_NEXT = "NEXT",
            BROADCAST_PLAYBACK_PREV = "PREVIOUS",
            BROADCAST_HEADSET_PLAY = "HEADSET_PLAY",
            BROADCAST_EXIT = "exit";

    private final IBinder binder = new RadioBinder();
    private final SignalReceiver receiver = new SignalReceiver(this);
    private Context context = this;

    public boolean isPlaying = false;
    private boolean isPreparingStarted = false;
    private EventsReceiver eventsReceiver;
    private ReactNativeAudioStreamingModule module;

    private HeadSetListener headSetListener;

    private boolean isButtonStopped = false;
    public String coverURL;
    public String channel;
    public String text;
    private Handler handler = new Handler();
    private String metaUrl;
    private String stationId;
    private ArrayList<Stream> streams = new ArrayList<>();
    private int playingStreamIndex = 0;
    RemoteControlClient remoteControlClient;
    private long currentChannelStartPlaying = 0;

    public void setData(Context context, ReactNativeAudioStreamingModule module) {
        this.context = context;
        this.module = module;

        this.eventsReceiver = new EventsReceiver(this.module);


        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.CREATED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.DESTROYED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.STARTED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.CONNECTING));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.START_PREPARING));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.PREPARED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.PLAYING));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.STOPPED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.COMPLETED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.ERROR));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.BUFFERING_START));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.BUFFERING_END));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.METADATA_UPDATED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.ADAPTIVE_TRACK_CHANGED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.ALBUM_UPDATED));
        registerReceiver(this.eventsReceiver, new IntentFilter(Mode.RETRYING));
        registerReceiver(this.eventsReceiver, new IntentFilter(BROADCAST_PLAYBACK_PREV));
        registerReceiver(this.eventsReceiver, new IntentFilter(BROADCAST_PLAYBACK_NEXT));
        registerReceiver(this.eventsReceiver, new IntentFilter(BROADCAST_HEADSET_PLAY));

        registerReceiver(this.metaReceiver, new IntentFilter(Mode.METADATA_UPDATED));

        String action;

        if (Build.VERSION.SDK_INT >= 21) {
            action = AudioManager.ACTION_HEADSET_PLUG;
        } else {
            action = Intent.ACTION_HEADSET_PLUG;
        }

        IntentFilter intentFilter = new IntentFilter(action);
        intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        headSetListener = new HeadSetListener(this.module);
        registerReceiver(headSetListener, intentFilter);


        ComponentName componentName = new ComponentName(getPackageName(), HeadSetButtonsListener.class.getName());
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.registerMediaButtonEventReceiver(componentName);

        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(componentName);
        PendingIntent mediaPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, mediaButtonIntent, 0);

        // create and register the remote control client
        remoteControlClient = new RemoteControlClient(mediaPendingIntent);
        audioManager.registerRemoteControlClient(remoteControlClient);
    }

    BroadcastReceiver metaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null && intent.getAction().equals(Mode.METADATA_UPDATED)) {
                String id = intent.getStringExtra("stationId");
                if(id == null || stationId == null || id.equalsIgnoreCase(stationId)) {
                    playerMetadata(intent.getStringExtra("key"), intent.getStringExtra("value"));
                }
            }
        }
    };


    public void sendNowPlayingEvent() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Map<String, String> lastOne = IcyMetaData.cachedMeta;
                if (lastOne == null) {
                    return;
                }
                for (String key : lastOne.keySet()) {
                    Intent metaIntent = new Intent(Mode.METADATA_UPDATED);
                    String value = lastOne.get(key);
                    metaIntent.putExtra("key", key);
                    metaIntent.putExtra("value", value);
                    sendBroadcast(metaIntent);
                }
            }
        }, 1000);
    }

    @Override
    public void onCreate() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BROADCAST_PLAYBACK_STOP);
        intentFilter.addAction(BROADCAST_PLAYBACK_PLAY);
        intentFilter.addAction(BROADCAST_EXIT);
        registerReceiver(this.receiver, intentFilter);

        firebaseAnalytics = FirebaseAnalytics.getInstance(this.getApplicationContext());

        try {
            player = new Player();
            player.init(this, this);
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }


        this.notifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        sendBroadcast(new Intent(Mode.CREATED));
    }

    public void sendFireBaseEvent(String eventName) {
        Log.d("Analytics", eventName);
        firebaseAnalytics.logEvent(eventName, null);
    }

    public void sendFirebaseDurationEvent(String stationName, int duration) {
        Bundle bundle = new Bundle();
        bundle.putInt(stationName, duration);
        firebaseAnalytics.logEvent("time_listening", bundle);

        Log.d("Analytics", "time_listening " + stationName + " " + duration);
    }

    public void setURLStreaming(ArrayList<Stream> streams, int playIndex, String metaUrl, String stationId) {
        this.metaUrl = metaUrl;
        this.stationId = stationId;

        if (!this.streams.isEmpty()) {
            maybeResetCache(streams);
        }

        this.streams = streams;
        this.playingStreamIndex = playIndex;
        scheduleMetadataUpdate();
    }

    private void maybeResetCache(ArrayList<Stream> streams) {
        boolean reset = false;
        for (Stream s : this.streams) {
            boolean found = false;
            for (Stream s1 : streams) {
                if (s.url.equals(s1.url)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                reset = true;
                break;
            }
        }
        if (reset) {
            resetCache();
        }
    }

    public void play() {
        if (isConnected()) {
            prepare();
            player.play();
            isPlaying = true;
            isButtonStopped = false;
            scheduleMetadataUpdate();
        } else {
            sendBroadcast(new Intent(Mode.RETRYING));
            handler.removeCallbacks(metadataRunnable);
            handler.postDelayed(playRunnable, 1000);
        }
        currentChannelStartPlaying = System.currentTimeMillis();
    }

    public Runnable playRunnable = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(playRunnable);
            play();
        }
    };

    private void scheduleMetadataUpdate() {
        handler.removeCallbacks(metadataRunnable);
        handler.postDelayed(metadataRunnable, 1000);
    }

    private Runnable metadataRunnable = new Runnable() {
        @Override
        public void run() {
            new MetadataTask(new WeakReference<>(Signal.this), stationId).execute();

            handler.removeCallbacks(metadataRunnable);
            handler.postDelayed(metadataRunnable, TIMER_UPDATE_META);
        }
    };

    public String getMetaUrl() {
        return metaUrl;
    }


    protected static class MetadataTask extends AsyncTask<URL, Void, Map<String, String>> {
        private WeakReference<Signal> signal;
        private String stationId;

        public MetadataTask(WeakReference<Signal> signal, String stationId) {
            this.signal = signal;
            this.stationId = stationId;
        }

        @Override
        protected Map<String, String> doInBackground(URL... urls) {
            if (signal.get() == null) {
                return null;
            }
            for (int i = 0; i < 3; i++) {
                try {
                    loadMeta();
                    return null;
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if(isCancelled()) {
                    return null;
                }
            }

            return null;
        }

        private boolean loadMeta() throws IOException {
            IcyMetaData icyMetaData = new IcyMetaData();

            Map<String, String> metadata = icyMetaData.loadMeta(signal.get().metaUrl);
            if (metadata == null) {
                return false;
            }

            Map<String, String> lastOne = IcyMetaData.cachedMeta;

            for (String key : metadata.keySet()) {
                Intent metaIntent = new Intent(Mode.METADATA_UPDATED);
                String value = metadata.get(key);
                if (lastOne == null || lastOne.get(key) == null || !lastOne.get(key).equals(value)) {
                    metaIntent.putExtra("key", key);
                    metaIntent.putExtra("value", value);
                    if(isCancelled()) {
                        return true;
                    }
                    metaIntent.putExtra("stationId", stationId);
                    signal.get().sendBroadcast(metaIntent);
                }
            }

            IcyMetaData.cachedMeta = metadata;
            return true;
        }
    }

    public static void stop(Context context) {
        Intent i = new Intent();
        i.setAction("stopself");
        i.setClass(context, Signal.class);
        ContextCompat.startForegroundService(context, i);
    }
    public void stop() {

        //Log.d("Stop","Stopped by user");
        this.isPreparingStarted = false;
        this.isButtonStopped = true;

        if (this.isPlaying) {
            this.isPlaying = false; // Это будет выставлено позже, в callback playerStopped.
            this.player.stop();
            handler.removeCallbacks(playRunnable);
        }
        resetCache();

        stopForeground(false);
        if(currentChannelStartPlaying > 0) {
            sendFirebaseDurationEvent(stationId, (int) ((System.currentTimeMillis() - currentChannelStartPlaying) / 1000));
            currentChannelStartPlaying = 0;
        }
        // sendBroadcast(new Intent(Mode.STOPPED)); // Это тоже.
    }

    private void resetCache() {
        coverURL = null;
        text = null;
        channel = null;

        IcyMetaData.cachedMeta = null;
    }

    public void stopByBroadcastExit() {
        Log.d("Stop", "Stopped by broadcast exit signal");
        this.module.statePlayOnFocus = false;
        this.stop();
        handler.removeCallbacks(playRunnable);
        handler.removeCallbacks(metadataRunnable);
    }

    public NotificationManager getNotifyManager() {
        return notifyManager;
    }

    public class RadioBinder extends Binder {
        public Signal getService() {
            return Signal.this;
        }
    }

    class UpdateNowPlayingRunnable implements Runnable {
        private String coverURL;
        private String channel;
        private String text;

        public void setData(String coverURL, String channel, String text) {
            this.coverURL = coverURL;
            this.channel = channel;
            this.text = text;
        }

        @Override
        public void run() {
            nowPlayInfoInternal(coverURL, channel, text);
        }
    }

    private UpdateNowPlayingRunnable updateNoPlayingRunnable = new UpdateNowPlayingRunnable();

    public void nowPlayInfo(String coverURL, String channel, String text) {
        handler.removeCallbacks(updateNoPlayingRunnable);
        updateNoPlayingRunnable.setData(coverURL, channel, text);
        handler.postDelayed(updateNoPlayingRunnable, 100);
    }

    private void notifySystemMetaData(String channel, String text) {
        String[] arr = text.split(" - ");
        String artist = "";
        String song = text;
        if (arr.length > 1) {
            artist = arr[0];
            song = arr[1];
        }

        bluetoothNotifyChange(AVRCP_PLAYSTATE_CHANGED, artist, song, channel);
        bluetoothNotifyChange(AVRCP_META_CHANGED, artist, song, channel);


        RemoteControlClient.MetadataEditor ed = remoteControlClient.editMetadata(true);
        ed.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, song);
        ed.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, channel);
        ed.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, artist);
        ed.putString(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, artist);
        ed.apply();
    }

    private void bluetoothNotifyChange(String action, String artist, String song, String channel) {
        Intent i = new Intent(action);

        i.putExtra("artist", artist);
        i.putExtra("album", channel);
        i.putExtra("track", song);

        sendBroadcast(i);
    }

    public void nowPlayInfoInternal(String coverURL, String channel, String text) {
        if (coverURL == null || channel == null || text == null) {
            return;
        }

        final RemoteViews remoteViews = getNotificationRemoteViews();

        boolean changed = false;

        if (this.coverURL == null || !this.coverURL.equals(coverURL)) {
            this.coverURL = coverURL;
            changed = true;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        URL url = new URL(Signal.this.coverURL);
                        URI uri = new URI(url.getProtocol(), url.getUserInfo(), url.getHost(), url.getPort(), url.getPath(), url.getQuery(), url.getRef());

                        Picasso.with(Signal.this).
                                load(uri.toString()).
                                resize(128, 128).
                                into(remoteViews, R.id.streaming_icon, NOTIFY_ME_ID, notifyBuilder.build());
                    } catch (URISyntaxException e) {
                        e.printStackTrace();
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        if (!text.equals("") && (this.text == null || !text.equals(this.text))) {
            this.text = text;
            changed = true;
            remoteViews.setTextViewText(R.id.song_name_notification, text);
        }
        if (!channel.equals("") && (this.channel == null || !channel.equals(this.channel))) {
            this.channel = channel;
            changed = true;
            remoteViews.setTextViewText(R.id.album_notification, channel);
        }

        if (notifyBuilder != null && changed) {
            notifyBuilder.setContent(remoteViews);
            startForeground(NOTIFY_ME_ID, notifyBuilder.build());
        } else if (notifyBuilder == null) {
            resetCache();
        }
    }

    public void showTextNotification(String text) {
        if (text == null) {
            return;
        }
        RemoteViews remoteViews = getNotificationRemoteViews();
        remoteViews.setTextViewText(R.id.song_name_notification, text);

        if (notifyBuilder != null && isPlaying) {
            notifyBuilder.setContent(remoteViews);
            startForeground(NOTIFY_ME_ID, notifyBuilder.build());
        }
    }

    public void showNotification() {
        RemoteViews remoteViews = getNotificationRemoteViews();
        notifyBuilder = new Notification.Builder(this.context)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off) // TODO gUse app icon instead
                .setContentText("")
                .setOngoing(false)
                .setContent(remoteViews);

        Intent resultIntent = new Intent(this.context, module.getClassActivity());
        resultIntent.setAction(Intent.ACTION_MAIN);
        resultIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        resultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent resultPendingIntent = PendingIntent.getActivity(this, 0, resultIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        notifyBuilder.setContentIntent(resultPendingIntent);
        // remoteViews.setOnClickPendingIntent(R.id.btn_streaming_notification_play, makePendingIntent(BROADCAST_PLAYBACK_PLAY));
        // remoteViews.setOnClickPendingIntent(R.id.btn_streaming_notification_stop, makePendingIntent(BROADCAST_EXIT));
        remoteViews.setOnClickPendingIntent(R.id.btn_streaming_notification_pause, makePendingIntent(BROADCAST_PLAYBACK_PLAY));
        remoteViews.setOnClickPendingIntent(R.id.btn_streaming_notification_next, makePendingIntent(BROADCAST_PLAYBACK_NEXT));
        remoteViews.setOnClickPendingIntent(R.id.btn_streaming_notification_prev, makePendingIntent(BROADCAST_PLAYBACK_PREV));

        notifyBuilder.setDeleteIntent(makePendingIntent(BROADCAST_EXIT));

        notifyManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        //notifyManager.notify(NOTIFY_ME_ID, notifyBuilder.build());

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    new NotificationChannel("com.audioStreaming", "Audio Streaming", NotificationManager.IMPORTANCE_DEFAULT);
            if (notifyManager != null) {
                notifyManager.createNotificationChannel(channel);
            }

            notifyBuilder.setChannelId("com.audioStreaming");
            notifyBuilder.setOnlyAlertOnce(true);
        }

        Notification notification = notifyBuilder.build();
        startForeground(NOTIFY_ME_ID, notification);

        // TODO foreground service and stop foreground service.

        nowPlayInfo(coverURL, channel, text);
    }


    private PendingIntent makePendingIntent(String broadcast) {
        Intent intent = new Intent(broadcast);
        return PendingIntent.getBroadcast(this.context, 0, intent, 0);
    }

    public void clearNotification() {
        stopForeground(true);
    }


    public void exitNotification() {
        if (notifyManager != null) {
            notifyManager.cancelAll();
            clearNotification();
            notifyBuilder = null;
            notifyManager = null;
        }
    }

    public boolean isConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        if (netInfo != null && netInfo.isConnectedOrConnecting()) {
            return true;
        }
        return false;
    }

    public void prepare() {
        /* ------Station- buffering-------- */
        this.isPreparingStarted = true;
        sendBroadcast(new Intent(Mode.START_PREPARING));
        try {
            this.player.prepare(streams, playingStreamIndex);
        } catch (Exception e) {
            e.printStackTrace();
            stop();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if(intent != null && intent.getAction() != null && intent.getAction().equalsIgnoreCase("stopself")) {
            showNotification();
            stop();
            this.exitNotification();
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        if (this.isPlaying) {
            sendBroadcast(new Intent(Mode.PLAYING));
        } else if (this.isPreparingStarted) {
            sendBroadcast(new Intent(Mode.START_PREPARING));
        } else {
            sendBroadcast(new Intent(Mode.STARTED));
        }

        return Service.START_STICKY;
    }

    // Для того, что бы этот вызов сработал надо
    // а) сделать не только bindService, но и startService перед этим
    // б) вернуть START_STICKY из onStartCommand
    // в) прописать stopWithTask в манифесте = false.
    public void onTaskRemoved(Intent rootIntent) {
        // Log.d("OnTaskRemoved","Callback");
        stop();
        this.exitNotification();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        player.stop();
        player.release();
        exitNotification();

        super.onDestroy();
    }

    @Override
    public void playerStarted() {
        //  TODO
    }

    @Override
    public void onPlayerBuffering(boolean isPlaying, boolean isBuffering) {
        if (isPlaying) {
            this.isPreparingStarted = false;
            if (isBuffering) {
                this.isPlaying = false;
                sendBroadcast(new Intent(Mode.BUFFERING_START));
                showTextNotification("...");
                //buffering
            } else {
                this.isPlaying = true;
                showTextNotification(text);
                sendBroadcast(new Intent(Mode.PLAYING));
            }
        } else {
            //buffering
            this.isPlaying = false;
            if (isBuffering) {
                sendBroadcast(new Intent(Mode.BUFFERING_START));
            } else {
                sendBroadcast(new Intent(Mode.STOPPED));
            }
        }

        updateUi();
    }

    private void updateUi() {
        RemoteViews remoteViews = getNotificationRemoteViews();
        if (isPlaying) {
            remoteViews.setImageViewResource(R.id.btn_streaming_notification_pause, R.drawable.ic_media_pause);
        } else {
            remoteViews.setImageViewResource(R.id.btn_streaming_notification_pause, R.drawable.ic_media_play);
        }
        if (notifyBuilder != null) {
            notifyBuilder.setOngoing(isPlaying);
            notifyBuilder.setAutoCancel(isPlaying);
            notifyBuilder.setContent(remoteViews);

            if (!isPlaying) {
                stopForeground(false);
                notifyManager.notify(NOTIFY_ME_ID, notifyBuilder.build());
            } else {
                startForeground(NOTIFY_ME_ID, notifyBuilder.build());
            }
        }
    }

    @Override
    public void playerException(final Throwable t) {
        Log.e("Error", "Player exception occurred!");
        if (this.isButtonStopped) {
            this.isPlaying = false;
            this.isPreparingStarted = false;
            sendBroadcast(new Intent(Mode.ERROR));
            updateUi();
            return;
        }
        sendBroadcast(new Intent(Mode.RETRYING));
    }

    @Override
    public void playerMetadata(final String key, final String value) {
        if (key != null && key.equals("StreamTitle")) {
            Log.d("MORE StreamTitle:", value);
            String newValue = this.morefm_replace(value);
            this.text = newValue;
            if(this.module.statePlayOnFocus) {
                showTextNotification(newValue);
            }
            notifySystemMetaData(channel, value);
        }
    }

    RemoteViews remoteViews;

    private RemoteViews getNotificationRemoteViews() {
        if (remoteViews == null) {
            remoteViews = new RemoteViews(context.getPackageName(), R.layout.streaming_notification_player);
        }
        if(!TextUtils.isEmpty(channel)) {
            remoteViews.setTextViewText(R.id.album_notification, channel);
        }
        if(!TextUtils.isEmpty(text)) {
            remoteViews.setTextViewText(R.id.song_name_notification, text);
        }
        return remoteViews;
    }


    private String morefm_replace(String morefm_title) {
        String input = morefm_title;

        String[] items;
        String name;
        int wavIndex = input.lastIndexOf(".wav");
        int mp3Index = input.lastIndexOf(".mp3");
        if (mp3Index > 0) {
            items = input.split(".mp3");
            name = items[1];
        } else if (wavIndex > 0) {
            items = input.split(".wav");
            name = items[1];
        } else {
            name = morefm_title;
            items = new String[1];
            items[0] = morefm_title;
        }
        String newString = name.trim();
        if (newString == "") {
            name = items[0].trim();
        }
        name = name.replace("\\\\Nas\\st", "");
        int x = name.lastIndexOf("\\M\\Programs\\");
        if (x > 0) {
            name = name.substring(x);
        }
        name = name.replace("\\t", "");
        name = name.trim();
        return (name);
    }
}
