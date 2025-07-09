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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

public class TpiCalculator {

    private static int ry, rx, diamY, count;
    private static int[] dxArr;

    public static boolean[][][] createMasksFromElevationData(short[][] elev, double[] cutoffs, LiftyBoundingBox boundingBox, float radiusSmall, float radiusLarge, float mountainCutoff) {
        float[][] tpiSmall = parallelCalcTPI(elev, radiusSmall, boundingBox.minLatitude, mountainCutoff);
        float[][] tpiLarge = parallelCalcTPI(elev, radiusLarge, boundingBox.minLatitude, mountainCutoff);
        int rows = tpiSmall.length, cols = tpiSmall[0].length;
        // combined tpi: sqr (tpiSmall^2 + tpiLarge^2)
        float[][] promClean = new float[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                float s = tpiSmall[i][j], l = tpiLarge[i][j];
                promClean[i][j] = (float) Math.sqrt(s * s + l * l);
            }
        }
        tpiSmall = null;
        tpiLarge = null;
        System.gc();
        // one mask for each cutoff
        boolean[][][] masks = new boolean[cutoffs.length][rows][cols];
        for (int k = 0; k < cutoffs.length; k++) {
            double cutoff = cutoffs[k];
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    masks[k][i][j] = promClean[i][j] > cutoff;
                }
            }
        }
        promClean = null;
        System.gc();
        return masks;
    }

    private static void initEllipseMask(double radiusLat, double radiusLon) {
        ry = (int) Math.round(radiusLat);
        rx = (int) Math.round(radiusLon);
        diamY = 2 * ry + 1;

        // Build boolean mask and derive per-row horizontal extents
        boolean[][] mask = new boolean[diamY][2 * rx + 1];
        for (int dy = -ry; dy <= ry; dy++) {
            for (int dx0 = -rx; dx0 <= rx; dx0++) {
                double nx = dx0 / radiusLon;
                double ny = dy / radiusLat;
                if (nx * nx + ny * ny <= 1.0) {
                    mask[dy + ry][dx0 + rx] = true;
                }
            }
        }

        dxArr = new int[diamY];
        count = 0;
        for (int i = 0; i < diamY; i++) {
            int leftIdx = 2 * rx, rightIdx = -1;
            for (int j = 0; j < mask[i].length; j++) {
                if (mask[i][j]) {
                    count++;
                    leftIdx = Math.min(leftIdx, j);
                    rightIdx = Math.max(rightIdx, j);
                }
            }
            dxArr[i] = rightIdx - rx;
        }
    }


    static class ChunkResult {
        final int startRow;
        final float[][] chunk;

        ChunkResult(int s, float[][] c) {
            startRow = s;
            chunk = c;
        }
    }

    private static ChunkResult calcChunk(short[][] data, int startRow, int endRow) {

        int rows = data.length;
        int cols = data[0].length;
        float[][] meanChunk = new float[endRow - startRow][cols];
        for (float[] row : meanChunk) Arrays.fill(row, Float.NaN);

        // Circular buffer for prefix sums
        int[][] buffer = new int[diamY][cols];
        int head = 0;

        // Preload first diamY rows: rows from (startRow - ry) to (startRow + ry)
        for (int d = 0; d < diamY; d++) {
            int y = startRow - ry + d;
            int bufIdx = (head + d) % diamY;
            if (y >= 0 && y < rows) {
                int sum = 0;
                for (int x = 0; x < cols; x++) {
                    sum += data[y][x];
                    buffer[bufIdx][x] = sum;
                }
            } else {
                throw new IllegalStateException("Out of bounds during tpi calculations. Bbox too tiny or radii too big?");
            }
        }

        // Main loop: compute each output row and slide the buffer
        for (int i = startRow; i < endRow; i++) {
            if (i - ry < 0 || i + ry >= rows) {
                continue;
            }
            int outRowIdx = i - startRow;
            for (int j = rx; j < cols - rx; j++) {
                long sum = 0;
                for (int d = 0; d < diamY; d++) {
                    int bufIdx = (head + d) % diamY;
                    int left = j - dxArr[d];
                    int right = j + dxArr[d];
                    sum += buffer[bufIdx][right]
                            - (left > 0 ? buffer[bufIdx][left - 1] : 0);
                }
                meanChunk[outRowIdx][j] = (float) (sum / (double) count);
            }
            // Slide buffer: remove oldest, add next row
            head = (head + 1) % diamY;
            int newY = i + ry + 1;
            int fillIdx = (head + diamY - 1) % diamY;
            if (newY >= 0 && newY < rows) {
                int sum = 0;
                for (int x = 0; x < cols; x++) {
                    sum += data[newY][x];
                    buffer[fillIdx][x] = sum;
                }
            } else {
                Arrays.fill(buffer[fillIdx], 0);
            }
        }
        return new ChunkResult(startRow, meanChunk);
    }

    public static float[][] parallelCalcTPI(short[][] data, double radius, double minLat,
                                            Float mountainCutoff) {
        int procs = Runtime.getRuntime().availableProcessors();
        double radiusLon = radius / Math.cos(Math.toRadians(minLat));
        initEllipseMask(radius, radiusLon);
        int rows = data.length;
        int ry = (int) Math.round(radius);
        int[] cuts = new int[procs + 1];
        for (int i = 0; i <= procs; i++) cuts[i] = ry + i * (rows - 2 * ry) / procs;
        ExecutorService exec = Executors.newFixedThreadPool(procs);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            exec.shutdown(); // stop accepting new tasks
            try {
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                    exec.shutdownNow(); // force-kill lingering tasks
                }
            } catch (InterruptedException ignored) {
                exec.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));
        List<Future<ChunkResult>> futures = new ArrayList<>();
        for (int i = 0; i < procs; i++) {
            int s = cuts[i], e = cuts[i + 1];
            futures.add(exec.submit(() -> calcChunk(data, s, e)));
        }
        // collect results
        float[][] meanData;
        meanData = new float[rows][data[0].length];
        for (float[] row : meanData) Arrays.fill(row, Float.NaN);

        try {
            for (Future<ChunkResult> f : futures) {
                ChunkResult r = f.get();
                for (int i = 0; i < r.chunk.length; i++) {
                    System.arraycopy(r.chunk[i], 0, meanData[r.startRow + i], 0, r.chunk[i].length);
                }
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during parallel tpi calculation", e);
        } catch (ExecutionException e) {
            exec.shutdownNow();
            throw new RuntimeException("Execution aborted during parallel tpi calculation", e);
        } finally {
            exec.shutdown();
        }

        // compute final tpi
        int cols = data[0].length;
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                float val = data[i][j] - meanData[i][j];
                if (mountainCutoff != null && data[i][j] > mountainCutoff) val = 0;
                meanData[i][j] = val > 0 ? val : 0;
            }
        }
        return meanData;
    }

}

