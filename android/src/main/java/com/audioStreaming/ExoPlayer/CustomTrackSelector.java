/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.audioStreaming.ExoPlayer;

import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.audioStreaming.Mode;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A default {@link TrackSelector} suitable for most use cases.
 * <p>
 * <h3>Constraint based track selection</h3>
 * Whilst this selector supports setting specific track overrides, the recommended way of
 * changing which tracks are selected is by setting {@link Parameters} that constrain the track
 * selection process. For example an instance can specify a preferred language for
 * the audio track, and impose constraints on the maximum video resolution that should be selected
 * for adaptive playbacks. Modifying the parameters is simple:
 * <pre>
 * {@code
 * Parameters currentParameters = trackSelector.getParameters();
 * // Generate new parameters to prefer German audio and impose a maximum video size constraint.
 * Parameters newParameters = currentParameters
 *     .withPreferredAudioLanguage("deu")
 *     .withMaxVideoSize(1024, 768);
 * // Set the new parameters on the selector.
 * trackSelector.setParameters(newParameters);}
 * </pre>
 * There are several benefits to using constraint based track selection instead of specific track
 * overrides:
 * <ul>
 * <li>You can specify constraints before knowing what tracks the media provides. This can
 * simplify track selection code (e.g. you don't have to listen for changes in the available
 * tracks before configuring the selector).</li>
 * <li>Constraints can be applied consistently across all periods in a complex piece of media,
 * even if those periods contain different tracks. In contrast, a specific track override is only
 * applied to periods whose tracks match those for which the override was set.</li>
 * </ul>
 * <p>
 * <h3>Track overrides, disabling renderers and tunneling</h3>
 * This selector extends {@link MappingTrackSelector}, and so inherits its support for setting
 * specific track overrides, disabling renderers and configuring tunneled media playback. See
 * {@link MappingTrackSelector} for details.
 * <p>
 * <h3>Extending this class</h3>
 * This class is designed to be extensible by developers who wish to customize its behavior but do
 * not wish to implement their own {@link MappingTrackSelector} or {@link TrackSelector} from
 * scratch.
 */
public class CustomTrackSelector extends MappingTrackSelector implements CustomLoadControl.EventListener {

    private static final long ADAPTIVE_TIMEOUT = 5000;
    private BandwidthMeter bandwidthMeter;
    private Context context;
    private long bufferedDurationMs = 0;
    private int lastTrackIndex = 0;
    private int lastGroupIndex = -1;

    /**
     * A builder for {@link Parameters}.
     */
    public static final class ParametersBuilder {

        private String preferredAudioLanguage;
        private String preferredTextLanguage;
        private boolean selectUndeterminedTextLanguage;
        private int disabledTextTrackSelectionFlags;
        private boolean forceLowestBitrate;
        private boolean allowMixedMimeAdaptiveness;
        private boolean allowNonSeamlessAdaptiveness;
        private int maxVideoWidth;
        private int maxVideoHeight;
        private int maxVideoBitrate;
        private boolean exceedVideoConstraintsIfNecessary;
        private boolean exceedRendererCapabilitiesIfNecessary;
        private int viewportWidth;
        private int viewportHeight;
        private boolean viewportOrientationMayChange;

        /**
         * Creates a builder obtaining the initial values from {@link Parameters#DEFAULT}.
         */
        public ParametersBuilder() {
            this(Parameters.DEFAULT);
        }

        /**
         * @param initialValues The {@link Parameters} from which the initial values of the builder are
         *                      obtained.
         */
        private ParametersBuilder(Parameters initialValues) {
            preferredAudioLanguage = initialValues.preferredAudioLanguage;
            preferredTextLanguage = initialValues.preferredTextLanguage;
            selectUndeterminedTextLanguage = initialValues.selectUndeterminedTextLanguage;
            disabledTextTrackSelectionFlags = initialValues.disabledTextTrackSelectionFlags;
            forceLowestBitrate = initialValues.forceLowestBitrate;
            allowMixedMimeAdaptiveness = initialValues.allowMixedMimeAdaptiveness;
            allowNonSeamlessAdaptiveness = initialValues.allowNonSeamlessAdaptiveness;
            maxVideoWidth = initialValues.maxVideoWidth;
            maxVideoHeight = initialValues.maxVideoHeight;
            maxVideoBitrate = initialValues.maxVideoBitrate;
            exceedVideoConstraintsIfNecessary = initialValues.exceedVideoConstraintsIfNecessary;
            exceedRendererCapabilitiesIfNecessary = initialValues.exceedRendererCapabilitiesIfNecessary;
            viewportWidth = initialValues.viewportWidth;
            viewportHeight = initialValues.viewportHeight;
            viewportOrientationMayChange = initialValues.viewportOrientationMayChange;
        }

        /**
         * See {@link Parameters#preferredAudioLanguage}.
         *
         * @return This builder.
         */
        public ParametersBuilder setPreferredAudioLanguage(String preferredAudioLanguage) {
            this.preferredAudioLanguage = preferredAudioLanguage;
            return this;
        }

        /**
         * See {@link Parameters#preferredTextLanguage}.
         *
         * @return This builder.
         */
        public ParametersBuilder setPreferredTextLanguage(String preferredTextLanguage) {
            this.preferredTextLanguage = preferredTextLanguage;
            return this;
        }

        /**
         * See {@link Parameters#selectUndeterminedTextLanguage}.
         *
         * @return This builder.
         */
        public ParametersBuilder setSelectUndeterminedTextLanguage(
                boolean selectUndeterminedTextLanguage) {
            this.selectUndeterminedTextLanguage = selectUndeterminedTextLanguage;
            return this;
        }

        /**
         * See {@link Parameters#disabledTextTrackSelectionFlags}.
         *
         * @return This builder.
         */
        public ParametersBuilder setDisabledTextTrackSelectionFlags(
                int disabledTextTrackSelectionFlags) {
            this.disabledTextTrackSelectionFlags = disabledTextTrackSelectionFlags;
            return this;
        }

        /**
         * See {@link Parameters#forceLowestBitrate}.
         *
         * @return This builder.
         */
        public ParametersBuilder setForceLowestBitrate(boolean forceLowestBitrate) {
            this.forceLowestBitrate = forceLowestBitrate;
            return this;
        }

        /**
         * See {@link Parameters#allowMixedMimeAdaptiveness}.
         *
         * @return This builder.
         */
        public ParametersBuilder setAllowMixedMimeAdaptiveness(boolean allowMixedMimeAdaptiveness) {
            this.allowMixedMimeAdaptiveness = allowMixedMimeAdaptiveness;
            return this;
        }

        /**
         * See {@link Parameters#allowNonSeamlessAdaptiveness}.
         *
         * @return This builder.
         */
        public ParametersBuilder setAllowNonSeamlessAdaptiveness(boolean allowNonSeamlessAdaptiveness) {
            this.allowNonSeamlessAdaptiveness = allowNonSeamlessAdaptiveness;
            return this;
        }

        /**
         * Equivalent to {@link #setMaxVideoSize setMaxVideoSize(1279, 719)}.
         *
         * @return This builder.
         */
        public ParametersBuilder setMaxVideoSizeSd() {
            return setMaxVideoSize(1279, 719);
        }

        /**
         * Equivalent to {@link #setMaxVideoSize setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)}.
         *
         * @return This builder.
         */
        public ParametersBuilder clearVideoSizeConstraints() {
            return setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        /**
         * See {@link Parameters#maxVideoWidth} and {@link Parameters#maxVideoHeight}.
         *
         * @return This builder.
         */
        public ParametersBuilder setMaxVideoSize(int maxVideoWidth, int maxVideoHeight) {
            this.maxVideoWidth = maxVideoWidth;
            this.maxVideoHeight = maxVideoHeight;
            return this;
        }

        /**
         * See {@link Parameters#maxVideoBitrate}.
         *
         * @return This builder.
         */
        public ParametersBuilder setMaxVideoBitrate(int maxVideoBitrate) {
            this.maxVideoBitrate = maxVideoBitrate;
            return this;
        }

        /**
         * See {@link Parameters#exceedVideoConstraintsIfNecessary}.
         *
         * @return This builder.
         */
        public ParametersBuilder setExceedVideoConstraintsIfNecessary(
                boolean exceedVideoConstraintsIfNecessary) {
            this.exceedVideoConstraintsIfNecessary = exceedVideoConstraintsIfNecessary;
            return this;
        }

        /**
         * See {@link Parameters#exceedRendererCapabilitiesIfNecessary}.
         *
         * @return This builder.
         */
        public ParametersBuilder setExceedRendererCapabilitiesIfNecessary(
                boolean exceedRendererCapabilitiesIfNecessary) {
            this.exceedRendererCapabilitiesIfNecessary = exceedRendererCapabilitiesIfNecessary;
            return this;
        }

        /**
         * Equivalent to invoking {@link #setViewportSize} with the viewport size obtained from
         * {@link Util#getPhysicalDisplaySize(Context)}.
         *
         * @param context                      The context to obtain the viewport size from.
         * @param viewportOrientationMayChange See {@link #viewportOrientationMayChange}.
         * @return This builder.
         */
        public ParametersBuilder setViewportSizeToPhysicalDisplaySize(Context context,
                                                                      boolean viewportOrientationMayChange) {
            // Assume the viewport is fullscreen.
            Point viewportSize = Util.getPhysicalDisplaySize(context);
            return setViewportSize(viewportSize.x, viewportSize.y, viewportOrientationMayChange);
        }

        /**
         * Equivalent to
         * {@link #setViewportSize setViewportSize(Integer.MAX_VALUE, Integer.MAX_VALUE, true)}.
         *
         * @return This builder.
         */
        public ParametersBuilder clearViewportSizeConstraints() {
            return setViewportSize(Integer.MAX_VALUE, Integer.MAX_VALUE, true);
        }

        /**
         * See {@link Parameters#viewportWidth}, {@link Parameters#maxVideoHeight} and
         * {@link Parameters#viewportOrientationMayChange}.
         *
         * @return This builder.
         */
        public ParametersBuilder setViewportSize(int viewportWidth, int viewportHeight,
                                                 boolean viewportOrientationMayChange) {
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.viewportOrientationMayChange = viewportOrientationMayChange;
            return this;
        }

        /**
         * Builds a {@link Parameters} instance with the selected values.
         */
        public Parameters build() {
            return new Parameters(
                    preferredAudioLanguage,
                    preferredTextLanguage,
                    selectUndeterminedTextLanguage,
                    disabledTextTrackSelectionFlags,
                    forceLowestBitrate,
                    allowMixedMimeAdaptiveness,
                    allowNonSeamlessAdaptiveness,
                    maxVideoWidth,
                    maxVideoHeight,
                    maxVideoBitrate,
                    exceedVideoConstraintsIfNecessary,
                    exceedRendererCapabilitiesIfNecessary,
                    viewportWidth,
                    viewportHeight,
                    viewportOrientationMayChange);
        }

    }

    public static final class Parameters {

        /**
         * An instance with default values:
         * <p>
         * <ul>
         * <li>No preferred audio language.
         * <li>No preferred text language.
         * <li>Text tracks with undetermined language are not selected if no track with {@link
         * #preferredTextLanguage} is available.
         * <li>All selection flags are considered for text track selections.
         * <li>Lowest bitrate track selections are not forced.
         * <li>Adaptation between different mime types is not allowed.
         * <li>Non seamless adaptation is allowed.
         * <li>No max limit for video width/height.
         * <li>No max video bitrate.
         * <li>Video constraints are exceeded if no supported selection can be made otherwise.
         * <li>Renderer capabilities are exceeded if no supported selection can be made.
         * <li>No viewport constraints.
         * </ul>
         */
        public static final Parameters DEFAULT = new Parameters();

        // Audio
        /**
         * The preferred language for audio, as well as for forced text tracks, as an ISO 639-2/T tag.
         * {@code null} selects the default track, or the first track if there's no default.
         */
        public final String preferredAudioLanguage;

        // Text
        /**
         * The preferred language for text tracks as an ISO 639-2/T tag. {@code null} selects the
         * default track if there is one, or no track otherwise.
         */
        public final String preferredTextLanguage;
        /**
         * Whether a text track with undetermined language should be selected if no track with
         * {@link #preferredTextLanguage} is available, or if {@link #preferredTextLanguage} is unset.
         */
        public final boolean selectUndeterminedTextLanguage;
        /**
         * Bitmask of selection flags that are disabled for text track selections. See {@link
         * C.SelectionFlags}.
         */
        public final int disabledTextTrackSelectionFlags;

        // Video
        /**
         * Maximum allowed video width.
         */
        public final int maxVideoWidth;
        /**
         * Maximum allowed video height.
         */
        public final int maxVideoHeight;
        /**
         * Maximum video bitrate.
         */
        public final int maxVideoBitrate;
        /**
         * Whether to exceed video constraints when no selection can be made otherwise.
         */
        public final boolean exceedVideoConstraintsIfNecessary;
        /**
         * Viewport width in pixels. Constrains video tracks selections for adaptive playbacks so that
         * only tracks suitable for the viewport are selected.
         */
        public final int viewportWidth;
        /**
         * Viewport height in pixels. Constrains video tracks selections for adaptive playbacks so that
         * only tracks suitable for the viewport are selected.
         */
        public final int viewportHeight;
        /**
         * Whether the viewport orientation may change during playback. Constrains video tracks
         * selections for adaptive playbacks so that only tracks suitable for the viewport are selected.
         */
        public final boolean viewportOrientationMayChange;

        // General
        /**
         * Whether to force selection of the single lowest bitrate audio and video tracks that comply
         * with all other constraints.
         */
        public final boolean forceLowestBitrate;
        /**
         * Whether to allow adaptive selections containing mixed mime types.
         */
        public final boolean allowMixedMimeAdaptiveness;
        /**
         * Whether to allow adaptive selections where adaptation may not be completely seamless.
         */
        public final boolean allowNonSeamlessAdaptiveness;
        /**
         * Whether to exceed renderer capabilities when no selection can be made otherwise.
         */
        public final boolean exceedRendererCapabilitiesIfNecessary;

        private Parameters() {
            this(
                    null,
                    null,
                    false,
                    0,
                    false,
                    false,
                    true,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    true,
                    true,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    true);
        }

        private Parameters(
                String preferredAudioLanguage,
                String preferredTextLanguage,
                boolean selectUndeterminedTextLanguage,
                int disabledTextTrackSelectionFlags,
                boolean forceLowestBitrate,
                boolean allowMixedMimeAdaptiveness,
                boolean allowNonSeamlessAdaptiveness,
                int maxVideoWidth,
                int maxVideoHeight,
                int maxVideoBitrate,
                boolean exceedVideoConstraintsIfNecessary,
                boolean exceedRendererCapabilitiesIfNecessary,
                int viewportWidth,
                int viewportHeight,
                boolean viewportOrientationMayChange) {
            this.preferredAudioLanguage = Util.normalizeLanguageCode(preferredAudioLanguage);
            this.preferredTextLanguage = Util.normalizeLanguageCode(preferredTextLanguage);
            this.selectUndeterminedTextLanguage = selectUndeterminedTextLanguage;
            this.disabledTextTrackSelectionFlags = disabledTextTrackSelectionFlags;
            this.forceLowestBitrate = forceLowestBitrate;
            this.allowMixedMimeAdaptiveness = allowMixedMimeAdaptiveness;
            this.allowNonSeamlessAdaptiveness = allowNonSeamlessAdaptiveness;
            this.maxVideoWidth = maxVideoWidth;
            this.maxVideoHeight = maxVideoHeight;
            this.maxVideoBitrate = maxVideoBitrate;
            this.exceedVideoConstraintsIfNecessary = exceedVideoConstraintsIfNecessary;
            this.exceedRendererCapabilitiesIfNecessary = exceedRendererCapabilitiesIfNecessary;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.viewportOrientationMayChange = viewportOrientationMayChange;
        }

        /**
         * Creates a new {@link ParametersBuilder}, copying the initial values from this instance.
         */
        public ParametersBuilder buildUpon() {
            return new ParametersBuilder(this);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            Parameters other = (Parameters) obj;
            return selectUndeterminedTextLanguage == other.selectUndeterminedTextLanguage
                    && disabledTextTrackSelectionFlags == other.disabledTextTrackSelectionFlags
                    && forceLowestBitrate == other.forceLowestBitrate
                    && allowMixedMimeAdaptiveness == other.allowMixedMimeAdaptiveness
                    && allowNonSeamlessAdaptiveness == other.allowNonSeamlessAdaptiveness
                    && maxVideoWidth == other.maxVideoWidth
                    && maxVideoHeight == other.maxVideoHeight
                    && exceedVideoConstraintsIfNecessary == other.exceedVideoConstraintsIfNecessary
                    && exceedRendererCapabilitiesIfNecessary == other.exceedRendererCapabilitiesIfNecessary
                    && viewportOrientationMayChange == other.viewportOrientationMayChange
                    && viewportWidth == other.viewportWidth
                    && viewportHeight == other.viewportHeight
                    && maxVideoBitrate == other.maxVideoBitrate
                    && TextUtils.equals(preferredAudioLanguage, other.preferredAudioLanguage)
                    && TextUtils.equals(preferredTextLanguage, other.preferredTextLanguage);
        }

        @Override
        public int hashCode() {
            int result = selectUndeterminedTextLanguage ? 1 : 0;
            result = 31 * result + disabledTextTrackSelectionFlags;
            result = 31 * result + (forceLowestBitrate ? 1 : 0);
            result = 31 * result + (allowMixedMimeAdaptiveness ? 1 : 0);
            result = 31 * result + (allowNonSeamlessAdaptiveness ? 1 : 0);
            result = 31 * result + maxVideoWidth;
            result = 31 * result + maxVideoHeight;
            result = 31 * result + (exceedVideoConstraintsIfNecessary ? 1 : 0);
            result = 31 * result + (exceedRendererCapabilitiesIfNecessary ? 1 : 0);
            result = 31 * result + (viewportOrientationMayChange ? 1 : 0);
            result = 31 * result + viewportWidth;
            result = 31 * result + viewportHeight;
            result = 31 * result + maxVideoBitrate;
            result = 31 * result + preferredAudioLanguage.hashCode();
            result = 31 * result + preferredTextLanguage.hashCode();
            return result;
        }

    }

    /**
     * If a dimension (i.e. width or height) of a video is greater or equal to this fraction of the
     * corresponding viewport dimension, then the video is considered as filling the viewport (in that
     * dimension).
     */
    private static final float FRACTION_TO_CONSIDER_FULLSCREEN = 0.98f;
    private static final int[] NO_TRACKS = new int[0];
    private static final int WITHIN_RENDERER_CAPABILITIES_BONUS = 1000;

    private final TrackSelection.Factory adaptiveTrackSelectionFactory;
    private final AtomicReference<Parameters> paramsReference;

    /**
     * Constructs an instance that does not support adaptive track selection.
     */
    public CustomTrackSelector() {
        this((TrackSelection.Factory) null);
    }

    /**
     * Constructs an instance that supports adaptive track selection. Adaptive track selections use
     * the provided {@link BandwidthMeter} to determine which individual track should be used during
     * playback.
     *
     * @param bandwidthMeter The {@link BandwidthMeter}.
     */
    public CustomTrackSelector(BandwidthMeter bandwidthMeter, Context context) {
        this(new AdaptiveTrackSelection.Factory(bandwidthMeter));
        this.bandwidthMeter = bandwidthMeter;
        this.context = context;
    }

    /**
     * Constructs an instance that uses a factory to create adaptive track selections.
     *
     * @param adaptiveTrackSelectionFactory A factory for adaptive {@link TrackSelection}s, or null if
     *                                      the selector should not support adaptive tracks.
     */
    public CustomTrackSelector(TrackSelection.Factory adaptiveTrackSelectionFactory) {
        this.adaptiveTrackSelectionFactory = adaptiveTrackSelectionFactory;
        paramsReference = new AtomicReference<>(Parameters.DEFAULT);
    }

    /**
     * Atomically sets the provided parameters for track selection.
     *
     * @param params The parameters for track selection.
     */
    public void setParameters(Parameters params) {
        Assertions.checkNotNull(params);
        if (!paramsReference.getAndSet(params).equals(params)) {
            invalidate();
        }
    }

    /**
     * Gets the current selection parameters.
     *
     * @return The current selection parameters.
     */
    public Parameters getParameters() {
        return paramsReference.get();
    }

    // MappingTrackSelector implementation.

    @Override
    protected TrackSelection[] selectTracks(RendererCapabilities[] rendererCapabilities,
                                            TrackGroupArray[] rendererTrackGroupArrays, int[][][] rendererFormatSupports)
            throws ExoPlaybackException {
        // Make a track selection for each renderer.
        int rendererCount = rendererCapabilities.length;
        TrackSelection[] rendererTrackSelections = new TrackSelection[rendererCount];
        Parameters params = paramsReference.get();

        boolean seenVideoRendererWithMappedTracks = false;
        boolean selectedVideoTracks = false;
        for (int i = 0; i < rendererCount; i++) {
            if (C.TRACK_TYPE_VIDEO == rendererCapabilities[i].getTrackType()) {
                if (!selectedVideoTracks) {
                    rendererTrackSelections[i] = selectVideoTrack(rendererCapabilities[i],
                            rendererTrackGroupArrays[i], rendererFormatSupports[i], params,
                            adaptiveTrackSelectionFactory);
                    selectedVideoTracks = rendererTrackSelections[i] != null;
                }
                seenVideoRendererWithMappedTracks |= rendererTrackGroupArrays[i].length > 0;
            }
        }

        boolean selectedAudioTracks = false;
        boolean selectedTextTracks = false;
        for (int i = 0; i < rendererCount; i++) {
            switch (rendererCapabilities[i].getTrackType()) {
                case C.TRACK_TYPE_VIDEO:
                    // Already done. Do nothing.
                    break;
                case C.TRACK_TYPE_AUDIO:
                    if (!selectedAudioTracks) {
                        rendererTrackSelections[i] = selectAudioTrack(rendererTrackGroupArrays[i],
                                rendererFormatSupports[i], params,
                                seenVideoRendererWithMappedTracks ? null : adaptiveTrackSelectionFactory);
                        selectedAudioTracks = rendererTrackSelections[i] != null;
                    }
                    break;
                case C.TRACK_TYPE_TEXT:
                    if (!selectedTextTracks) {
                        rendererTrackSelections[i] = selectTextTrack(rendererTrackGroupArrays[i],
                                rendererFormatSupports[i], params);
                        selectedTextTracks = rendererTrackSelections[i] != null;
                    }
                    break;
                default:
                    rendererTrackSelections[i] = selectOtherTrack(rendererCapabilities[i].getTrackType(),
                            rendererTrackGroupArrays[i], rendererFormatSupports[i], params);
                    break;
            }
        }
        return rendererTrackSelections;
    }

    // Video track selection implementation.

    /**
     * Called by {@link #selectTracks(RendererCapabilities[], TrackGroupArray[], int[][][])} to
     * create a {@link TrackSelection} for a video renderer.
     *
     * @param rendererCapabilities          The {@link RendererCapabilities} for the renderer.
     * @param groups                        The {@link TrackGroupArray} mapped to the renderer.
     * @param formatSupport                 The result of {@link RendererCapabilities#supportsFormat} for each mapped
     *                                      track, indexed by track group index and track index (in that order).
     * @param params                        The selector's current constraint parameters.
     * @param adaptiveTrackSelectionFactory A factory for generating adaptive track selections, or
     *                                      null if a fixed track selection is required.
     * @return The {@link TrackSelection} for the renderer, or null if no selection was made.
     * @throws ExoPlaybackException If an error occurs while selecting the tracks.
     */
    protected TrackSelection selectVideoTrack(RendererCapabilities rendererCapabilities,
                                              TrackGroupArray groups, int[][] formatSupport, Parameters params,
                                              TrackSelection.Factory adaptiveTrackSelectionFactory) throws ExoPlaybackException {
        TrackSelection selection = null;
        if (!params.forceLowestBitrate && adaptiveTrackSelectionFactory != null) {
            selection = selectAdaptiveVideoTrack(rendererCapabilities, groups, formatSupport,
                    params, adaptiveTrackSelectionFactory);
        }
        if (selection == null) {
            selection = selectFixedVideoTrack(groups, formatSupport, params);
        }
        return selection;
    }

    private static TrackSelection selectAdaptiveVideoTrack(RendererCapabilities rendererCapabilities,
                                                           TrackGroupArray groups, int[][] formatSupport, Parameters params,
                                                           TrackSelection.Factory adaptiveTrackSelectionFactory) throws ExoPlaybackException {
        int requiredAdaptiveSupport = params.allowNonSeamlessAdaptiveness
                ? (RendererCapabilities.ADAPTIVE_NOT_SEAMLESS | RendererCapabilities.ADAPTIVE_SEAMLESS)
                : RendererCapabilities.ADAPTIVE_SEAMLESS;
        boolean allowMixedMimeTypes = params.allowMixedMimeAdaptiveness
                && (rendererCapabilities.supportsMixedMimeTypeAdaptation() & requiredAdaptiveSupport) != 0;
        for (int i = 0; i < groups.length; i++) {
            TrackGroup group = groups.get(i);
            int[] adaptiveTracks = getAdaptiveVideoTracksForGroup(group, formatSupport[i],
                    allowMixedMimeTypes, requiredAdaptiveSupport, params.maxVideoWidth, params.maxVideoHeight,
                    params.maxVideoBitrate, params.viewportWidth, params.viewportHeight,
                    params.viewportOrientationMayChange);
            if (adaptiveTracks.length > 0) {
                return adaptiveTrackSelectionFactory.createTrackSelection(group, adaptiveTracks);
            }
        }
        return null;
    }

    private static int[] getAdaptiveVideoTracksForGroup(TrackGroup group, int[] formatSupport,
                                                        boolean allowMixedMimeTypes, int requiredAdaptiveSupport, int maxVideoWidth,
                                                        int maxVideoHeight, int maxVideoBitrate, int viewportWidth, int viewportHeight,
                                                        boolean viewportOrientationMayChange) {
        if (group.length < 2) {
            return NO_TRACKS;
        }

        List<Integer> selectedTrackIndices = getViewportFilteredTrackIndices(group, viewportWidth,
                viewportHeight, viewportOrientationMayChange);
        if (selectedTrackIndices.size() < 2) {
            return NO_TRACKS;
        }

        String selectedMimeType = null;
        if (!allowMixedMimeTypes) {
            // Select the mime type for which we have the most adaptive tracks.
            HashSet<String> seenMimeTypes = new HashSet<>();
            int selectedMimeTypeTrackCount = 0;
            for (int i = 0; i < selectedTrackIndices.size(); i++) {
                int trackIndex = selectedTrackIndices.get(i);
                String sampleMimeType = group.getFormat(trackIndex).sampleMimeType;
                if (seenMimeTypes.add(sampleMimeType)) {
                    int countForMimeType = getAdaptiveVideoTrackCountForMimeType(group, formatSupport,
                            requiredAdaptiveSupport, sampleMimeType, maxVideoWidth, maxVideoHeight,
                            maxVideoBitrate, selectedTrackIndices);
                    if (countForMimeType > selectedMimeTypeTrackCount) {
                        selectedMimeType = sampleMimeType;
                        selectedMimeTypeTrackCount = countForMimeType;
                    }
                }
            }
        }

        // Filter by the selected mime type.
        filterAdaptiveVideoTrackCountForMimeType(group, formatSupport, requiredAdaptiveSupport,
                selectedMimeType, maxVideoWidth, maxVideoHeight, maxVideoBitrate, selectedTrackIndices);

        return selectedTrackIndices.size() < 2 ? NO_TRACKS : Util.toArray(selectedTrackIndices);
    }

    private static int getAdaptiveVideoTrackCountForMimeType(TrackGroup group, int[] formatSupport,
                                                             int requiredAdaptiveSupport, String mimeType, int maxVideoWidth, int maxVideoHeight,
                                                             int maxVideoBitrate, List<Integer> selectedTrackIndices) {
        int adaptiveTrackCount = 0;
        for (int i = 0; i < selectedTrackIndices.size(); i++) {
            int trackIndex = selectedTrackIndices.get(i);
            if (isSupportedAdaptiveVideoTrack(group.getFormat(trackIndex), mimeType,
                    formatSupport[trackIndex], requiredAdaptiveSupport, maxVideoWidth, maxVideoHeight,
                    maxVideoBitrate)) {
                adaptiveTrackCount++;
            }
        }
        return adaptiveTrackCount;
    }

    private static void filterAdaptiveVideoTrackCountForMimeType(TrackGroup group,
                                                                 int[] formatSupport, int requiredAdaptiveSupport, String mimeType, int maxVideoWidth,
                                                                 int maxVideoHeight, int maxVideoBitrate, List<Integer> selectedTrackIndices) {
        for (int i = selectedTrackIndices.size() - 1; i >= 0; i--) {
            int trackIndex = selectedTrackIndices.get(i);
            if (!isSupportedAdaptiveVideoTrack(group.getFormat(trackIndex), mimeType,
                    formatSupport[trackIndex], requiredAdaptiveSupport, maxVideoWidth, maxVideoHeight,
                    maxVideoBitrate)) {
                selectedTrackIndices.remove(i);
            }
        }
    }

    private static boolean isSupportedAdaptiveVideoTrack(Format format, String mimeType,
                                                         int formatSupport, int requiredAdaptiveSupport, int maxVideoWidth, int maxVideoHeight,
                                                         int maxVideoBitrate) {
        return isSupported(formatSupport, false) && ((formatSupport & requiredAdaptiveSupport) != 0)
                && (mimeType == null || Util.areEqual(format.sampleMimeType, mimeType))
                && (format.width == Format.NO_VALUE || format.width <= maxVideoWidth)
                && (format.height == Format.NO_VALUE || format.height <= maxVideoHeight)
                && (format.bitrate == Format.NO_VALUE || format.bitrate <= maxVideoBitrate);
    }

    private static TrackSelection selectFixedVideoTrack(TrackGroupArray groups,
                                                        int[][] formatSupport, Parameters params) {
        TrackGroup selectedGroup = null;
        int selectedTrackIndex = 0;
        int selectedTrackScore = 0;
        int selectedBitrate = Format.NO_VALUE;
        int selectedPixelCount = Format.NO_VALUE;
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
            TrackGroup trackGroup = groups.get(groupIndex);
            List<Integer> selectedTrackIndices = getViewportFilteredTrackIndices(trackGroup,
                    params.viewportWidth, params.viewportHeight, params.viewportOrientationMayChange);
            int[] trackFormatSupport = formatSupport[groupIndex];
            for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
                if (isSupported(trackFormatSupport[trackIndex],
                        params.exceedRendererCapabilitiesIfNecessary)) {
                    Format format = trackGroup.getFormat(trackIndex);
                    boolean isWithinConstraints = selectedTrackIndices.contains(trackIndex)
                            && (format.width == Format.NO_VALUE || format.width <= params.maxVideoWidth)
                            && (format.height == Format.NO_VALUE || format.height <= params.maxVideoHeight)
                            && (format.bitrate == Format.NO_VALUE || format.bitrate <= params.maxVideoBitrate);
                    if (!isWithinConstraints && !params.exceedVideoConstraintsIfNecessary) {
                        // Track should not be selected.
                        continue;
                    }
                    int trackScore = isWithinConstraints ? 2 : 1;
                    boolean isWithinCapabilities = isSupported(trackFormatSupport[trackIndex], false);
                    if (isWithinCapabilities) {
                        trackScore += WITHIN_RENDERER_CAPABILITIES_BONUS;
                    }
                    boolean selectTrack = trackScore > selectedTrackScore;
                    if (trackScore == selectedTrackScore) {
                        if (params.forceLowestBitrate) {
                            // Use bitrate as a tie breaker, preferring the lower bitrate.
                            selectTrack = compareFormatValues(format.bitrate, selectedBitrate) < 0;
                        } else {
                            // Use the pixel count as a tie breaker (or bitrate if pixel counts are tied). If
                            // we're within constraints prefer a higher pixel count (or bitrate), else prefer a
                            // lower count (or bitrate). If still tied then prefer the first track (i.e. the one
                            // that's already selected).
                            int formatPixelCount = format.getPixelCount();
                            int comparisonResult = formatPixelCount != selectedPixelCount
                                    ? compareFormatValues(formatPixelCount, selectedPixelCount)
                                    : compareFormatValues(format.bitrate, selectedBitrate);
                            selectTrack = isWithinCapabilities && isWithinConstraints
                                    ? comparisonResult > 0 : comparisonResult < 0;
                        }
                    }
                    if (selectTrack) {
                        selectedGroup = trackGroup;
                        selectedTrackIndex = trackIndex;
                        selectedTrackScore = trackScore;
                        selectedBitrate = format.bitrate;
                        selectedPixelCount = format.getPixelCount();
                    }
                }
            }
        }
        return selectedGroup == null ? null
                : new FixedTrackSelection(selectedGroup, selectedTrackIndex);
    }

    /**
     * Compares two format values for order. A known value is considered greater than
     * {@link Format#NO_VALUE}.
     *
     * @param first  The first value.
     * @param second The second value.
     * @return A negative integer if the first value is less than the second. Zero if they are equal.
     * A positive integer if the first value is greater than the second.
     */
    private static int compareFormatValues(int first, int second) {
        return first == Format.NO_VALUE ? (second == Format.NO_VALUE ? 0 : -1)
                : (second == Format.NO_VALUE ? 1 : (first - second));
    }

    // Audio track selection implementation.

    /**
     * Called by {@link #selectTracks(RendererCapabilities[], TrackGroupArray[], int[][][])} to
     * create a {@link TrackSelection} for an audio renderer.
     *
     * @param groups                        The {@link TrackGroupArray} mapped to the renderer.
     * @param formatSupport                 The result of {@link RendererCapabilities#supportsFormat} for each mapped
     *                                      track, indexed by track group index and track index (in that order).
     * @param params                        The selector's current constraint parameters.
     * @param adaptiveTrackSelectionFactory A factory for generating adaptive track selections, or
     *                                      null if a fixed track selection is required.
     * @return The {@link TrackSelection} for the renderer, or null if no selection was made.
     * @throws ExoPlaybackException If an error occurs while selecting the tracks.
     */
    protected TrackSelection selectAudioTrack(TrackGroupArray groups, int[][] formatSupport,
                                              Parameters params, TrackSelection.Factory adaptiveTrackSelectionFactory)
            throws ExoPlaybackException {
        int selectedTrackIndex = C.INDEX_UNSET;
        int selectedGroupIndex = C.INDEX_UNSET;
        AudioTrackScore selectedTrackScore = null;

        long bitrateEstimate = bandwidthMeter.getBitrateEstimate();
        Log.d("BitrateEstimate", "Buffer: " + bufferedDurationMs + "ms speed: " + bitrateEstimate / 1000.0 + " kB/s");

        //if buffer too low - we switch down
        //if buffer is very good - we try to switch up
        boolean canSwitchAdaptive = bufferedDurationMs < ADAPTIVE_TIMEOUT || bufferedDurationMs > 5 * ADAPTIVE_TIMEOUT;
        if(canSwitchAdaptive || lastGroupIndex < 0) {
            Log.d("BitrateEstimate", "Adaptive: changing");
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                TrackGroup trackGroup = groups.get(groupIndex);
                int[] trackFormatSupport = formatSupport[groupIndex];
                for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
                    if (isSupported(trackFormatSupport[trackIndex],
                            params.exceedRendererCapabilitiesIfNecessary)) {
                        Format format = trackGroup.getFormat(trackIndex);
                        AudioTrackScore trackScore =
                                new AudioTrackScore(format, bitrateEstimate, params, trackFormatSupport[trackIndex]);
                        if (selectedTrackScore == null || trackScore.compareTo(selectedTrackScore) > 0) {
                            selectedGroupIndex = groupIndex;
                            selectedTrackIndex = trackIndex;
                            selectedTrackScore = trackScore;
                        }
                    }
                }
            }
            if(lastGroupIndex >= 0 && selectedTrackScore != null) {
                Format format = groups.get(lastGroupIndex).getFormat(lastTrackIndex);//skip changing down if buffer big enough
                if(selectedTrackScore.getBitrate() < format.bitrate && bufferedDurationMs > ADAPTIVE_TIMEOUT) {
                    selectedGroupIndex = lastGroupIndex;
                    selectedTrackIndex = lastTrackIndex;
                }
            }


            Log.d("BitrateEstimate", "Switched to group:" + selectedGroupIndex);
        } else {
            Log.d("BitrateEstimate", "Adaptive: use old");
            selectedGroupIndex = lastGroupIndex;
            selectedTrackIndex = lastTrackIndex;
        }

        if(lastGroupIndex != selectedGroupIndex || lastTrackIndex != selectedTrackIndex) {
            if(selectedTrackScore != null) {
                Intent metaIntent = new Intent(Mode.ADAPTIVE_TRACK_CHANGED);
                metaIntent.putExtra("bitrate", selectedTrackScore.getBitrate());
                context.sendBroadcast(metaIntent);
            }
        }

        lastGroupIndex = selectedGroupIndex;
        lastTrackIndex = selectedTrackIndex;

        if (selectedGroupIndex == C.INDEX_UNSET) {
            return null;
        }
        TrackGroup selectedGroup = groups.get(selectedGroupIndex);

        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                invalidate();
            }
        }, ADAPTIVE_TIMEOUT);

        return new FixedTrackSelection(selectedGroup, selectedTrackIndex);
    }

    Handler h = new Handler();

    private static int getAdaptiveAudioTrackCount(TrackGroup group, int[] formatSupport,
                                                  AudioConfigurationTuple configuration) {
        int count = 0;
        for (int i = 0; i < group.length; i++) {
            if (isSupportedAdaptiveAudioTrack(group.getFormat(i), formatSupport[i], configuration)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isSupportedAdaptiveAudioTrack(Format format, int formatSupport,
                                                         AudioConfigurationTuple configuration) {
        return isSupported(formatSupport, false) && format.channelCount == configuration.channelCount
                && format.sampleRate == configuration.sampleRate
                && (configuration.mimeType == null
                || TextUtils.equals(configuration.mimeType, format.sampleMimeType));
    }

    // Text track selection implementation.

    /**
     * Called by {@link #selectTracks(RendererCapabilities[], TrackGroupArray[], int[][][])} to
     * create a {@link TrackSelection} for a text renderer.
     *
     * @param groups        The {@link TrackGroupArray} mapped to the renderer.
     * @param formatSupport The result of {@link RendererCapabilities#supportsFormat} for each mapped
     *                      track, indexed by track group index and track index (in that order).
     * @param params        The selector's current constraint parameters.
     * @return The {@link TrackSelection} for the renderer, or null if no selection was made.
     * @throws ExoPlaybackException If an error occurs while selecting the tracks.
     */
    protected TrackSelection selectTextTrack(TrackGroupArray groups, int[][] formatSupport,
                                             Parameters params) throws ExoPlaybackException {
        TrackGroup selectedGroup = null;
        int selectedTrackIndex = 0;
        int selectedTrackScore = 0;
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
            TrackGroup trackGroup = groups.get(groupIndex);
            int[] trackFormatSupport = formatSupport[groupIndex];
            for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
                if (isSupported(trackFormatSupport[trackIndex],
                        params.exceedRendererCapabilitiesIfNecessary)) {
                    Format format = trackGroup.getFormat(trackIndex);
                    int maskedSelectionFlags =
                            format.selectionFlags & ~params.disabledTextTrackSelectionFlags;
                    boolean isDefault = (maskedSelectionFlags & C.SELECTION_FLAG_DEFAULT) != 0;
                    boolean isForced = (maskedSelectionFlags & C.SELECTION_FLAG_FORCED) != 0;
                    int trackScore;
                    boolean preferredLanguageFound = formatHasLanguage(format, params.preferredTextLanguage);
                    if (preferredLanguageFound
                            || (params.selectUndeterminedTextLanguage && formatHasNoLanguage(format))) {
                        if (isDefault) {
                            trackScore = 8;
                        } else if (!isForced) {
                            // Prefer non-forced to forced if a preferred text language has been specified. Where
                            // both are provided the non-forced track will usually contain the forced subtitles as
                            // a subset.
                            trackScore = 6;
                        } else {
                            trackScore = 4;
                        }
                        trackScore += preferredLanguageFound ? 1 : 0;
                    } else if (isDefault) {
                        trackScore = 3;
                    } else if (isForced) {
                        if (formatHasLanguage(format, params.preferredAudioLanguage)) {
                            trackScore = 2;
                        } else {
                            trackScore = 1;
                        }
                    } else {
                        // Track should not be selected.
                        continue;
                    }
                    if (isSupported(trackFormatSupport[trackIndex], false)) {
                        trackScore += WITHIN_RENDERER_CAPABILITIES_BONUS;
                    }
                    if (trackScore > selectedTrackScore) {
                        selectedGroup = trackGroup;
                        selectedTrackIndex = trackIndex;
                        selectedTrackScore = trackScore;
                    }
                }
            }
        }
        return selectedGroup == null ? null
                : new FixedTrackSelection(selectedGroup, selectedTrackIndex);
    }

    // General track selection methods.

    /**
     * Called by {@link #selectTracks(RendererCapabilities[], TrackGroupArray[], int[][][])} to
     * create a {@link TrackSelection} for a renderer whose type is neither video, audio or text.
     *
     * @param trackType     The type of the renderer.
     * @param groups        The {@link TrackGroupArray} mapped to the renderer.
     * @param formatSupport The result of {@link RendererCapabilities#supportsFormat} for each mapped
     *                      track, indexed by track group index and track index (in that order).
     * @param params        The selector's current constraint parameters.
     * @return The {@link TrackSelection} for the renderer, or null if no selection was made.
     * @throws ExoPlaybackException If an error occurs while selecting the tracks.
     */
    protected TrackSelection selectOtherTrack(int trackType, TrackGroupArray groups,
                                              int[][] formatSupport, Parameters params) throws ExoPlaybackException {
        TrackGroup selectedGroup = null;
        int selectedTrackIndex = 0;
        int selectedTrackScore = 0;
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
            TrackGroup trackGroup = groups.get(groupIndex);
            int[] trackFormatSupport = formatSupport[groupIndex];
            for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
                if (isSupported(trackFormatSupport[trackIndex],
                        params.exceedRendererCapabilitiesIfNecessary)) {
                    Format format = trackGroup.getFormat(trackIndex);
                    boolean isDefault = (format.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0;
                    int trackScore = isDefault ? 2 : 1;
                    if (isSupported(trackFormatSupport[trackIndex], false)) {
                        trackScore += WITHIN_RENDERER_CAPABILITIES_BONUS;
                    }
                    if (trackScore > selectedTrackScore) {
                        selectedGroup = trackGroup;
                        selectedTrackIndex = trackIndex;
                        selectedTrackScore = trackScore;
                    }
                }
            }
        }
        return selectedGroup == null ? null
                : new FixedTrackSelection(selectedGroup, selectedTrackIndex);
    }

    /**
     * Applies the {@link RendererCapabilities#FORMAT_SUPPORT_MASK} to a value obtained from
     * {@link RendererCapabilities#supportsFormat(Format)}, returning true if the result is
     * {@link RendererCapabilities#FORMAT_HANDLED} or if {@code allowExceedsCapabilities} is set
     * and the result is {@link RendererCapabilities#FORMAT_EXCEEDS_CAPABILITIES}.
     *
     * @param formatSupport            A value obtained from {@link RendererCapabilities#supportsFormat(Format)}.
     * @param allowExceedsCapabilities Whether to return true if the format support component of the
     *                                 value is {@link RendererCapabilities#FORMAT_EXCEEDS_CAPABILITIES}.
     * @return True if the format support component is {@link RendererCapabilities#FORMAT_HANDLED}, or
     * if {@code allowExceedsCapabilities} is set and the format support component is
     * {@link RendererCapabilities#FORMAT_EXCEEDS_CAPABILITIES}.
     */
    protected static boolean isSupported(int formatSupport, boolean allowExceedsCapabilities) {
        int maskedSupport = formatSupport & RendererCapabilities.FORMAT_SUPPORT_MASK;
        return maskedSupport == RendererCapabilities.FORMAT_HANDLED || (allowExceedsCapabilities
                && maskedSupport == RendererCapabilities.FORMAT_EXCEEDS_CAPABILITIES);
    }

    /**
     * Returns whether a {@link Format} does not define a language.
     *
     * @param format The {@link Format}.
     * @return Whether the {@link Format} does not define a language.
     */
    protected static boolean formatHasNoLanguage(Format format) {
        return TextUtils.isEmpty(format.language) || formatHasLanguage(format, C.LANGUAGE_UNDETERMINED);
    }

    /**
     * Returns whether a {@link Format} specifies a particular language, or {@code false} if
     * {@code language} is null.
     *
     * @param format   The {@link Format}.
     * @param language The language.
     * @return Whether the format specifies the language, or {@code false} if {@code language} is
     * null.
     */
    protected static boolean formatHasLanguage(Format format, String language) {
        return language != null
                && TextUtils.equals(language, Util.normalizeLanguageCode(format.language));
    }

    // Viewport size util methods.

    private static List<Integer> getViewportFilteredTrackIndices(TrackGroup group, int viewportWidth,
                                                                 int viewportHeight, boolean orientationMayChange) {
        // Initially include all indices.
        ArrayList<Integer> selectedTrackIndices = new ArrayList<>(group.length);
        for (int i = 0; i < group.length; i++) {
            selectedTrackIndices.add(i);
        }

        if (viewportWidth == Integer.MAX_VALUE || viewportHeight == Integer.MAX_VALUE) {
            // Viewport dimensions not set. Return the full set of indices.
            return selectedTrackIndices;
        }

        int maxVideoPixelsToRetain = Integer.MAX_VALUE;
        for (int i = 0; i < group.length; i++) {
            Format format = group.getFormat(i);
            // Keep track of the number of pixels of the selected format whose resolution is the
            // smallest to exceed the maximum size at which it can be displayed within the viewport.
            // We'll discard formats of higher resolution.
            if (format.width > 0 && format.height > 0) {
                Point maxVideoSizeInViewport = getMaxVideoSizeInViewport(orientationMayChange,
                        viewportWidth, viewportHeight, format.width, format.height);
                int videoPixels = format.width * format.height;
                if (format.width >= (int) (maxVideoSizeInViewport.x * FRACTION_TO_CONSIDER_FULLSCREEN)
                        && format.height >= (int) (maxVideoSizeInViewport.y * FRACTION_TO_CONSIDER_FULLSCREEN)
                        && videoPixels < maxVideoPixelsToRetain) {
                    maxVideoPixelsToRetain = videoPixels;
                }
            }
        }

        // Filter out formats that exceed maxVideoPixelsToRetain. These formats have an unnecessarily
        // high resolution given the size at which the video will be displayed within the viewport. Also
        // filter out formats with unknown dimensions, since we have some whose dimensions are known.
        if (maxVideoPixelsToRetain != Integer.MAX_VALUE) {
            for (int i = selectedTrackIndices.size() - 1; i >= 0; i--) {
                Format format = group.getFormat(selectedTrackIndices.get(i));
                int pixelCount = format.getPixelCount();
                if (pixelCount == Format.NO_VALUE || pixelCount > maxVideoPixelsToRetain) {
                    selectedTrackIndices.remove(i);
                }
            }
        }

        return selectedTrackIndices;
    }

    /**
     * Given viewport dimensions and video dimensions, computes the maximum size of the video as it
     * will be rendered to fit inside of the viewport.
     */
    private static Point getMaxVideoSizeInViewport(boolean orientationMayChange, int viewportWidth,
                                                   int viewportHeight, int videoWidth, int videoHeight) {
        if (orientationMayChange && (videoWidth > videoHeight) != (viewportWidth > viewportHeight)) {
            // Rotation is allowed, and the video will be larger in the rotated viewport.
            int tempViewportWidth = viewportWidth;
            viewportWidth = viewportHeight;
            viewportHeight = tempViewportWidth;
        }

        if (videoWidth * viewportHeight >= videoHeight * viewportWidth) {
            // Horizontal letter-boxing along top and bottom.
            return new Point(viewportWidth, Util.ceilDivide(viewportWidth * videoHeight, videoWidth));
        } else {
            // Vertical letter-boxing along edges.
            return new Point(Util.ceilDivide(viewportHeight * videoWidth, videoHeight), viewportHeight);
        }
    }

    /**
     * A representation of how well a track fits with our track selection {@link Parameters}.
     * <p>
     * <p>This is used to rank different audio tracks relatively with each other.
     */
    private static final class AudioTrackScore implements Comparable<AudioTrackScore> {
        private long bandwidthEstimate;
        private final Parameters parameters;
        private final int withinRendererCapabilitiesScore;
        private final int matchLanguageScore;
        private final int defaultSelectionFlagScore;
        private final int channelCount;
        private final int sampleRate;
        private final int bitrate;

        public AudioTrackScore(Format format, long bandwidthEstimate, Parameters parameters, int formatSupport) {
            this.bandwidthEstimate = bandwidthEstimate;
            this.parameters = parameters;
            withinRendererCapabilitiesScore = isSupported(formatSupport, false) ? 1 : 0;
            matchLanguageScore = formatHasLanguage(format, parameters.preferredAudioLanguage) ? 1 : 0;
            defaultSelectionFlagScore = (format.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0 ? 1 : 0;
            channelCount = format.channelCount;
            sampleRate = format.sampleRate;
            bitrate = format.bitrate;
        }

        public int getBitrate() {
            return bitrate;
        }

        /**
         * Compares the score of the current track format with another {@link AudioTrackScore}.
         *
         * @param other The other score to compare to.
         * @return A positive integer if this score is better than the other. Zero if they are equal. A
         * negative integer if this score is worse than the other.
         */
        @Override
        public int compareTo(@NonNull AudioTrackScore other) {
            if (this.withinRendererCapabilitiesScore != other.withinRendererCapabilitiesScore) {
                return compareInts(this.withinRendererCapabilitiesScore,
                        other.withinRendererCapabilitiesScore);
            } else if (this.matchLanguageScore != other.matchLanguageScore) {
                return compareInts(this.matchLanguageScore, other.matchLanguageScore);
            } else if (this.defaultSelectionFlagScore != other.defaultSelectionFlagScore) {
                return compareInts(this.defaultSelectionFlagScore, other.defaultSelectionFlagScore);
            } else if (parameters.forceLowestBitrate) {
                return compareInts(other.bitrate, this.bitrate);
            } else {
                // If the format are within renderer capabilities, prefer higher values of channel count,
                // sample rate and bit rate in that order. Otherwise, prefer lower values.
                int resultSign = withinRendererCapabilitiesScore == 1 ? 1 : -1;
                if (this.channelCount != other.channelCount) {
                    return resultSign * compareInts(this.channelCount, other.channelCount);
                } else if (this.sampleRate != other.sampleRate) {
                    return resultSign * compareInts(this.sampleRate, other.sampleRate);
                }

                if (this.bitrate < bandwidthEstimate && other.bitrate > bandwidthEstimate) {
                    return 1;
                } else if (this.bitrate > bandwidthEstimate && other.bitrate < bandwidthEstimate) {
                    return -1;
                }
                return resultSign * compareInts(this.bitrate, other.bitrate);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            AudioTrackScore that = (AudioTrackScore) o;

            return withinRendererCapabilitiesScore == that.withinRendererCapabilitiesScore
                    && matchLanguageScore == that.matchLanguageScore
                    && defaultSelectionFlagScore == that.defaultSelectionFlagScore
                    && channelCount == that.channelCount && sampleRate == that.sampleRate
                    && bitrate == that.bitrate;
        }

        @Override
        public int hashCode() {
            int result = withinRendererCapabilitiesScore;
            result = 31 * result + matchLanguageScore;
            result = 31 * result + defaultSelectionFlagScore;
            result = 31 * result + channelCount;
            result = 31 * result + sampleRate;
            result = 31 * result + bitrate;
            return result;
        }
    }

    /**
     * Compares two integers in a safe way and avoiding potential overflow.
     *
     * @param first  The first value.
     * @param second The second value.
     * @return A negative integer if the first value is less than the second. Zero if they are equal.
     * A positive integer if the first value is greater than the second.
     */
    private static int compareInts(int first, int second) {
        return first > second ? 1 : (second > first ? -1 : 0);
    }

    private static final class AudioConfigurationTuple {

        public final int channelCount;
        public final int sampleRate;
        public final String mimeType;

        public AudioConfigurationTuple(int channelCount, int sampleRate, String mimeType) {
            this.channelCount = channelCount;
            this.sampleRate = sampleRate;
            this.mimeType = mimeType;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            AudioConfigurationTuple other = (AudioConfigurationTuple) obj;
            return channelCount == other.channelCount && sampleRate == other.sampleRate
                    && TextUtils.equals(mimeType, other.mimeType);
        }

        @Override
        public int hashCode() {
            int result = channelCount;
            result = 31 * result + sampleRate;
            result = 31 * result + (mimeType != null ? mimeType.hashCode() : 0);
            return result;
        }
    }


    @Override
    public void onBufferedDurationSample(long bufferedDurationUs) {
        this.bufferedDurationMs = bufferedDurationUs/1000;
    }

}
