/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.timedetector;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.PhoneTimeSuggestion;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.LocalLog;
import android.util.Slog;
import android.util.TimestampedValue;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.timezonedetector.ArrayMapWithHistory;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An implementation of TimeDetectorStrategy that passes phone and manual suggestions to
 * {@link AlarmManager}. When there are multiple phone sources, the one with the lowest ID is used
 * unless the data becomes too stale.
 *
 * <p>Most public methods are marked synchronized to ensure thread safety around internal state.
 */
public final class TimeDetectorStrategyImpl implements TimeDetectorStrategy {

    private static final boolean DBG = false;
    private static final String LOG_TAG = "SimpleTimeDetectorStrategy";

    /** A score value used to indicate "no score", either due to validation failure or age. */
    private static final int PHONE_INVALID_SCORE = -1;
    /** The number of buckets phone suggestions can be put in by age. */
    private static final int PHONE_BUCKET_COUNT = 24;
    /** Each bucket is this size. All buckets are equally sized. */
    @VisibleForTesting
    static final int PHONE_BUCKET_SIZE_MILLIS = 60 * 60 * 1000;
    /** Phone suggestions older than this value are considered too old. */
    @VisibleForTesting
    static final long PHONE_MAX_AGE_MILLIS = PHONE_BUCKET_COUNT * PHONE_BUCKET_SIZE_MILLIS;

    @IntDef({ ORIGIN_PHONE, ORIGIN_MANUAL })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Origin {}

    /** Used when a time value originated from a telephony signal. */
    @Origin
    private static final int ORIGIN_PHONE = 1;

    /** Used when a time value originated from a user / manual settings. */
    @Origin
    private static final int ORIGIN_MANUAL = 2;

    /**
     * CLOCK_PARANOIA: The maximum difference allowed between the expected system clock time and the
     * actual system clock time before a warning is logged. Used to help identify situations where
     * there is something other than this class setting the system clock.
     */
    private static final long SYSTEM_CLOCK_PARANOIA_THRESHOLD_MILLIS = 2 * 1000;

    /** The number of previous phone suggestions to keep for each ID (for use during debugging). */
    private static final int KEEP_SUGGESTION_HISTORY_SIZE = 30;

    // A log for changes made to the system clock and why.
    @NonNull
    private final LocalLog mTimeChangesLog = new LocalLog(30, false /* useLocalTimestamps */);

    // @NonNull after initialize()
    private Callback mCallback;

    // Used to store the last time the system clock state was set automatically. It is used to
    // detect (and log) issues with the realtime clock or whether the clock is being set without
    // going through this strategy code.
    @GuardedBy("this")
    @Nullable
    private TimestampedValue<Long> mLastAutoSystemClockTimeSet;

    /**
     * A mapping from phoneId to a time suggestion. We typically expect one or two mappings: devices
     * will have a small number of telephony devices and phoneIds are assumed to be stable.
     */
    @GuardedBy("this")
    private ArrayMapWithHistory<Integer, PhoneTimeSuggestion> mSuggestionByPhoneId =
            new ArrayMapWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    @Override
    public void initialize(@NonNull Callback callback) {
        mCallback = callback;
    }

    @Override
    public synchronized void suggestManualTime(@NonNull ManualTimeSuggestion suggestion) {
        final TimestampedValue<Long> newUtcTime = suggestion.getUtcTime();

        if (!validateSuggestionTime(newUtcTime, suggestion)) {
            return;
        }

        String cause = "Manual time suggestion received: suggestion=" + suggestion;
        setSystemClockIfRequired(ORIGIN_MANUAL, newUtcTime, cause);
    }

    @Override
    public synchronized void suggestPhoneTime(@NonNull PhoneTimeSuggestion timeSuggestion) {
        // Empty time suggestion means that telephony network connectivity has been lost.
        // The passage of time is relentless, and we don't expect our users to use a time machine,
        // so we can continue relying on previous suggestions when we lose connectivity. This is
        // unlike time zone, where a user may lose connectivity when boarding a flight and where we
        // do want to "forget" old signals. Suggestions that are too old are discarded later in the
        // detection algorithm.
        if (timeSuggestion.getUtcTime() == null) {
            return;
        }

        // Perform validation / input filtering and record the validated suggestion against the
        // phoneId.
        if (!validateAndStorePhoneSuggestion(timeSuggestion)) {
            return;
        }

        // Now perform auto time detection. The new suggestion may be used to modify the system
        // clock.
        String reason = "New phone time suggested. timeSuggestion=" + timeSuggestion;
        doAutoTimeDetection(reason);
    }

    @Override
    public synchronized void handleAutoTimeDetectionChanged() {
        boolean enabled = mCallback.isAutoTimeDetectionEnabled();
        // When automatic time detection is enabled we update the system clock instantly if we can.
        // Conversely, when automatic time detection is disabled we leave the clock as it is.
        if (enabled) {
            String reason = "Auto time zone detection setting enabled.";
            doAutoTimeDetection(reason);
        } else {
            // CLOCK_PARANOIA: We are losing "control" of the system clock so we cannot predict what
            // it should be in future.
            mLastAutoSystemClockTimeSet = null;
        }
    }

    @Override
    public synchronized void dump(@NonNull PrintWriter pw, @Nullable String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, " ");
        ipw.println("TimeDetectorStrategy:");
        ipw.increaseIndent(); // level 1

        ipw.println("mLastAutoSystemClockTimeSet=" + mLastAutoSystemClockTimeSet);

        ipw.println("Time change log:");
        ipw.increaseIndent(); // level 2
        mTimeChangesLog.dump(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.println("Phone suggestion history:");
        ipw.increaseIndent(); // level 2
        mSuggestionByPhoneId.dump(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.decreaseIndent(); // level 1
        ipw.flush();
    }

    @GuardedBy("this")
    private boolean validateAndStorePhoneSuggestion(@NonNull PhoneTimeSuggestion suggestion) {
        TimestampedValue<Long> newUtcTime = suggestion.getUtcTime();
        if (!validateSuggestionTime(newUtcTime, suggestion)) {
            // There's probably nothing useful we can do: elsewhere we assume that reference
            // times are in the past so just stop here.
            return false;
        }

        int phoneId = suggestion.getPhoneId();
        PhoneTimeSuggestion previousSuggestion = mSuggestionByPhoneId.get(phoneId);
        if (previousSuggestion != null) {
            // We can log / discard suggestions with obvious issues with the reference time clock.
            if (previousSuggestion.getUtcTime() == null
                    || previousSuggestion.getUtcTime().getValue() == null) {
                // This should be impossible given we only store validated suggestions.
                Slog.w(LOG_TAG, "Previous suggestion is null or has a null time."
                        + " previousSuggestion=" + previousSuggestion
                        + ", suggestion=" + suggestion);
                return false;
            }

            long referenceTimeDifference = TimestampedValue.referenceTimeDifference(
                    newUtcTime, previousSuggestion.getUtcTime());
            if (referenceTimeDifference < 0) {
                // The reference time is before the previously received suggestion. Ignore it.
                Slog.w(LOG_TAG, "Out of order phone suggestion received."
                        + " referenceTimeDifference=" + referenceTimeDifference
                        + " previousSuggestion=" + previousSuggestion
                        + " suggestion=" + suggestion);
                return false;
            }
        }

        // Store the latest suggestion.
        mSuggestionByPhoneId.put(phoneId, suggestion);
        return true;
    }

    private boolean validateSuggestionTime(
            @NonNull TimestampedValue<Long> newUtcTime, @NonNull Object suggestion) {
        if (newUtcTime.getValue() == null) {
            Slog.w(LOG_TAG, "Suggested time value is null. suggestion=" + suggestion);
            return false;
        }

        // We can validate the suggestion against the reference time clock.
        long elapsedRealtimeMillis = mCallback.elapsedRealtimeMillis();
        if (elapsedRealtimeMillis < newUtcTime.getReferenceTimeMillis()) {
            // elapsedRealtime clock went backwards?
            Slog.w(LOG_TAG, "New reference time is in the future? Ignoring."
                    + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                    + ", suggestion=" + suggestion);
            return false;
        }
        return true;
    }

    @GuardedBy("this")
    private void doAutoTimeDetection(@NonNull String detectionReason) {
        if (!mCallback.isAutoTimeDetectionEnabled()) {
            // Avoid doing unnecessary work with this (race-prone) check.
            return;
        }

        PhoneTimeSuggestion bestPhoneSuggestion = findBestPhoneSuggestion();

        // Work out what to do with the best suggestion.
        if (bestPhoneSuggestion == null) {
            // There is no good phone suggestion.
            if (DBG) {
                Slog.d(LOG_TAG, "Could not determine time: No best phone suggestion."
                        + " detectionReason=" + detectionReason);
            }
            return;
        }

        final TimestampedValue<Long> newUtcTime = bestPhoneSuggestion.getUtcTime();
        String cause = "Found good suggestion."
                + ", bestPhoneSuggestion=" + bestPhoneSuggestion
                + ", detectionReason=" + detectionReason;
        setSystemClockIfRequired(ORIGIN_PHONE, newUtcTime, cause);
    }

    @GuardedBy("this")
    @Nullable
    private PhoneTimeSuggestion findBestPhoneSuggestion() {
        long elapsedRealtimeMillis = mCallback.elapsedRealtimeMillis();

        // Phone time suggestions are assumed to be derived from NITZ or NITZ-like signals. These
        // have a number of limitations:
        // 1) No guarantee of accuracy ("accuracy of the time information is in the order of
        // minutes") [1]
        // 2) No guarantee of regular signals ("dependent on the handset crossing radio network
        // boundaries") [1]
        //
        // [1] https://en.wikipedia.org/wiki/NITZ
        //
        // Generally, when there are suggestions from multiple phoneIds they should usually
        // approximately agree. In cases where signals *are* inaccurate we don't want to vacillate
        // between signals from two phoneIds. However, it is known for NITZ signals to be incorrect
        // occasionally, which means we also don't want to stick forever with one phoneId. Without
        // cross-referencing across sources (e.g. the current device time, NTP), or doing some kind
        // of statistical analysis of consistency within and across phoneIds, we can't know which
        // suggestions are more correct.
        //
        // For simplicity, we try to value recency, then consistency of phoneId.
        //
        // The heuristic works as follows:
        // Recency: The most recent suggestion from each phone is scored. The score is based on a
        // discrete age bucket, i.e. so signals received around the same time will be in the same
        // bucket, thus applying a loose reference time ordering. The suggestion with the highest
        // score is used.
        // Consistency: If there a multiple suggestions with the same score, the suggestion with the
        // lowest phoneId is always taken.
        //
        // In the trivial case with a single ID this will just mean that the latest received
        // suggestion is used.

        PhoneTimeSuggestion bestSuggestion = null;
        int bestScore = PHONE_INVALID_SCORE;
        for (int i = 0; i < mSuggestionByPhoneId.size(); i++) {
            Integer phoneId = mSuggestionByPhoneId.keyAt(i);
            PhoneTimeSuggestion candidateSuggestion = mSuggestionByPhoneId.valueAt(i);
            if (candidateSuggestion == null) {
                // Unexpected - null suggestions should never be stored.
                Slog.w(LOG_TAG, "Latest suggestion unexpectedly null for phoneId."
                        + " phoneId=" + phoneId);
                continue;
            } else if (candidateSuggestion.getUtcTime() == null) {
                // Unexpected - we do not store empty suggestions.
                Slog.w(LOG_TAG, "Latest suggestion unexpectedly empty. "
                        + " candidateSuggestion=" + candidateSuggestion);
                continue;
            }

            int candidateScore = scorePhoneSuggestion(elapsedRealtimeMillis, candidateSuggestion);
            if (candidateScore == PHONE_INVALID_SCORE) {
                // Expected: This means the suggestion is obviously invalid or just too old.
                continue;
            }

            // Higher scores are better.
            if (bestSuggestion == null || bestScore < candidateScore) {
                bestSuggestion = candidateSuggestion;
                bestScore = candidateScore;
            } else if (bestScore == candidateScore) {
                // Tie! Use the suggestion with the lowest phoneId.
                int candidatePhoneId = candidateSuggestion.getPhoneId();
                int bestPhoneId = bestSuggestion.getPhoneId();
                if (candidatePhoneId < bestPhoneId) {
                    bestSuggestion = candidateSuggestion;
                }
            }
        }
        return bestSuggestion;
    }

    private static int scorePhoneSuggestion(
            long elapsedRealtimeMillis, @NonNull PhoneTimeSuggestion timeSuggestion) {
        // The score is based on the age since receipt. Suggestions are bucketed so two
        // suggestions in the same bucket from different phoneIds are scored the same.
        TimestampedValue<Long> utcTime = timeSuggestion.getUtcTime();
        long referenceTimeMillis = utcTime.getReferenceTimeMillis();
        if (referenceTimeMillis > elapsedRealtimeMillis) {
            // Future times are ignored. They imply the reference time was wrong, or the elapsed
            // realtime clock has gone backwards, neither of which are supportable situations.
            Slog.w(LOG_TAG, "Existing suggestion found to be in the future. "
                    + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                    + ", timeSuggestion=" + timeSuggestion);
            return PHONE_INVALID_SCORE;
        }

        long ageMillis = elapsedRealtimeMillis - referenceTimeMillis;

        // Any suggestion > MAX_AGE_MILLIS is treated as too old. Although time is relentless and
        // predictable, the accuracy of the reference time clock may be poor over long periods which
        // would lead to errors creeping in. Also, in edge cases where a bad suggestion has been
        // made and never replaced, it could also mean that the time detection code remains
        // opinionated using a bad invalid suggestion. This caps that edge case at MAX_AGE_MILLIS.
        if (ageMillis > PHONE_MAX_AGE_MILLIS) {
            return PHONE_INVALID_SCORE;
        }

        // Turn the age into a discrete value: 0 <= bucketIndex < MAX_AGE_HOURS.
        int bucketIndex = (int) (ageMillis / PHONE_BUCKET_SIZE_MILLIS);

        // We want the lowest bucket index to have the highest score. 0 > score >= BUCKET_COUNT.
        return PHONE_BUCKET_COUNT - bucketIndex;
    }

    @GuardedBy("this")
    private void setSystemClockIfRequired(
            @Origin int origin, @NonNull TimestampedValue<Long> time, @NonNull String cause) {

        boolean isOriginAutomatic = isOriginAutomatic(origin);
        if (isOriginAutomatic) {
            if (!mCallback.isAutoTimeDetectionEnabled()) {
                if (DBG) {
                    Slog.d(LOG_TAG, "Auto time detection is not enabled."
                            + " origin=" + origin
                            + ", time=" + time
                            + ", cause=" + cause);
                }
                return;
            }
        } else {
            if (mCallback.isAutoTimeDetectionEnabled()) {
                if (DBG) {
                    Slog.d(LOG_TAG, "Auto time detection is enabled."
                            + " origin=" + origin
                            + ", time=" + time
                            + ", cause=" + cause);
                }
                return;
            }
        }

        mCallback.acquireWakeLock();
        try {
            setSystemClockUnderWakeLock(origin, time, cause);
        } finally {
            mCallback.releaseWakeLock();
        }
    }

    private static boolean isOriginAutomatic(@Origin int origin) {
        return origin == ORIGIN_PHONE;
    }

    @GuardedBy("this")
    private void setSystemClockUnderWakeLock(
            int origin, @NonNull TimestampedValue<Long> newTime, @NonNull Object cause) {

        long elapsedRealtimeMillis = mCallback.elapsedRealtimeMillis();
        boolean isOriginAutomatic = isOriginAutomatic(origin);
        long actualSystemClockMillis = mCallback.systemClockMillis();
        if (isOriginAutomatic) {
            // CLOCK_PARANOIA : Check to see if this class owns the clock or if something else
            // may be setting the clock.
            if (mLastAutoSystemClockTimeSet != null) {
                long expectedTimeMillis = TimeDetectorStrategy.getTimeAt(
                        mLastAutoSystemClockTimeSet, elapsedRealtimeMillis);
                long absSystemClockDifference =
                        Math.abs(expectedTimeMillis - actualSystemClockMillis);
                if (absSystemClockDifference > SYSTEM_CLOCK_PARANOIA_THRESHOLD_MILLIS) {
                    Slog.w(LOG_TAG,
                            "System clock has not tracked elapsed real time clock. A clock may"
                                    + " be inaccurate or something unexpectedly set the system"
                                    + " clock."
                                    + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                                    + " expectedTimeMillis=" + expectedTimeMillis
                                    + " actualTimeMillis=" + actualSystemClockMillis
                                    + " cause=" + cause);
                }
            }
        }

        // Adjust for the time that has elapsed since the signal was received.
        long newSystemClockMillis = TimeDetectorStrategy.getTimeAt(newTime, elapsedRealtimeMillis);

        // Check if the new signal would make sufficient difference to the system clock. If it's
        // below the threshold then ignore it.
        long absTimeDifference = Math.abs(newSystemClockMillis - actualSystemClockMillis);
        long systemClockUpdateThreshold = mCallback.systemClockUpdateThresholdMillis();
        if (absTimeDifference < systemClockUpdateThreshold) {
            if (DBG) {
                Slog.d(LOG_TAG, "Not setting system clock. New time and"
                        + " system clock are close enough."
                        + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                        + " newTime=" + newTime
                        + " cause=" + cause
                        + " systemClockUpdateThreshold=" + systemClockUpdateThreshold
                        + " absTimeDifference=" + absTimeDifference);
            }
            return;
        }

        mCallback.setSystemClock(newSystemClockMillis);
        String logMsg = "Set system clock using time=" + newTime
                + " cause=" + cause
                + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                + " newSystemClockMillis=" + newSystemClockMillis;
        if (DBG) {
            Slog.d(LOG_TAG, logMsg);
        }
        mTimeChangesLog.log(logMsg);

        // CLOCK_PARANOIA : Record the last time this class set the system clock due to an auto-time
        // signal, or clear the record it is being done manually.
        if (isOriginAutomatic(origin)) {
            mLastAutoSystemClockTimeSet = newTime;
        } else {
            mLastAutoSystemClockTimeSet = null;
        }

        // Historically, Android has sent a TelephonyManager.ACTION_NETWORK_SET_TIME broadcast only
        // when setting the time using NITZ.
        if (origin == ORIGIN_PHONE) {
            // Send a broadcast that telephony code used to send after setting the clock.
            // TODO Remove this broadcast as soon as there are no remaining listeners.
            Intent intent = new Intent(TelephonyManager.ACTION_NETWORK_SET_TIME);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra("time", newSystemClockMillis);
            mCallback.sendStickyBroadcast(intent);
        }
    }

    /**
     * Returns the current best phone suggestion. Not intended for general use: it is used during
     * tests to check strategy behavior.
     */
    @VisibleForTesting
    @Nullable
    public synchronized PhoneTimeSuggestion findBestPhoneSuggestionForTests() {
        return findBestPhoneSuggestion();
    }

    /**
     * A method used to inspect state during tests. Not intended for general use.
     */
    @VisibleForTesting
    @Nullable
    public synchronized PhoneTimeSuggestion getLatestPhoneSuggestion(int phoneId) {
        return mSuggestionByPhoneId.get(phoneId);
    }
}
