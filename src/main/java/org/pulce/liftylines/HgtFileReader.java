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

import java.io.*;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

public class HgtFileReader {
    public static Logger LOG = Logger.getLogger(HgtFileReader.class.getName());

    public static short[][] readElevationData(Path tilesDir, LiftyBoundingBox boundingBox) {

        // Create all tiles required for the bounding box
        int latStart = (int) Math.floor(boundingBox.minLatitude);
        int latEnd = (int) Math.ceil(boundingBox.maxLatitude) - 1;
        int lonStart = (int) Math.floor(boundingBox.minLongitude);
        int lonEnd = (int) Math.ceil(boundingBox.maxLongitude) - 1;
        List<HgtTile> tiles = new ArrayList<>();
        for (int lat = latStart; lat <= latEnd; lat++) {
            for (int lon = lonStart; lon <= lonEnd; lon++) {
                tiles.add(new HgtTile(lat, lon, boundingBox, tilesDir));
            }
        }

        // Merge the required data from all tiles to one array
        short[][] stacked = new short[boundingBox.getMaskRows()][boundingBox.getMaskCols()]; // Oida, memory city!
        for (HgtTile tile : tiles) {
            tile.dumpDataToArray(stacked);
        }

        return stacked;
    }

    private static class HgtTile {
        public int lat;
        public int lon;
        public Path path;
        public int firstRowInArray;
        public int firstColInArray;

        private static final String BASE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/skadi/";

        private HgtTile(int lat, int lon, LiftyBoundingBox boundingBox, Path tilesDir) {
            this.lat = lat;
            this.lon = lon;
            this.path = getTilePath(lat, lon, tilesDir);
            firstRowInArray = (int) Math.round((boundingBox.maxLatitude - (lat + 1)) * LiftyBoundingBox.SAMPLES_PER_DEGREE);
            firstColInArray = (int) Math.round((lon - boundingBox.minLongitude) * LiftyBoundingBox.SAMPLES_PER_DEGREE);
        }

        private void dumpDataToArray(short[][] array) {
            // total samples per tile side
            final int S = LiftyBoundingBox.SAMPLES_PER_DEGREE + 1;
            // bytes in one full row of S samples
            final int rowBytes = S * Short.BYTES;

            int arrayRows = array.length, arrayCols = array[0].length;

            try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
                MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
                buf.order(ByteOrder.BIG_ENDIAN);

                // tileRow 0 = northmost sample, tileRow S-1 = southmost sample
                for (int tileRow = 0; tileRow < S; tileRow++) {
                    int globalRow = firstRowInArray + tileRow;
                    if (globalRow < 0 || globalRow >= arrayRows) {
                        // skip entire row (all S samples)
                        buf.position(buf.position() + rowBytes);
                        continue;
                    }
                    // read every short in this row
                    for (int tileCol = 0; tileCol < S; tileCol++) {
                        short elev = buf.getShort();
                        int globalCol = firstColInArray + tileCol;
                        if (globalCol >= 0 && globalCol < arrayCols) {
                            array[globalRow][globalCol] = elev;
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Read error while processing " + path, e);
            }
        }

        private static Path getTilePath(int lat, int lon, Path tilesDir) {
            // build normalized name
            char latHem = lat >= 0 ? 'N' : 'S';
            char lonHem = lon >= 0 ? 'E' : 'W';
            String fileName = String.format("%c%02d%c%03d.hgt",
                    latHem, Math.abs(lat),
                    lonHem, Math.abs(lon));

            Path localFile = tilesDir.resolve(fileName);
            if (Files.exists(localFile)) {
                LOG.fine("Using available hgt file " + localFile);
                return localFile;
            }
            // download + decompress in one go
            String awsDir = String.format("%c%02d/", latHem, Math.abs(lat));
            String gzName = fileName + ".gz";
            String url = BASE_URL + awsDir + gzName;
            LOG.info("Downloading " + url);

            try (InputStream in = URI.create(url).toURL().openStream();
                 BufferedInputStream bin = new BufferedInputStream(in);
                 GZIPInputStream gis = new GZIPInputStream(bin);
                 OutputStream out = Files.newOutputStream(localFile,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                byte[] buffer = new byte[8192];
                int len;
                while ((len = gis.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                }
                return localFile;

            } catch (IOException e) {
                throw new UncheckedIOException("Failed to download/decompress " + url, e);
            }
        }
    }
}
