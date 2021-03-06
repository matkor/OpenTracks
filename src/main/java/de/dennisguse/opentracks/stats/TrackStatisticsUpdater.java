/*
 * Copyright 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package de.dennisguse.opentracks.stats;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.time.Duration;

import de.dennisguse.opentracks.content.data.TrackPoint;
import de.dennisguse.opentracks.content.provider.TrackPointIterator;

/**
 * Updater for {@link TrackStatistics}.
 * For updating track {@link TrackStatistics} as new {@link TrackPoint}s are added.
 * NOTE: Some of the locations represent pause/resume separator.
 * NOTE: Has still support for segments (at the moment unused).
 *
 * @author Sandor Dornbush
 * @author Rodrigo Damazio
 */
public class TrackStatisticsUpdater {

    /**
     * The number of elevation readings to smooth to get a somewhat accurate signal.
     */
    @VisibleForTesting
    private static final int ELEVATION_SMOOTHING_FACTOR = 25;

    /**
     * The number of speed reading to smooth to get a somewhat accurate signal.
     */
    @VisibleForTesting
    private static final int SPEED_SMOOTHING_FACTOR = 25;

    private static final String TAG = TrackStatisticsUpdater.class.getSimpleName();
    /**
     * Ignore any acceleration faster than this.
     * Will ignore any speeds that imply acceleration greater than 2g's
     * 2g = 19.6 m/s^2 = 0.0002 m/ms^2 = 0.02 m/(m*ms)
     */
    private static final double MAX_ACCELERATION = 0.02;

    private boolean trackInitialized = false;
    private boolean segmentInitialized = false;

    // The track's statistics
    private final TrackStatistics trackStatistics;

    // A buffer of the recent elevation readings (m)
    private final DoubleRingBuffer elevationBuffer_m = new DoubleRingBuffer(ELEVATION_SMOOTHING_FACTOR);
    // A buffer of the recent speed readings (m/s) for calculating max speed
    private final DoubleRingBuffer speedBuffer_ms = new DoubleRingBuffer(SPEED_SMOOTHING_FACTOR);

    // The current segment's statistics
    private final TrackStatistics currentSegment = new TrackStatistics();
    // Current segment's last trackPoint
    private TrackPoint lastTrackPoint;
    // Current segment's last moving trackPoint
    private TrackPoint lastMovingTrackPoint;

    public TrackStatisticsUpdater() {
        trackStatistics = new TrackStatistics();
    }

    /**
     * Creates a new{@link TrackStatisticsUpdater} with a {@link TrackStatisticsUpdater} already existed.
     *
     * @param trackStatistics a {@link TrackStatisticsUpdater}
     */
    public TrackStatisticsUpdater(TrackStatistics trackStatistics) {
        this.trackStatistics = trackStatistics;
        trackInitialized = true;
    }

    public TrackStatistics getTrackStatistics() {
        // Take a snapshot - we don't want anyone messing with our trackStatistics
        TrackStatistics stats = new TrackStatistics(trackStatistics);
        stats.merge(currentSegment);
        return stats;
    }

    public boolean isTrackInitialized() {
        return trackInitialized;
    }

    /**
     * Adds a trackPoint.
     *
     * @param trackPoint     the trackPoint
     * @param minGPSDistance the min recording distance
     */
    public void addTrackPoint(TrackPoint trackPoint, int minGPSDistance) {
        internalAddTrackPoint(trackPoint, minGPSDistance);
        Log.d(TAG, this.toString());
    }

    private void internalAddTrackPoint(TrackPoint trackPoint, int minGPSDistance) {
        if (!trackInitialized) {
            trackStatistics.setStartTime(trackPoint.getTime());
            trackInitialized = true;
        }
        if (!segmentInitialized) {
            currentSegment.setStartTime(trackPoint.getTime());
            segmentInitialized = true;
        }

        // Always update time
        currentSegment.setStopTime(trackPoint.getTime());
        currentSegment.setTotalTime(Duration.between(currentSegment.getStartTime(), trackPoint.getTime()));

        if (trackPoint.isSegmentStart()) {
            reset(trackPoint);
            return;
        }

        // Process sensor data
        if (trackPoint.hasElevationGain()) {
            currentSegment.addTotalElevationGain(trackPoint.getElevationGain());
        }

        if (trackPoint.hasElevationLoss()) {
            currentSegment.addTotalElevationLoss(trackPoint.getElevationLoss());
        }

        if (trackPoint.hasSensorDistance()) {
            currentSegment.addTotalDistance(trackPoint.getSensorDistance());
        }

        if (trackPoint.isSegmentEnd()) {
            reset(trackPoint);
            return;
        }

        //Update absolute (GPS-based) elevation
        if (trackPoint.hasAltitude()) {
            updateAbsoluteElevation(trackPoint.getAltitude());
        }

        if (lastTrackPoint == null || lastMovingTrackPoint == null) {
            lastTrackPoint = trackPoint;
            lastMovingTrackPoint = trackPoint;
            return;
        }

        if (!trackPoint.hasSensorDistance()) {
            // GPS-based distance/speed
            float movingDistance = lastMovingTrackPoint.distanceToPrevious(trackPoint);
            if (movingDistance < minGPSDistance && !trackPoint.isMoving()) {
                speedBuffer_ms.reset();
                lastTrackPoint = trackPoint;
                return;
            }
            // Update total distance
            currentSegment.addTotalDistance(movingDistance);
        }

        Duration movingTime = Duration.between(lastTrackPoint.getTime(), trackPoint.getTime());
        if (movingTime.isNegative()) {
            lastTrackPoint = trackPoint;
            return;
        }

        // Update moving time
        currentSegment.addMovingTime(movingTime);

        // Update max speed
        if (trackPoint.hasSpeed() && lastTrackPoint.hasSpeed()) {
            updateSpeed(trackPoint, lastTrackPoint);
        }

        lastTrackPoint = trackPoint;
        lastMovingTrackPoint = trackPoint;
    }

    private void reset(TrackPoint trackPoint) {
        trackStatistics.merge(currentSegment);
        currentSegment.reset(trackPoint.getTime());

        lastTrackPoint = null;
        lastMovingTrackPoint = null;
        elevationBuffer_m.reset();
        speedBuffer_ms.reset();
    }

    public void addTrackPoint(TrackPointIterator iterator, int minRecordingDistance) {
        while (iterator.hasNext()) {
            TrackPoint location = iterator.next();
            addTrackPoint(location, minRecordingDistance);
        }
    }

    /**
     * Gets the smoothed elevation over several readings.
     * The elevation readings is noisy so the smoothed elevation is better than the raw elevation for many tasks.
     */
    public double getSmoothedElevation() {
        return elevationBuffer_m.getAverage();
    }

    public double getSmoothedSpeed() {
        return speedBuffer_ms.getAverage();
    }

    /**
     * Updates a speed reading while assuming the user is moving.
     */
    @VisibleForTesting
    private void updateSpeed(@NonNull TrackPoint trackPoint, @NonNull TrackPoint lastTrackPoint) {
        if (!trackPoint.isMoving()) {
            speedBuffer_ms.reset();
        } else if (isValidSpeed(trackPoint, lastTrackPoint)) {
            speedBuffer_ms.setNext(trackPoint.getSpeed());
            if (speedBuffer_ms.getAverage() > currentSegment.getMaxSpeed()) {
                currentSegment.setMaxSpeed(speedBuffer_ms.getAverage());
            }
        } else {
            Log.d(TAG, "Invalid speed. speed: " + trackPoint.getSpeed() + " lastLocationSpeed: " + lastTrackPoint.getSpeed());
        }
    }

    /**
     * Updates an elevation reading.
     */
    private void updateAbsoluteElevation(double elevation) {
        // Update elevation using the smoothed average
        double oldAverage = elevationBuffer_m.getAverage();
        elevationBuffer_m.setNext(elevation);
        double newAverage = elevationBuffer_m.getAverage();

        currentSegment.updateElevationExtremities(newAverage);
    }

    private boolean isValidSpeed(@NonNull TrackPoint trackPoint, @NonNull TrackPoint lastTrackPoint) {
        // There are a lot of noisy speed readings. Do the cheapest checks first, most expensive last.
        if (trackPoint.getSpeed() == 0) {
            return false;
        }

        // The following code will ignore unlikely readings. 128 m/s seems to be an internal android error code.
        if (Math.abs(trackPoint.getSpeed() - 128) < 1) {
            return false;
        }

        // See if the speed seems physically likely. Ignore any speeds that imply acceleration greater than 2g.
        Duration timeDifference = Duration.between(lastTrackPoint.getTime(), trackPoint.getTime());
        double speedDifference = Math.abs(lastTrackPoint.getSpeed() - trackPoint.getSpeed());
        if (speedDifference > MAX_ACCELERATION * timeDifference.toMillis()) {
            return false;
        }

        // Only check if the speed buffer is full. Check that the speed is less than 10X the smoothed average and the speed difference doesn't imply 2g acceleration.
        if (speedBuffer_ms.isFull()) {
            double average = speedBuffer_ms.getAverage();
            double diff = Math.abs(average - trackPoint.getSpeed());
            return (trackPoint.getSpeed() < average * 10) && (diff < MAX_ACCELERATION * timeDifference.toMillis());
        }

        return true;
    }

    @Override
    public String toString() {
        return "TrackStatisticsUpdater{" +
                "trackStatistics=" + trackStatistics +
                '}';
    }
}
