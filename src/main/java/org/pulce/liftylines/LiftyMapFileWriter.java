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

import org.mapsforge.map.writer.RAMTileBasedDataProcessor;
import org.mapsforge.map.writer.model.EncodingChoice;
import org.mapsforge.map.writer.model.MapWriterConfiguration;
import org.mapsforge.map.writer.model.TileBasedDataProcessor;

import org.openstreetmap.osmosis.core.domain.common.SimpleTimestampContainer;
import org.openstreetmap.osmosis.core.domain.common.TimestampContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.*;

public class LiftyMapFileWriter {

    public static Logger LOG = Logger.getLogger(LiftyMapFileWriter.class.getName());
    public static Logger PROGRESS = Logger.getLogger("org.pulce.liftylines.progress");

    public long runningNodeId = 1;
    public long runningWayId = 1;
    public int version = 1;
    public long changeSetId = 1;

    // MapWriterConfiguration
    public TimestampContainer tsContainer;
    public OsmUser osmUser = new OsmUser(1337, "lifty");
    public MapWriterConfiguration config;

    private final TileBasedDataProcessor processor;
    private final FileManager fileManager;
    private final LiftyBoundingBox boundingBox;

    private final List<Node> reusableNodes = new ArrayList<>();
    private final List<WayNode> reusableWayNodes = new ArrayList<>();

    public LiftyMapFileWriter(FileManager fileManager, LiftyBoundingBox boundingBox, String zoomString, int simplification, byte simplificationMaxZoom) {
        this.boundingBox = boundingBox;
        // Delete the old file if it exists
        this.fileManager = fileManager;
        File oldFile = fileManager.mapOutputFile.toFile();
        if (oldFile.exists() && !oldFile.delete()) {
            throw new UncheckedIOException("Failed to delete old file: " + oldFile.getAbsolutePath(), new IOException());
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        tsContainer = new SimpleTimestampContainer(Date.from(now));
        config = new MapWriterConfiguration();
        config.setFileSpecificationVersion(5);
        config.setOutputFile(fileManager.mapOutputFile.toFile());
        config.loadTagMappingFile(fileManager.tagMappingFile.toString());
        config.setTagValues(true);
        config.addZoomIntervalConfiguration(zoomString);
        config.addMapStartZoom(null);
        config.setBboxConfiguration(boundingBox);
        config.setThreads(8);
        config.setWriterVersion("liftylines");
        config.setEncodingChoice(EncodingChoice.fromString("UTF-8"));
        config.setDataProcessorType("RamTileBasedDataProcessor");
        config.setSimplification(simplification);
        config.setSimplificationMaxZoom(simplificationMaxZoom);
        config.validate();
        processor = RAMTileBasedDataProcessor.newInstance(config);
        //processor = HDTileBasedDataProcessor.newInstance(config); // Does not help with memory issues
    }

    public void writeMapFileFromMasks(boolean[][][] masks) {
        int polyCount = 0;
        for (int i = 0; i < masks.length; i++) {
            boolean[][] mask = masks[i];
            for (int yy = 0; yy < mask.length; yy++) {
                for (int xx = 0; xx < mask[yy].length; xx++) {
                    if (mask[yy][xx]) {
                        Polygon polygon = new Polygon(new IntCoord(yy, xx), i + 1);
                        polygon.tracePolygonFromMask(mask);
                        processPolygon(polygon);
                        polyCount++;
                        if (polyCount % 1000 == 0) {
                            PROGRESS.info(("Processed " + polyCount + " polygons"));
                        }
                    }
                }
            }
        }
        closeAndWrite();
    }

    public void processPolygon(Polygon polygon) {
        // Experimental: cut polygons
//        while (polygon.lines.size() > 1200) {
//            Polygon newPoly = polygon.cutPolygon(boundingBox);
//            if (newPoly == null) { // not cutable
//                break;
//            } else {
//                processPolygon(newPoly);
//            }
//        }

        polygon.simplifyAndMergeLines();
        if (polygon.lines.size() > 1000) {
            LOG.finer("large polygon has " + polygon.lines.size() + " lines");
        }
        reusableNodes.clear();
        reusableWayNodes.clear();

        for (Line line : polygon.lines) {
            CommonEntityData entityData = new CommonEntityData(runningNodeId++, version, tsContainer, osmUser, changeSetId);
            Node osmNode = new Node(entityData, boundingBox.getLatitudeFromY(line.a.y()), boundingBox.getLongitudeFromX(line.a.x()));
            reusableNodes.add(osmNode);
            processor.addNode(osmNode);
            reusableWayNodes.add(new WayNode(osmNode.getId()));
        }
        reusableNodes.add(reusableNodes.get(0));
        reusableWayNodes.add(new WayNode(reusableNodes.get(0).getId())); // close the ring
        CommonEntityData entityData = new CommonEntityData(runningWayId++, version, tsContainer, osmUser, changeSetId);
        entityData.getTags().add(new Tag("liftyline", "" + polygon.liftyLineTagValue));
        Way osmWay = new Way(entityData, reusableWayNodes);
        processor.addWay(osmWay);
    }

    public void closeAndWrite() {
        processor.complete();
        processor.close();
        try {
            MapsforgeMapFileWriter.writeFile(config, processor);
            MapsforgeMapFileWriter.release();
        } catch (IOException e) {
            throw new UncheckedIOException("Error writing map to file " + fileManager.mapOutputFile, e);
        }
    }
}
