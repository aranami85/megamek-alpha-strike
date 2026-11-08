/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.common.alphaStrike.play;

import megamek.common.alphaStrike.ASMessages;
import java.util.ArrayList;
import java.util.List;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;

/**
 * The hex map as Alpha Strike sees it (ASCE "Converting Alpha Strike to Hex Maps", pp. 68-71): 1 hex = 2", 1 level
 * = 1". Hexes are numbered i = y * width + x (MegaMek's odd-columns-down layout). Holds what the rules need per hex
 * (level, woods, water depth, rough, building height), distances, hex lines and line of sight.
 *
 * This is a straight port of the Python Alpha Strike simulator (as_hexmap.py / as_core board.rs), so both give the
 * same answers.
 */
public class ASBoard {

    public static final int TABLE_RANGE = 21;
    public static final int WOODS_BLOCK_AT = 3;

    public final int width;
    public final int height;
    public final int n;
    final int[] elev;
    final int[] woods;
    final int[] water;
    final boolean[] rough;
    final int[] building;
    private final int[] cubeQ;
    private final int[] cubeR;
    private final byte[] losCache; // n*n, -1 = not worked out yet; else blocked*4 + woods*2 + partial

    /** The board as Alpha Strike sees it (levels, woods, water, rough, buildings), read from a MegaMek board. */
    public ASBoard(Board board) {
        width = board.getWidth();
        height = board.getHeight();
        n = width * height;
        elev = new int[n];
        woods = new int[n];
        water = new int[n];
        rough = new boolean[n];
        building = new int[n];
        cubeQ = new int[n];
        cubeR = new int[n];
        for (int i = 0; i < n; i++) {
            int x = i % width;
            int y = i / width;
            cubeQ[i] = x;
            cubeR[i] = y - (x - (x & 1)) / 2;
            Hex hex = board.getHex(x, y);
            if (hex == null) {
                continue;
            }
            elev[i] = hex.getLevel();
            int w = Math.max(hex.terrainLevel(Terrains.WOODS), hex.terrainLevel(Terrains.JUNGLE));
            woods[i] = Math.max(0, Math.min(w, 3));
            water[i] = Math.max(0, hex.terrainLevel(Terrains.WATER));
            rough[i] = hex.containsTerrain(Terrains.ROUGH) || hex.containsTerrain(Terrains.RUBBLE)
                  || hex.containsTerrain(Terrains.SWAMP) || hex.containsTerrain(Terrains.MUD)
                  || hex.containsTerrain(Terrains.SAND) || hex.containsTerrain(Terrains.TUNDRA)
                  || hex.containsTerrain(Terrains.SNOW) || hex.containsTerrain(Terrains.ICE)
                  || hex.containsTerrain(Terrains.RAPIDS);
            if (hex.containsTerrain(Terrains.BUILDING)) {
                building[i] = Math.max(1, hex.terrainLevel(Terrains.BLDG_ELEV));
            }
        }
        losCache = new byte[n * n];
        java.util.Arrays.fill(losCache, (byte) -1);
    }

    // --- coordinates ---------------------------------------------------------------

    /** The hex as the simulator stores it: {elevation, woods, water depth, rough (0/1), building height}. */
    public int[] hexRecord(int i) {
        return new int[] { elev[i], woods[i], water[i], rough[i] ? 1 : 0, building[i] };
    }

    /** The hex index (y * width + x) of a position. */
    public int index(Coords c) {
        return c.getY() * width + c.getX();
    }

    /** The position of a hex index. */
    public Coords coords(int i) {
        return new Coords(i % width, i / width);
    }

    /** True if x/y is on the board. */
    public boolean contains(int x, int y) {
        return (x >= 0) && (x < width) && (y >= 0) && (y < height);
    }

    /** The level a unit stands on in this hex (ground level minus water depth). */
    public int floor(int i) {
        return elev[i] - water[i];
    }

    /** The height of the solid things in this hex (ground plus buildings), for line of sight. */
    public int top(int i) {
        return elev[i] + building[i];
    }

    /** True if ground units can enter this hex. */
    public boolean passable(int i) {
        return (building[i] == 0) && (water[i] <= 1);
    }

    /** Distance in hexes between two hexes. */
    public int distance(int a, int b) {
        int dq = Math.abs(cubeQ[a] - cubeQ[b]);
        int dr = Math.abs(cubeR[a] - cubeR[b]);
        int ds = Math.abs((cubeQ[a] + cubeR[a]) - (cubeQ[b] + cubeR[b]));
        return Math.max(dq, Math.max(dr, ds));
    }

    private int fromCube(int q, int r) {
        int x = q;
        int y = r + Math.floorDiv(x - (x & 1), 2);
        return contains(x, y) ? y * width + x : -1;
    }

    /** Neighbor in direction 0-5 (0 = north, clockwise), -1 = off the map. */
    public int neighbor(int i, int dir) {
        int x = i % width;
        int y = i / width;
        int odd = x & 1;
        int[][] steps = { { 0, -1 }, { 1, -1 + odd }, { 1, odd }, { 0, 1 }, { -1, odd }, { -1, -1 + odd } };
        int nx = x + steps[dir][0];
        int ny = y + steps[dir][1];
        return contains(nx, ny) ? ny * width + nx : -1;
    }

    /** Every hex within radius of center (same order as the Python simulator). */
    public List<Integer> within(int center, int radius) {
        List<Integer> out = new ArrayList<>();
        int q0 = cubeQ[center];
        int r0 = cubeR[center];
        for (int dq = -radius; dq <= radius; dq++) {
            int lo = Math.max(-radius, -dq - radius);
            int hi = Math.min(radius, -dq + radius);
            for (int dr = lo; dr <= hi; dr++) {
                int i = fromCube(q0 + dq, r0 + dr);
                if (i >= 0) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    // --- facing ----------------------------------------------------------------------

    /** Angle from hex a to hex b in degrees, 0 = north (up), clockwise. */
    public double bearing(int a, int b) {
        double xa = 1.5 * (a % width);
        double ya = Math.sqrt(3) * ((a / width) + 0.5 * ((a % width) & 1));
        double xb = 1.5 * (b % width);
        double yb = Math.sqrt(3) * ((b / width) + 0.5 * ((b % width) & 1));
        double deg = Math.toDegrees(Math.atan2(xb - xa, -(yb - ya)));
        return pyMod(deg, 360.0);
    }

    static double pyMod(double a, double b) {
        double r = a % b;
        if ((r != 0) && ((r < 0) != (b < 0))) {
            r += b;
        }
        return r;
    }

    /** How far (in degrees) a bearing is from a facing; more than 90 means outside the front arc. */
    public static double angleOff(int facing, double bearing) {
        double d = pyMod(bearing - facing * 60, 360.0);
        return (d <= 180) ? d : 360 - d;
    }

    /** Front arc = the forward half (180 degrees). */
    public boolean inFrontArc(int a, int facing, int b) {
        return angleOff(facing, bearing(a, b)) <= 90.01;
    }

    /** Rear arc = the 60-degree wedge behind the unit (its rear hexside). */
    public boolean inRearArc(int a, int facing, int b) {
        return angleOff(facing, bearing(a, b)) >= 149.99;
    }

    /** The facing (0-5) that points from one hex toward another. */
    public int faceToward(int a, int b) {
        return (int) Math.floorMod((long) Math.floor((bearing(a, b) + 30) / 60), 6L);
    }

    // --- hex lines and line of sight ---------------------------------------------------

    private static double roundEven(double x) {
        return Math.rint(x); // Java rint = round half to even, like Python's round()
    }

    /** Hexes from a to b (both included), nudged a hair to one side; -1 = off the map. */
    public int[] line(int a, int b, double nudge) {
        int dist = distance(a, b);
        int[] out = new int[dist + 1];
        out[0] = a;
        if (dist == 0) {
            return out;
        }
        double fq = cubeQ[a] + 1e-6 * nudge;
        double fr = cubeR[a] + 2e-6 * nudge;
        double dq = (cubeQ[b] - cubeQ[a]) / (double) dist;
        double dr = (cubeR[b] - cubeR[a]) / (double) dist;
        int len = 1;
        for (int k = 1; k <= dist; k++) {
            double q = fq + dq * k;
            double r = fr + dr * k;
            double s = -q - r;
            double rq = roundEven(q);
            double rr = roundEven(r);
            double rs = roundEven(s);
            double eq = Math.abs(rq - q);
            double er = Math.abs(rr - r);
            double es = Math.abs(rs - s);
            if ((eq > er) && (eq > es)) {
                rq = -rr - rs;
            } else if (er > es) {
                rr = -rq - rs;
            }
            int iq = (int) rq;
            int ir = (int) rr;
            int y = ir + Math.floorDiv(iq - (iq & 1), 2);
            int i = contains(iq, y) ? y * width + iq : -1;
            if ((out[len - 1] != i) || (i < 0)) {
                out[len++] = i;
            }
        }
        return (len == out.length) ? out : java.util.Arrays.copyOf(out, len);
    }

    /** The first of the two nudged lines from a to b (always worked out from the lower hex number). */
    public int[] firstLine(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        int[] l = line(lo, hi, 1.0);
        if (a != lo) {
            reverse(l);
        }
        return l;
    }

    private static void reverse(int[] l) {
        for (int i = 0, j = l.length - 1; i < j; i++, j--) {
            int t = l[i];
            l[i] = l[j];
            l[j] = t;
        }
    }

    /** Line of sight result. */
    public record LOS(boolean blocked, boolean woods, boolean partial) {
        int code() {
            return (blocked ? 4 : 0) + (woods ? 2 : 0) + (partial ? 1 : 0);
        }

        static LOS of(int code) {
            return new LOS((code & 4) != 0, (code & 2) != 0, (code & 1) != 0);
        }
    }

    private LOS losOne(int a, int b, int[] line, int height) {
        int aTop = floor(a) + height;
        int tFloor = floor(b);
        int tTop = tFloor + height;
        if ((water[a] >= 2) || (water[b] >= 2)) {
            return new LOS(true, false, false);
        }
        int nn = line.length - 1;
        int woodsCount = 0;
        boolean woodsMod = woods[b] > 0;
        boolean partial = water[b] == 1;
        if (nn <= 1) {
            return new LOS(false, woodsMod, partial);
        }
        for (int k = 1; k < nn; k++) {
            int i = line[k];
            if (i < 0) {
                continue;
            }
            boolean nextA = k == 1;
            boolean nextT = k == nn - 1;
            int solid = top(i);
            if (intervenes(solid, aTop, tTop, nextA, nextT)) {
                return new LOS(true, false, false);
            }
            if ((woods[i] != 0) && intervenes(elev[i] + 2, aTop, tTop, nextA, nextT)) {
                woodsCount++;
                woodsMod = true;
                if (woodsCount >= WOODS_BLOCK_AT) {
                    return new LOS(true, false, false);
                }
            }
            if (nextT && (solid >= tFloor + 1) && (aTop <= tTop)) {
                partial = true;
            }
        }
        return new LOS(false, woodsMod, partial);
    }

    /** Why the line of sight from a to b is blocked, in plain words (for the window; "" if it isn't). */
    public String blockReason(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        java.util.List<String> reasons = new java.util.ArrayList<>();
        for (double side : new double[] { 1.0, -1.0 }) {
            int[] line = line(lo, hi, side);
            if (a != lo) {
                reverse(line);
            }
            String why = blockReasonOne(a, b, line, 2);
            if (!why.isEmpty() && !reasons.contains(why)) {
                reasons.add(why);
            }
        }
        return String.join("; or ", reasons);
    }

    private String blockReasonOne(int a, int b, int[] line, int height) {
        if ((water[a] >= 2) || (water[b] >= 2)) {
            return ASMessages.getString("ASBoard.los.deepWater");
        }
        int aTop = floor(a) + height;
        int tTop = floor(b) + height;
        int nn = line.length - 1;
        java.util.List<String> woodsHexes = new java.util.ArrayList<>();
        for (int k = 1; k < nn; k++) {
            int i = line[k];
            if (i < 0) {
                continue;
            }
            boolean nextA = k == 1;
            boolean nextT = k == nn - 1;
            if (intervenes(top(i), aTop, tTop, nextA, nextT)) {
                return ASMessages.getString((building[i] > 0) ? "ASBoard.los.building" : "ASBoard.los.higherGround",
                      name(i));
            }
            if ((woods[i] != 0) && intervenes(elev[i] + 2, aTop, tTop, nextA, nextT)) {
                woodsHexes.add(name(i));
                if (woodsHexes.size() >= WOODS_BLOCK_AT) {
                    return ASMessages.getString("ASBoard.los.woods", WOODS_BLOCK_AT, String.join(", ", woodsHexes));
                }
            }
        }
        return "";
    }

    private String name(int i) {
        return String.format("%02d%02d", i % width + 1, i / width + 1);
    }

    private static boolean intervenes(int level, int aTop, int tTop, boolean nextA, boolean nextT) {
        return ((level >= aTop) && (level >= tTop)) || (nextA && (level >= aTop)) || (nextT && (level >= tTop));
    }

    private static int score(LOS r) {
        return (r.blocked ? 100 : 0) + (r.woods ? 1 : 0) + (r.partial ? 1 : 0);
    }

    /** ASCE hex LOS between 'Mechs (2 levels tall). When the line runs along a hexside, the defender picks. */
    public LOS los(int a, int b) {
        int code = losCache[a * n + b];
        if (code >= 0) {
            return LOS.of(code);
        }
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        int[] l1 = line(lo, hi, 1.0);
        int[] l2 = line(lo, hi, -1.0);
        boolean same = java.util.Arrays.equals(l1, l2);
        if (a != lo) {
            reverse(l1);
            reverse(l2);
        }
        LOS r1 = losOne(a, b, l1, 2);
        LOS result = r1;
        if (!same) {
            LOS r2 = losOne(a, b, l2, 2);
            if (score(r2) > score(r1)) {
                result = r2;
            }
        }
        losCache[a * n + b] = (byte) result.code();
        return result;
    }

    /** Hexes of Move to step from a into b (null = not allowed) [p. 34]. */
    public Integer enterCost(int a, int b, ASRules rules) {
        if (!passable(b)) {
            return null;
        }
        int change = Math.abs(floor(b) - floor(a));
        if (change > rules.maxLevelChange) {
            return null;
        }
        int cost = rules.costClear + change * rules.costPerLevel;
        if (rough[b]) {
            cost += rules.costRough;
        }
        if (woods[b] != 0) {
            cost += rules.costWoods;
        }
        if (water[b] == 1) {
            cost += rules.costWater1;
        }
        return cost;
    }

    /** Height of the solid stuff (or woods) in a hex, for jumping over it. */
    public int terrainTop(int i) {
        return Math.max(elev[i] + building[i], (woods[i] != 0) ? elev[i] + 2 : elev[i]);
    }

    public boolean isWoods(int i) {
        return woods[i] != 0;
    }

    /** Water depth in this hex (0 = no water). */
    public int waterDepth(int i) {
        return water[i];
    }
}
