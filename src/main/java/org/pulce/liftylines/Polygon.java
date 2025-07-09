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

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

public class Polygon {
    public List<Line> lines = new ArrayList<>();
    public ArrayList<Polygon> childs = new ArrayList<>();
    public Polygon parent = null;
    private boolean isOuter = true;
    public int liftyLineTagValue;
    private static long runningNodeId = -1;  // start negative so we don't collide with OSM
    private static long runningWayId = -1;
    private static long runningRelationId = -1;
    private static Map<Long, Boolean> relations = new HashMap<>();

    public static Logger LOG = Logger.getLogger(Polygon.class.getName());

    public Polygon(IntCoord origin, int liftyLineTagValue) {
        IntCoord c1 = new IntCoord(origin.y(), origin.x() + 1);
        lines.add(new Line(c1, Line.LEFT));
        lines.add(new Line(new IntCoord(origin.y(), origin.x()), Line.DOWN));
        this.liftyLineTagValue = liftyLineTagValue;
    }

    public Polygon(int liftyLineTagValue) {
        // Empty polygon
        this.liftyLineTagValue = liftyLineTagValue;
    }

    public void simplifyAndMergeLines() {
        // Simplify lines by making inner corners 45 deg lines
        int index = 2;
        do {
            if (lines.get(index).turnedRight(lines.get(index - 1)) &&
                    // we must avoid U-shapes and long lines
                    (!lines.get(index).isLongLine || !lines.get(index - 1).isLongLine) &&
                    !lines.get(index - 1).turnedRight(lines.get(index - 2)) &&
                    !lines.get(index + 1).turnedRight(lines.get(index))) {
                // merge two lines to avoid inner corner
                lines.get(index - 1).b = lines.get(index).b; // update the end of the previous line
                lines.get(index - 1).direction = lines.get(index - 1).direction + 1; // 45 degree line
                lines.remove(index); // remove the current line
            } else {
                index++; // move to the next line
            }
        } while (index < lines.size() - 1);

        // Merge lines with same direction
        index = 1;
        do {
            if (lines.get(index).direction == lines.get(index - 1).direction) {
                // merge two lines of the same direction
                lines.get(index - 1).b = lines.get(index).b; // update the end of the previous line
                lines.remove(index); // remove the current line
            } else {
                index++; // move to the next line
            }
        } while (index < lines.size());
    }

    public String toOsmJunk(LiftyBoundingBox boundingBox) {
        String lineBreak = System.lineSeparator();
        String indent1 = "  ";
        String indent2 = "    ";
        long startNodeId = runningNodeId;
        long startWayId = runningWayId;
        StringBuilder sb = new StringBuilder();
        Instant now = Instant.now(); // timestamp for all elements

        // 1) emit all nodes
        for (Line line : lines) {
            double lat = boundingBox.getLatitudeFromY(line.a.y());
            double lon = boundingBox.getLongitudeFromX(line.a.x());
            sb.append(indent1)
                    .append(String.format(Locale.US,
                            "<node id=\"%d\" lat=\"%f\" lon=\"%f\" version=\"1\" timestamp=\"%s\" />",
                            runningNodeId--, lat, lon, now))
                    .append(lineBreak);
        }
        sb.append(lineBreak);  // blank line before way

        // 2) emit the way referencing all those nodes (closing the ring)
        sb.append(indent1).append(String.format(
                "<way id=\"%d\" version=\"1\" timestamp=\"%s\">",
                runningWayId--, now)).append(lineBreak);

        // reference them in the same order
        long refId = startNodeId;
        for (int i = 0; i < lines.size(); i++) {
            sb.append(indent2).append(String.format("<nd ref=\"%d\" />", refId--)).append(lineBreak);
        }
        // close the ring by re‐referencing the first node
        sb.append(indent2).append(String.format("<nd ref=\"%d\" />", startNodeId)).append(lineBreak);

        if (parent == null) {
            relations = new HashMap<>(); // reset relations for root nodes
            if (childs.isEmpty()) { // Write tags to the way
                sb.append(indent2).append("<tag k=\"liftyline\" v=\"").append(liftyLineTagValue).append("\" />").append(lineBreak);
                sb.append(indent2).append("<tag k=\"area\" v=\"yes\" />").append(lineBreak);
            }
        }

        // Close the way
        sb.append(indent1).append("</way>").append(lineBreak);
        sb.append(lineBreak);  // blank line before child

        relations.put(startWayId, isOuter);

        if (!childs.isEmpty()) {
            for (Polygon child : childs) {
                sb.append(child.toOsmJunk(boundingBox));
            }
        }

        if (parent == null && !childs.isEmpty()) {// We are the root node, write relations
            sb.append(indent1).append(String.format(
                    "<relation id=\"%d\" version=\"1\" timestamp=\"%s\">",
                    runningRelationId--, now)).append(lineBreak);
            for (Map.Entry<Long, Boolean> entry : relations.entrySet()) {
                sb.append(indent2).append(String.format(
                        "<member type=\"way\" ref=\"%d\" role=\"%s\" />",
                        entry.getKey(), entry.getValue() ? "outer" : "inner")).append(lineBreak);
            }
            sb.append(indent2).append("<tag k=\"type\" v=\"multipolygon\" />").append(lineBreak);
            sb.append(indent2).append("<tag k=\"liftyline\" v=\"").append(liftyLineTagValue).append("\" />").append(lineBreak);
            sb.append(indent1).append("</relation>").append(lineBreak);
        }
        return sb.toString();
    }

    // We assume polygon is already instantiated, thus origin and first two lines are already set
    public ArrayList<IntCoord> tracePolygonFromMask(boolean[][] mask) {
        ArrayList<IntCoord> donutCoords = new ArrayList<>();
        Line lastLine = lines.get(lines.size() - 1);
        IntCoord endPoint = lastLine.b;
        do {
            int y = lastLine.getMaskField().y();
            int x = lastLine.getMaskField().x();
            if (lastLine.direction == Line.RIGHT) {
                // Check if has neighbor down-right
                if (x + 1 < mask[y].length && y + 1 < mask.length && mask[y + 1][x + 1]) {
                    // Next line will be down
                    lastLine = new Line(endPoint, Line.DOWN);
                } else if (x + 1 < mask[y].length && mask[y][x + 1]) {
                    // We have a neighbor to the right
                    lastLine = new Line(endPoint, Line.RIGHT);
                } else {
                    // No neighbor to the right, we have to go up
                    lastLine = new Line(endPoint, Line.UP);
                }
            } else if (lastLine.direction == Line.DOWN) {
                // check if have a neighbor down-left
                if (y + 1 < mask.length && x - 1 < mask[y].length && mask[y + 1][x - 1]) {
                    // Next line will be left
                    lastLine = new Line(endPoint, Line.LEFT);
                } else if (y + 1 < mask.length && mask[y + 1][x]) {
                    // We have a neighbor below
                    lastLine = new Line(endPoint, Line.DOWN);
                } else {
                    // No neighbor below, we have to go right
                    lastLine = new Line(endPoint, Line.RIGHT);
                }
            } else if (lastLine.direction == Line.LEFT) {
                // check if have a neighbor up-left
                if (x - 1 >= 0 && y - 1 >= 0 && mask[y - 1][x - 1]) {
                    // Next line will be up
                    lastLine = new Line(endPoint, Line.UP);
                } else if (x - 1 >= 0 && mask[y][x - 1]) {
                    // We have a neighbor to the left
                    lastLine = new Line(endPoint, Line.LEFT);
                } else {
                    // No neighbor to the left, we have to go down
                    lastLine = new Line(endPoint, Line.DOWN);
                }
            } else if (lastLine.direction == Line.UP) {
                // check if have a neighbor up-right
                if (y - 1 >= 0 && x + 1 < mask[y].length && mask[y - 1][x + 1]) {
                    // Next line will be right
                    lastLine = new Line(endPoint, Line.RIGHT);
                } else if (y - 1 >= 0 && mask[y - 1][x]) {
                    // We have a neighbor above
                    lastLine = new Line(endPoint, Line.UP);
                } else {
                    // No neighbor above, we have to go left
                    lastLine = new Line(endPoint, Line.LEFT);
                }
            } else {
                throw new RuntimeException("Tracing Poylgon failed. Emergency stop.");
            }
            lines.add(lastLine);
            endPoint = lastLine.b;
        } while (!endPoint.equals(lines.get(0).a));

        // Delete that poly from the mask
        ArrayList<Line> sortedLines = new ArrayList<>(lines);
        // Drop horizontal lines
        sortedLines.removeIf(Line::isHorizontal);
        sortedLines.sort(Line.COMPARATOR); // Sort the lines by their coordinate
        // Assert that the arraylist length is at least 2
        if (sortedLines.size() < 2)
            throw new RuntimeException("Polygon has less than 2 lines, emergency stop");
        // Assert that the arraylist length is multiple of 2
        if (sortedLines.size() % 2 != 0)
            throw new RuntimeException("Polygon has an odd number of lines, emergency stop");

        int index = 0;

        // Flip all mask fields between the start and end of each line, watch out for the donut problem
        do {
            IntCoord start = sortedLines.get(index).getMaskField();
            IntCoord end = sortedLines.get(index + 1).getMaskField();
            // Assert that the coordinates are on the same y coordinate
            if (start.y() != end.y()) {
                throw new RuntimeException("Lines are not on the same y coordinate, emergency stop");
            }
            // Flip all mask fields between start and end
            for (int x = Math.min(start.x(), end.x()); x <= Math.max(start.x(), end.x()); x++) {
                if (mask[start.y()][x]) {
                    mask[start.y()][x] = false;
                } else {
                    // We have a donut problem here, i.e. a hole in the mask
                    mask[start.y()][x] = true;
                    donutCoords.add(new IntCoord(start.y(), x));
                }
            }
            index += 2;
        } while (index < sortedLines.size());
        return donutCoords;
    }

    public static ArrayList<Polygon> createPolygonTreeFromMask(boolean[][] mask, int liftyLineTagValue) {
        // Solves the donut problem. Only needed for .osm files, not for map files.
        ArrayList<Polygon> new_polygons = new ArrayList<>();
        Map<IntCoord, Polygon> donuts = new HashMap<>();

        for (int yy = 0; yy < mask.length; yy++) {
            for (int xx = 0; xx < mask[yy].length; xx++) {
                if (mask[yy][xx]) {
                    //LOG.fine("Found polygon " + new_polygons.size() + " at (" + yy + ", " + xx + ")");
                    IntCoord origin = new IntCoord(yy, xx);
                    Polygon polygon = new Polygon(origin, liftyLineTagValue);
                    if (donuts.containsKey(origin)) {
                        Polygon parent = donuts.get(origin);
                        polygon.isOuter = !parent.isOuter; // flip the isOuter flag
                        parent.childs.add(polygon);
                        polygon.parent = parent;
                    } else {
                        new_polygons.add(polygon);
                    }
                    ArrayList<IntCoord> donutCoords = polygon.tracePolygonFromMask(mask);
                    for (IntCoord coord : donutCoords) {
                        donuts.put(coord, polygon);
                    }
                }
            }
        }
        return new_polygons;
    }

    public record PossibleCutPair(Line leftLine, Line rightLine, int score) {
    }

    public Polygon cutPolygon(LiftyBoundingBox boundingBox) {
        // Get only vertical lines, sort
        ArrayList<Line> sortedLines = new ArrayList<>(lines);
        sortedLines.removeIf(Line::isHorizontal);
        sortedLines.sort(Line.COMPARATOR); // Sort the lines by their coordinate

        // Check for cutpoints. For valid cutpoints we must make sure that we have no donut for the cutpoint row, and the row north
        ArrayList<PossibleCutPair> possibleCutPairs = new ArrayList<>();
        int cutIndex = 0;
        do {
            boolean cutable = true;
            int maskRow = sortedLines.get(cutIndex).getMaskField().y();
            ArrayList<IntCoord> donutCoords = new ArrayList<>();
            for (Polygon child : childs) {
                for (Line line : child.lines) {
                    if (line.direction == Line.UP || line.direction == Line.DOWN) {
                        donutCoords.add(line.getMaskField());
                    }
                }
            }
            // We also exclude cases where we have a gap noth
//            for (Line checkLine : lines) {
//                if (checkLine.direction == Line.RIGHT) {
//                    donutCoords.add(checkLine.getMaskField());
//                }
//            }
            for (IntCoord toCheck : donutCoords) {
                if (maskRow == toCheck.y() || (maskRow > 0 && maskRow - 1 == toCheck.y())) {
                    cutable = false;
                    break;
                }
            }
            if (cutable) {
                Line leftLine = sortedLines.get(cutIndex);
                Line rightLine = sortedLines.get(cutIndex + 1);
                int indexLineLeft = lines.indexOf(leftLine);
                int indexLineRight = lines.indexOf(rightLine);
                assert (indexLineLeft < indexLineRight);
                assert (leftLine.direction == Line.DOWN);
                int numberOfLinesInner = Math.abs(indexLineRight - indexLineLeft);
                int numberOfLinesOuter = lines.size() - numberOfLinesInner;
                possibleCutPairs.add(new PossibleCutPair(leftLine, rightLine, Math.min(numberOfLinesInner, numberOfLinesOuter)));
            }
            cutIndex += 2;
        } while (cutIndex < sortedLines.size() - 2);

        if (possibleCutPairs.isEmpty()) {
            LOG.fine("Large polygon could not be cut");
            return null;
        }

        // find the cutting point with highest score
        PossibleCutPair bestCutPair = possibleCutPairs.stream()
                .max(Comparator.comparingInt(PossibleCutPair::score))
                .orElse(null);

        if (bestCutPair.score < 50) {
            LOG.fine("Large polygon could not be cut, score is too low");
            return null;
        }

        LOG.fine("Cutting polygon at " + boundingBox.getLatitudeFromY(bestCutPair.leftLine.a.y()) + ",   " +
                boundingBox.getLongitudeFromX(bestCutPair.leftLine.a.x()));

        // Grab all lines for a new polygon
        int lowerIndex = Math.min(lines.indexOf(bestCutPair.leftLine), lines.indexOf(bestCutPair.rightLine));
        int upperIndex = Math.max(lines.indexOf(bestCutPair.leftLine), lines.indexOf(bestCutPair.rightLine));
        // where we cut matters, inclusive or exclusive. We wanna cut north.
        if (lines.indexOf(bestCutPair.leftLine) == lowerIndex) {
            // The new poly must have those lines
            upperIndex += 1;
        } else {
            // The lines stay with old poly
            lowerIndex += 1;
        }
        // Bring the lines package in correct order for both polys
        List<Line> oldLinesFirst = lines.subList(0, lowerIndex);
        ArrayList<Line> newLines = new ArrayList<>(lines.subList(lowerIndex, upperIndex));
        ArrayList<Line> oldLines = new ArrayList<>(lines.subList(upperIndex, lines.size()));
        oldLines.addAll(oldLinesFirst);


        // Replace lines of old poly
        lines = trimHorizontals(oldLines);

        Polygon newPoly = new Polygon(liftyLineTagValue);
        newPoly.isOuter = isOuter;
        newPoly.lines = trimHorizontals(newLines);

        // check donuts. Since we don't cut donuts, it is enough to check if one maskField is in poly
        newLines.removeIf(Line::isHorizontal);
        newLines.sort(Line.COMPARATOR); // Sort the lines by their coordinate

        if (newLines.isEmpty()) {
            LOG.warning("Empty polygon!!!!!!");
        }
        for (int i = childs.size() - 1; i >= 0; i--) {
            IntCoord checkThis = childs.get(i).lines.get(0).getMaskField();
            cutIndex = 0;
            do {
                int leftBound = newLines.get(cutIndex).getMaskField().x();
                int rightBound = newLines.get(cutIndex + 1).getMaskField().x();
                if (checkThis.y() == newLines.get(cutIndex).getMaskField().y() &&
                        checkThis.x() > leftBound &&
                        checkThis.x() < rightBound) {
                    // Transplant this
                    childs.get(i).parent = newPoly;
                    newPoly.childs.add(childs.get(i));
                    childs.remove(i);  // safe because we’re going backwards
                    break;
                }
                cutIndex += 2;
            } while (cutIndex < newLines.size() - 2);
        }
        return newPoly;

    }

    public static ArrayList<Line> trimHorizontals(ArrayList<Line> lines) {
        int start = 0;
        int end = lines.size() - 1;
        // Move start index forward
        while (start <= end && (lines.get(start).isHorizontal())) {
            start++;
        }
        // Move end index backward
        while (end >= start && (lines.get(end).isHorizontal())) {
            end--;
        }
        ArrayList<Line> trimmed = new ArrayList<>(lines.subList(start, end + 1));
        // Add new line to close the gap
        IntCoord secEndPoint = trimmed.get(0).a;
        IntCoord secStartPoint = trimmed.get(trimmed.size() - 1).b;
        int secDirection = secStartPoint.x() < secEndPoint.x() ? Line.RIGHT : Line.LEFT;
        trimmed.add(new Line(secStartPoint, secEndPoint, secDirection));
        return trimmed;
    }

}
