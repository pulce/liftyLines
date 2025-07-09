/*
 * Copyright 2025 liftyLines
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.pulce.liftylines;

import org.mapsforge.core.model.BoundingBox;

public class LiftyBoundingBox extends BoundingBox {
    public static int SAMPLES_PER_DEGREE = 3600;

    public double originLat;
    public double originLon;

    // Public entry point: raw inputs
    public LiftyBoundingBox(double minLat, double minLon, double maxLat, double maxLon) {
        super(
                validateAndRoundMinLat(minLat, maxLat),
                validateAndRoundMinLon(minLon, maxLon),
                validateAndRoundMaxLat(maxLat, minLat),
                validateAndRoundMaxLon(maxLon, minLon)
        );
        originLat = maxLatitude + 0.5 / SAMPLES_PER_DEGREE;
        originLon = minLongitude - 0.5 / SAMPLES_PER_DEGREE;
    }

    public double getLatitudeFromY(int y) {
        return originLat - (double) y / SAMPLES_PER_DEGREE;
    }

    public double getLongitudeFromX(int x) {
        return originLon + (double) x / SAMPLES_PER_DEGREE;
    }

    public double getDimension() {
        return (this.maxLatitude - this.minLatitude) * (this.maxLongitude - this.minLongitude);
    }

    public int getMaskRows() {
        return (int) Math.round((this.maxLatitude - this.minLatitude) * SAMPLES_PER_DEGREE + 1);
    }

    public int getMaskCols() {
        return (int) Math.round((this.maxLongitude - this.minLongitude) * SAMPLES_PER_DEGREE + 1);
    }

    private static double validateAndRoundMinLat(double minLat, double maxLat) {
        if (minLat >= maxLat) throw new IllegalArgumentException("minLat ≥ maxLat");
        if (minLat < -90 || minLat > 90) throw new IllegalArgumentException("lat out of [-90,90]");
        return roundToSeconds(minLat);
    }

    private static double validateAndRoundMaxLat(double maxLat, double minLat) {
        if (maxLat <= minLat) throw new IllegalArgumentException("maxLat ≤ minLat");
        if (maxLat < -90 || maxLat > 90) throw new IllegalArgumentException("lat out of [-90,90]");
        return roundToSeconds(maxLat);
    }

    private static double validateAndRoundMinLon(double minLon, double maxLon) {
        if (minLon >= maxLon) throw new IllegalArgumentException("minLon ≥ maxLon");
        if (minLon < -180 || minLon > 180) throw new IllegalArgumentException("lon out of [-180,180]");
        return roundToSeconds(minLon);
    }

    private static double validateAndRoundMaxLon(double maxLon, double minLon) {
        if (maxLon <= minLon) throw new IllegalArgumentException("maxLon ≤ minLon");
        if (maxLon < -180 || maxLon > 180) throw new IllegalArgumentException("lon out of [-180,180]");
        return roundToSeconds(maxLon);
    }

    private static double roundToSeconds(double coord) {
        // 🍁 math, baby
        return Math.round(coord * SAMPLES_PER_DEGREE) / (double) SAMPLES_PER_DEGREE;
    }
}
