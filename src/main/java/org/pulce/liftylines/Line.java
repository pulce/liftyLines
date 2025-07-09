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

import java.util.Comparator;

public class Line {
    public IntCoord a, b;
    public static final int UP = 0, RIGHT = 2, DOWN = 4, LEFT = 6;
    public int direction;
    public boolean isLongLine = false;

    public Line(IntCoord a, int direction) {
        this.a = a;
        this.direction = direction;
        int endX = a.x();
        int endY = a.y();
        if (direction == UP) --endY;
        if (direction == DOWN) ++endY;
        if (direction == RIGHT) ++endX;
        if (direction == LEFT) --endX;
        this.b = new IntCoord(endY, endX);
    }

    public Line(IntCoord a, IntCoord b, int direction) { // Constructor only used when cutting polys.
        this.a = a;
        this.b = b;
        this.direction = direction;
        this.isLongLine = true;
    }

    public boolean isHorizontal() {
        return direction == Line.RIGHT || direction == Line.LEFT;
    }

    public IntCoord getMaskField() {
        if (direction == UP) return new IntCoord(a.y() - 1, a.x() - 1);
        if (direction == DOWN) return new IntCoord(a.y(), a.x());
        if (direction == LEFT) return new IntCoord(b.y(), b.x());
        if (direction == RIGHT) return new IntCoord(b.y() - 1, b.x() - 1);
        throw new IllegalStateException("Invalid line direction: " + direction);
    }

    public boolean turnedRight(Line lastLine) {
        return direction - lastLine.direction == 2 || direction - lastLine.direction == -6;
    }

    // Comparator for sorting: first by y, then by x
    public static final java.util.Comparator<Line> COMPARATOR = Comparator.comparingInt((Line l) -> l.getMaskField().y()).thenComparingInt(l -> l.a.x());

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Line l)) return false;
        // undirected equality: same endpoints regardless of order
        return (a.equals(l.a) && b.equals(l.b)) || (a.equals(l.b) && b.equals(l.a));
    }

    @Override
    public int hashCode() {
        // undirected hash: order-independent combination
        return a.hashCode() ^ b.hashCode();
    }

    @Override
    public String toString() {
        return a + "→" + b;
    }
}
