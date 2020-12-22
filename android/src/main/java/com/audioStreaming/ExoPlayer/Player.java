package com.audioStreaming.ExoPlayer;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import com.audioStreaming.Signal;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.MetadataRenderer;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextRenderer;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by Dmitriy on 25.01.2018.
 */

public class Player implements com.google.android.exoplayer2.Player.EventListener, MetadataRenderer.Output, TextRenderer.Output, TransferListener<DataSource> {
    private static final long FLUSH_BANDWIDTH_TIMEOUT = 4000;
    private SimpleExoPlayer player;
    private Context context;
    private Callback callback;
    private int lastState;
    private DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
    private long lastFlushBandwidthTime = System.currentTimeMillis();
    private ArrayList<Signal.Stream> streams;
    private int index;

    public boolean isPlaying() {
        return player != null && player.getPlayWhenReady();
    }

    private Handler handler = new Handler();

    public interface Callback {
        void playerStarted();

        void onPlayerBuffering(boolean isPlaying, boolean isBuffering);

        void playerException(final Throwable t);

        void playerMetadata(final String key, final String value);
    }

    public void init(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;

        CustomTrackSelector trackSelector = new CustomTrackSelector(bandwidthMeter, context);
        lastFlushBandwidthTime = System.currentTimeMillis();

        CustomLoadControl loadControl = new CustomLoadControl(trackSelector, handler);

        DefaultRenderersFactory defaultRenderersFactory = new DefaultRenderersFactory(context);
        player = ExoPlayerFactory.newSimpleInstance(defaultRenderersFactory, trackSelector, loadControl);

        player.addMetadataOutput(this);
        player.addTextOutput(this);
        player.addListener(this);
    }

    public void prepare(ArrayList<Signal.Stream> streams, int index) {
        this.streams = streams;
        this.index = index;
        if(streams == null || index >= streams.size()) {
            return;
        }

        player.release();
        init(context, callback);
//Produces DataSource instances through which media data is loaded.
        DefaultHttpDataSourceFactory dataSourceFactory = new DefaultHttpDataSourceFactory(Util.getUserAgent(context, getClass().getName()), this);

        MediaSource source = null;
        if (index == Signal.ADAPTIVE_MODE) {
            MediaSource sources[] = new MediaSource[streams.size()];
            for (int i = 0; i < sources.length; i++) {
                Signal.Stream stream = streams.get(i);
                MediaSource mediaSource = new ExtractorMediaSource.Factory(dataSourceFactory)
                        .setContinueLoadingCheckIntervalBytes(1024)
                        .setExtractorsFactory(new CustomMp3Extractor.Factory(stream.bitrate))
                        .createMediaSource(Uri.parse(stream.url));

                sources[i] = mediaSource;
            }

            source = new MergingMediaSource(sources);
        } else {
            Signal.Stream stream = streams.get(index);
            source = /*new DashMediaSource.Factory(new DefaultDashChunkSource.Factory(dataSourceFactory), dataSourceFactory)
                .setLivePresentationDelayMs(185000)
                .createMediaSource(Uri.parse(url));*/
                    new ExtractorMediaSource.Factory(dataSourceFactory)
                            .setContinueLoadingCheckIntervalBytes(1024)
                            .setExtractorsFactory(new CustomMp3Extractor.Factory(stream.bitrate))
                            .createMediaSource(Uri.parse(stream.url));
        }

        player.prepare(source);
    }

    public void play() {
        player.setPlayWhenReady(true);

    }

    public void stop() {
        player.stop();
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object o, int i) {

    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroupArray, TrackSelectionArray trackSelectionArray) {
        TrackGroup trackGroup = trackGroupArray.get(0);
    }

    @Override
    public void onLoadingChanged(boolean b) {

    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        boolean buffering = lastState != com.google.android.exoplayer2.Player.STATE_BUFFERING;

        lastState = playbackState;
        switch (playbackState) {
            case com.google.android.exoplayer2.Player.STATE_BUFFERING:
                if (callback != null) {
                    callback.onPlayerBuffering(playWhenReady, buffering);
                }
                break;
            case com.google.android.exoplayer2.Player.STATE_ENDED:
                if (callback != null) {
                    callback.onPlayerBuffering(false, buffering);
                }
                break;
            case com.google.android.exoplayer2.Player.STATE_IDLE:
                if (callback != null) {
                    callback.onPlayerBuffering(false, false);
                }
                break;
            case com.google.android.exoplayer2.Player.STATE_READY:
                if (callback != null) {
                    callback.playerStarted();
                    callback.onPlayerBuffering(true, false);
                }
                break;
        }
    }

    public void release() {
        player.release();
    }


    @Override
    public void onRepeatModeChanged(int i) {

    }

    @Override
    public void onShuffleModeEnabledChanged(boolean b) {

    }

    @Override
    public void onPlayerError(ExoPlaybackException e) {
        player.stop();
        prepare(streams, index);
        player.setPlayWhenReady(true);

        if (callback != null) {
            callback.playerException(e);
        }
    }

    @Override
    public void onPositionDiscontinuity(int i) {

    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {

    }

    @Override
    public void onSeekProcessed() {

    }


    @Override
    public void onMetadata(Metadata metadata) {
        Metadata.Entry entry = metadata.get(0);
    }


    @Override
    public void onCues(List<Cue> list) {
        Log.d("cues", list.toString());
    }


    @Override
    public void onTransferStart(DataSource dataSource, DataSpec dataSpec) {
        if (dataSource instanceof DefaultHttpDataSource && callback != null) {
            DefaultHttpDataSource defaultHttpDataSource = (DefaultHttpDataSource) dataSource;
            Map<String, List<String>> responseHeaders = defaultHttpDataSource.getResponseHeaders();
            for (java.util.Map.Entry<String, java.util.List<String>> me : responseHeaders.entrySet()) {
                for (String s : me.getValue()) {
                    callback.playerMetadata(me.getKey(), s);
                }
            }
        }

        bandwidthMeter.onTransferStart(dataSource, dataSpec);
    }

    @Override
    public void onBytesTransferred(DataSource dataSource, int i) {
        bandwidthMeter.onBytesTransferred(dataSource, i);
        if (System.currentTimeMillis() - lastFlushBandwidthTime > FLUSH_BANDWIDTH_TIMEOUT) {
            flushRunnable.run();
        }
    }

    private Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(flushRunnable);
            bandwidthMeter.onBytesTransferred(null, 1);
            bandwidthMeter.onTransferEnd(null);
            lastFlushBandwidthTime = System.currentTimeMillis();
            bandwidthMeter.onTransferStart(null, null);
            handler.postDelayed(flushRunnable, FLUSH_BANDWIDTH_TIMEOUT);
        }
    };

    @Override
    public void onTransferEnd(DataSource dataSource) {

    }

}
