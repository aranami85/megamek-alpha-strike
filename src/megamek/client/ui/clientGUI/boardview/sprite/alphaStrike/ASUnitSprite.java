/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.client.ui.clientGUI.boardview.sprite.alphaStrike;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.sprite.Sprite;
import megamek.client.ui.util.UIUtil;
import megamek.common.Player;
import megamek.common.alphaStrike.play.ASUnit;
import megamek.common.board.Coords;

/**
 * An Alpha Strike unit on the map: MegaMek's picture of the unit in its owner's camo (a plain counter if there is no
 * picture), turned to face the hex edge it faces (an arrow for plain counters), a name label with armor (white) and structure (orange) pips, heat, and a
 * to-hit tag when it can be attacked. Selected = yellow hex outline, looked-at = cyan, chosen target = red. Units that
 * have already acted this phase are dimmed. Units destroyed this turn get the red X at once (they leave the map in the
 * End Phase). A "ghost" shows where a unit is planning to move.
 */
public class ASUnitSprite extends Sprite {

    private static final Polygon HEX = new Polygon(new int[] { 21, 62, 83, 62, 21, 0 }, new int[] { 0, 0, 35, 71, 71, 35 },
          6);

    private final ASUnit unit;
    private final Player owner;
    private final Coords where;
    private final int facing;
    private final boolean ghost;
    private boolean selected;
    private boolean viewed;
    private boolean done;
    private String targetText;          // e.g. "8+" when this unit can be attacked by the selected unit
    private boolean isTarget;           // the attack target currently chosen

    public ASUnitSprite(BoardView boardView, ASUnit unit, Player owner, Coords where, int facing, boolean ghost) {
        super(boardView);
        this.unit = unit;
        this.owner = owner;
        this.where = where;
        this.facing = facing;
        this.ghost = ghost;
        getBounds();
    }

    public ASUnit getUnit() {
        return unit;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    /** Highlighted because it was clicked in the unit list (not necessarily yours). */
    public void setViewed(boolean viewed) {
        this.viewed = viewed;
    }

    /** Already moved (Movement Phase) or attacked (Combat Phase): drawn a bit darker. */
    public void setDone(boolean done) {
        this.done = done;
    }

    public void setTargetInfo(String text, boolean chosen) {
        targetText = text;
        isTarget = chosen;
    }

    @Override
    public Rectangle getBounds() {
        bounds = new Rectangle(0, 0, bv.getHexSize().width, bv.getHexSize().height);
        Point p = bv.getHexLocation(where);
        bounds.setLocation(p.x, p.y);
        return bounds;
    }

    @Override
    public void prepare() {
        getBounds();
        GraphicsConfiguration config = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
              .getDefaultConfiguration();
        image = config.createCompatibleImage(bounds.width, bounds.height, Transparency.TRANSLUCENT);
        Graphics2D g = (Graphics2D) image.getGraphics();
        UIUtil.setHighQualityRendering(g);
        g.scale(bv.getScale(), bv.getScale());
        if (ghost) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        }
        boolean wreck = unit.destroyed || unit.doomed;   // doomed = destroyed this turn, removed at the end
        Color base = (owner != null) ? owner.getColour().getColour() : Color.GRAY;

        // hex outline for selection / viewing / target
        if (selected || viewed || isTarget) {
            g.setStroke(new BasicStroke(3.5f));
            g.setColor(isTarget ? Color.RED : (selected ? Color.YELLOW : Color.CYAN));
            g.drawPolygon(HEX);
        }

        BufferedImage picture = ASUnitImages.imageFor(unit, owner);
        if (picture != null) {
            // MegaMek's pictures look "up" (north); turn the unit to face the hex edge it faces
            BufferedImage shown = wreck ? darken(picture, 0.35f) : (done && !ghost ? darken(picture, 0.7f) : picture);
            AffineTransform old = g.getTransform();
            g.rotate(Math.toRadians(facing * 60), 42, 36);
            g.drawImage(shown, 0, 0, null);
            g.setTransform(old);
        } else {
            drawCounter(g, base, wreck);
            // facing arrow on the hex edge (only for the plain counter; pictures turn themselves)
            if (!wreck) {
                AffineTransform old = g.getTransform();
                g.translate(42, 36);
                g.rotate(Math.toRadians(facing * 60));
                Polygon arrow = new Polygon(new int[] { -7, 0, 7 }, new int[] { -27, -35, -27 }, 3);
                g.setColor(Color.YELLOW);
                g.fillPolygon(arrow);
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(1f));
                g.drawPolygon(arrow);
                g.setTransform(old);
            }
        }

        // name label with pips (like MegaMek's unit label)
        String name = unit.generalName() == null ? unit.name : unit.generalName();
        g.setFont(new Font("SansSerif", Font.BOLD, 9));
        if (g.getFontMetrics().stringWidth(name) > 66) {
            while (name.length() > 3 && g.getFontMetrics().stringWidth(name + ".") > 66) {
                name = name.substring(0, name.length() - 1);
            }
            name = name + ".";
        }
        int tw = g.getFontMetrics().stringWidth(name);
        int lx = 42 - Math.max(tw, 44) / 2 - 3;
        int lw = Math.max(tw, 44) + 6;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(lx, 52, lw, 19, 5, 5);
        g.setColor(base);
        g.fillRect(lx, 52, 3, 19);
        g.setColor(Color.WHITE);
        g.drawString(name, 42 - tw / 2, 61);
        drawPips(g, unit.armor, unit.fullArmor, 63, Color.WHITE);
        drawPips(g, unit.structure, unit.fullStructure, 67, new Color(255, 150, 40));

        if (unit.heat > 0 && !wreck) {
            g.setColor(Color.RED);
            g.setFont(new Font("SansSerif", Font.BOLD, 10));
            g.drawString("H" + unit.heat, 8, 18);
        }
        if (wreck) {
            g.setStroke(new BasicStroke(3));
            g.setColor(new Color(220, 30, 30));
            g.drawLine(30, 18, 54, 42);
            g.drawLine(54, 18, 30, 42);
        } else if (unit.shutdown) {
            g.setColor(Color.RED);
            g.setFont(new Font("SansSerif", Font.BOLD, 9));
            g.drawString("SHUTDOWN", 17, 48);
        }
        if (targetText != null) {
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            int ttw = g.getFontMetrics().stringWidth(targetText);
            g.setColor(isTarget ? Color.RED : new Color(160, 0, 0, 210));
            g.fillRoundRect(42 - ttw / 2 - 4, 0, ttw + 8, 16, 6, 6);
            g.setColor(Color.WHITE);
            g.drawString(targetText, 42 - ttw / 2, 13);
        }
        g.dispose();
    }

    /** The old counter, for units MegaMek has no picture for. */
    private void drawCounter(Graphics2D g, Color base, boolean wreck) {
        Color c = wreck ? base.darker().darker() : base;
        g.setColor(c);
        g.fillRoundRect(20, 10, 44, 38, 8, 8);
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(Color.BLACK);
        g.drawRoundRect(20, 10, 44, 38, 8, 8);
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.setColor(textColor(c));
        String sz = "SZ" + unit.size;
        g.drawString(sz, 42 - g.getFontMetrics().stringWidth(sz) / 2, 27);
        g.setFont(new Font("SansSerif", Font.PLAIN, 9));
        String sk = unit.skill + "+";
        g.drawString(sk, 42 - g.getFontMetrics().stringWidth(sk) / 2, 40);
        if (done && !ghost && !wreck) {
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRoundRect(20, 10, 44, 38, 8, 8);
        }
    }

    private static BufferedImage darken(BufferedImage src, float factor) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y);
                int a = (p >>> 24) & 0xff;
                int r = (int) (((p >> 16) & 0xff) * factor);
                int gg = (int) (((p >> 8) & 0xff) * factor);
                int b = (int) ((p & 0xff) * factor);
                out.setRGB(x, y, (a << 24) | (r << 16) | (gg << 8) | b);
            }
        }
        return out;
    }

    private static void drawPips(Graphics2D g, int current, int full, int y, Color color) {
        int n = Math.min(full, 16);
        if (n <= 0) {
            return;
        }
        int size = 3;
        int gap = 1;
        int total = n * (size + gap) - gap;
        int x0 = 42 - total / 2;
        for (int i = 0; i < n; i++) {
            boolean filled = i < Math.min(current, n);
            g.setColor(filled ? color : new Color(90, 90, 90));
            g.fillRect(x0 + i * (size + gap), y, size, size);
        }
    }

    private static Color textColor(Color background) {
        double lum = 0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue();
        return lum > 140 ? Color.BLACK : Color.WHITE;
    }

    @Override
    protected int getSpritePriority() {
        return 10;                      // over ECM halos, C3 lines and movement hexes
    }

    @Override
    public boolean isInside(Point point) {
        return false;
    }
}
