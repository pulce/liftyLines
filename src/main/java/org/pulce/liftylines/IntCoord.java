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

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public record IntCoord(int y, int x) {

    @Override
    @NotNull
    public String toString() {
        return "(y:" + y + ", x:" + x + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IntCoord other)) return false;
        return y == other.y && x == other.x;
    }

    @Override
    public int hashCode() {
        // Bro, this hash keeps your HashMaps from losing their shit
        return Objects.hash(y, x);
    }

}

