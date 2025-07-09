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

import org.openstreetmap.osmosis.core.Osmosis;

import java.io.*;
import java.util.ArrayList;
import java.util.logging.Logger;

public class LiftyOsmFileWriter {

    public static Logger PROGRESS = Logger.getLogger("org.pulce.liftylines.progress");
    public static Logger LOG = Logger.getLogger(LiftyOsmFileWriter.class.getName());

    FileManager fileManager;
    LiftyBoundingBox boundingBox;

    public LiftyOsmFileWriter(FileManager fileManager, LiftyBoundingBox boundingBox) {
        this.fileManager = fileManager;
        this.boundingBox = boundingBox;
    }

    public void writeOsmFileFromMasks(boolean[][][] masks) {
        ArrayList<ArrayList<Polygon>> allPolygons = new ArrayList<>();
        for (int i = 0; i < masks.length; i++) {
            ArrayList<Polygon> polygons = Polygon.createPolygonTreeFromMask(masks[i], i + 1);
            allPolygons.add(polygons);
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileManager.osmOutputFile.toFile()))) {
            // header (don’t @ me, needs single quotes here 🤷‍️)
            writer.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
            writer.write("<osm version=\"0.6\" generator=\"liftyLines\">\n");
            writer.write("  <bounds minlat=\"" + boundingBox.minLatitude +
                    "\" minlon=\"" + boundingBox.minLongitude +
                    "\" maxlat=\"" + boundingBox.maxLatitude +
                    "\" maxlon=\"" + boundingBox.maxLongitude +
                    "\" origin=\"0.47\" />\n\n");

            int totalLines = 0;
            int maxNumberOfLines = 0;
            int polyCount = 0;
            for (ArrayList<Polygon> polygons : allPolygons) {
                for (int i = 0; i < polygons.size(); i++) {
//                    while (polygons.get(i).lines.size() > 1200) {   // cutting polys does not help
//                        Polygon newPoly = polygons.get(i).cutPolygon(boundingBox);
//                        if (newPoly == null) { // not cutable
//                            break;
//                        } else {
//                            polygons.add(newPoly);
//                        }
//                    }
                    polygons.get(i).simplifyAndMergeLines();
                    totalLines += polygons.get(i).lines.size();
                    if (polygons.get(i).lines.size() > maxNumberOfLines)
                        maxNumberOfLines = polygons.get(i).lines.size();
                    String xml = polygons.get(i).toOsmJunk(boundingBox);
                    writer.write(xml);
                    writer.write("\n");
                    if (++polyCount % 1000 == 0) {
                        PROGRESS.info(("Processed " + polyCount + " polygons"));
                    }
                }
            }
            LOG.fine("Processed " + polyCount + " polygons in total.");
            LOG.fine(("Resulting number of lines in map:  " + totalLines));
            LOG.fine(("N.o. lines of the biggest polygon: " + maxNumberOfLines));

            // close it out
            writer.write("</osm>\n");
            writer.flush();

        } catch (IOException e) {
            throw new UncheckedIOException("Error writing to file " + fileManager.osmOutputFile, e); // well, somebody’s gotta handle it…
        }
    }

    public void writeMapFileFromOsm(String zoomString) {
        System.setProperty(
                "org.java.plugin.boot.pluginsRepositories",
                new File("lib").toURI().toString()
        );

        String[] osmosisArgs = {
                "-quiet",
                "--read-xml", "file=" + fileManager.osmOutputFile,
                "--buffer",
                "--mapfile-writer", "file=" + fileManager.mapOutputFile,
                //"type=ram",                  // or "hd"
                "tag-conf-file=" + fileManager.tagMappingFile,
                "tag-values=true",
                "zoom-interval-conf=" + zoomString,
                "threads=8"
        };
        Osmosis.run(osmosisArgs);
    }

}
