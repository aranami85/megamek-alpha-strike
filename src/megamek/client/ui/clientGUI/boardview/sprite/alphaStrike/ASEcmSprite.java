/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.client.ui.clientGUI.boardview.sprite.alphaStrike;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.util.Set;

import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.sprite.Sprite;
import megamek.client.ui.util.UIUtil;
import megamek.common.board.Coords;

/**
 * An ECM "bubble" on the Alpha Strike map: every hex inside it lightly tinted in the owner's colour, with a stronger
 * line around the outside edge (a halo). Drawn under the units; clicks go through it.
 */
public class ASEcmSprite extends Sprite {

    private static final int[] HX = { 21, 62, 83, 62, 21, 0 };
    private static final int[] HY = { 0, 0, 35, 71, 71, 35 };

    private final Set<Coords> hexes;
    private final Color color;
    private final boolean angel;

    public ASEcmSprite(BoardView boardView, Set<Coords> hexes, Color color, boolean angel) {
        super(boardView);
        this.hexes = hexes;
        this.color = color;
        this.angel = angel;
        getBounds();
    }

    @Override
    public Rectangle getBounds() {
        Rectangle r = null;
        for (Coords c : hexes) {
            Point p = bv.getHexLocation(c);
            Rectangle h = new Rectangle(p.x, p.y, bv.getHexSize().width, bv.getHexSize().height);
            r = (r == null) ? h : r.union(h);
        }
        bounds = (r == null) ? new Rectangle(0, 0, 1, 1) : r;
        return bounds;
    }

    @Override
    public void prepare() {
        getBounds();
        image = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration()
              .createCompatibleImage(bounds.width, bounds.height, Transparency.TRANSLUCENT);
        Graphics2D g = (Graphics2D) image.getGraphics();
        UIUtil.setHighQualityRendering(g);
        Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), 38);
        Color edge = new Color(color.getRed(), color.getGreen(), color.getBlue(), 210);
        float scale = bv.getScale();
        for (Coords c : hexes) {
            Point p = bv.getHexLocation(c);
            int ox = p.x - bounds.x;
            int oy = p.y - bounds.y;
            Polygon hex = new Polygon();
            for (int i = 0; i < 6; i++) {
                hex.addPoint(ox + Math.round(HX[i] * scale), oy + Math.round(HY[i] * scale));
            }
            g.setColor(fill);
            g.fillPolygon(hex);
            g.setColor(edge);
            g.setStroke(angel ? new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                  new float[] { 8f, 5f }, 0f) : new BasicStroke(2.5f));
            for (int dir = 0; dir < 6; dir++) {      // only the outside edges: the halo's rim
                if (!hexes.contains(c.translated(dir))) {
                    int a = dir;
                    int b = (dir + 1) % 6;
                    g.drawLine(hex.xpoints[a], hex.ypoints[a], hex.xpoints[b], hex.ypoints[b]);
                }
            }
        }
        g.dispose();
    }

    @Override
    protected int getSpritePriority() {
        return -20;
    }

    @Override
    public boolean isInside(Point point) {
        return false;
    }
}
