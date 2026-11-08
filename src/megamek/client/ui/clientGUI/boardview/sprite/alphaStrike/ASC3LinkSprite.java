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
package megamek.client.ui.clientGUI.boardview.sprite.alphaStrike;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Transparency;

import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.sprite.Sprite;
import megamek.client.ui.util.UIUtil;
import megamek.common.board.Coords;

/**
 * A C3 link between two units of the same network: a solid line in the network's colour while data flows, a red
 * dashed line when enemy ECM (or a lost master) cuts it. Drawn under the units; clicks go through it.
 */
public class ASC3LinkSprite extends Sprite {

    private static final int PAD = 6;
    private final Coords a;
    private final Coords b;
    private final Color color;
    private final boolean working;

    /** A line between two hexes; working = solid, otherwise red dashed. */
    public ASC3LinkSprite(BoardView boardView, Coords a, Coords b, Color color, boolean working) {
        super(boardView);
        this.a = a;
        this.b = b;
        this.color = color;
        this.working = working;
        getBounds();
    }

    private Point center(Coords c) {
        Point p = bv.getHexLocation(c);
        return new Point(p.x + bv.getHexSize().width / 2, p.y + bv.getHexSize().height / 2);
    }

    @Override
    public Rectangle getBounds() {
        Point pa = center(a);
        Point pb = center(b);
        bounds = new Rectangle(Math.min(pa.x, pb.x) - PAD, Math.min(pa.y, pb.y) - PAD,
              Math.abs(pa.x - pb.x) + 2 * PAD, Math.abs(pa.y - pb.y) + 2 * PAD);
        return bounds;
    }

    @Override
    public void prepare() {
        getBounds();
        image = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration()
              .createCompatibleImage(bounds.width, bounds.height, Transparency.TRANSLUCENT);
        Graphics2D g = (Graphics2D) image.getGraphics();
        UIUtil.setHighQualityRendering(g);
        Point pa = center(a);
        Point pb = center(b);
        int x1 = pa.x - bounds.x;
        int y1 = pa.y - bounds.y;
        int x2 = pb.x - bounds.x;
        int y2 = pb.y - bounds.y;
        g.setColor(new Color(0, 0, 0, 150));                       // dark outline so it shows on any terrain
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x1, y1, x2, y2);
        if (working) {
            g.setColor(color);
            g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        } else {
            g.setColor(new Color(235, 40, 40));
            g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f,
                  new float[] { 7f, 6f }, 0f));
        }
        g.drawLine(x1, y1, x2, y2);
        g.dispose();
    }

    @Override
    protected int getSpritePriority() {
        return -10;
    }

    @Override
    public boolean isInside(Point point) {
        return false;
    }
}
