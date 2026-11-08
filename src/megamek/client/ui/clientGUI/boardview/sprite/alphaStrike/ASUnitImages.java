/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.client.ui.clientGUI.boardview.sprite.alphaStrike;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.ImageIcon;

import megamek.client.ui.tileset.MMStaticDirectoryManager;
import megamek.client.ui.tileset.MekTileset;
import megamek.common.Player;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.play.ASUnit;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.ConvInfantry;
import megamek.common.units.ProtoMek;
import megamek.common.units.Tank;
import megamek.logging.MMLogger;

/**
 * MegaMek's own unit pictures (data/images/units, mekset.txt) for Alpha Strike units, painted with the owner's
 * camo like on a normal MegaMek map. An AS unit is only a card, so a light stand-in unit with the same name, weight
 * class and type is used to look its picture up.
 */
public final class ASUnitImages {

    private static final MMLogger logger = MMLogger.create(ASUnitImages.class);
    private static final Map<String, BufferedImage> cache = new ConcurrentHashMap<>();
    private static final Image NONE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    private static final Map<String, Image> bases = new ConcurrentHashMap<>();

    private ASUnitImages() {
    }

    /** The unit's picture in its owner's camo (84 x 72), or null if MegaMek has none. */
    public static BufferedImage imageFor(ASUnit unit, Player owner) {
        String camoKey = (owner == null) ? "-" : String.valueOf(owner.getCamouflage()) + owner.getColour();
        String key = unit.name + "|" + camoKey;
        BufferedImage cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Image base = baseImage(unit.getElement());
        if (base == null) {
            return null;
        }
        Image camo = null;
        Color colour = Color.GRAY;
        if (owner != null) {
            colour = owner.getColour().getColour();
            try {
                camo = owner.getCamouflage().getBaseImage();
            } catch (Exception ignored) {
            }
        }
        BufferedImage painted = applyCamo(base, camo, colour);
        cache.put(key, painted);
        return painted;
    }

    private static Image baseImage(AlphaStrikeElement element) {
        Image cached = bases.get(element.getName());
        if (cached != null) {
            return cached == NONE ? null : cached;
        }
        Image img = null;
        try {
            MekTileset tileset = MMStaticDirectoryManager.getMekTileset();
            Entity standIn = standIn(element);
            if (tileset != null && standIn != null) {
                img = tileset.imageFor(standIn);
                if (img != null) {
                    img = new ImageIcon(img).getImage();             // make sure it's fully loaded
                    if (img.getWidth(null) <= 0) {
                        img = null;
                    }
                }
            }
        } catch (Exception ex) {
            logger.warn("No unit picture for {}: {}", element.getName(), ex.getMessage());
        }
        bases.put(element.getName(), img == null ? NONE : img);
        return img;
    }

    /** A bare unit with the card's name, type and a weight from its size, just for the picture lookup. */
    private static Entity standIn(AlphaStrikeElement e) {
        Entity entity;
        double tons = switch (e.getSize()) {
            case 1 -> 30;
            case 2 -> 50;
            case 3 -> 70;
            default -> 90;
        };
        if (e.isBattleArmor()) {
            entity = new BattleArmor();
        } else if (e.isConventionalInfantry()) {
            entity = new ConvInfantry();
        } else if (e.isProtoMek()) {
            entity = new ProtoMek();
        } else if (e.isGround() && !e.isMek()) {
            Tank tank = new Tank();
            Set<String> modes = e.getMovementModes();
            EntityMovementMode mode = EntityMovementMode.TRACKED;
            if (modes.contains("w")) {
                mode = EntityMovementMode.WHEELED;
            } else if (modes.contains("h")) {
                mode = EntityMovementMode.HOVER;
            } else if (modes.contains("v")) {
                mode = EntityMovementMode.VTOL;
            } else if (modes.contains("g")) {
                mode = EntityMovementMode.WIGE;
            } else if (modes.contains("n")) {
                mode = EntityMovementMode.NAVAL;
            }
            tank.setMovementMode(mode);
            entity = tank;
        } else {
            entity = new BipedMek();
        }
        entity.setChassis(e.getChassis());
        entity.setModel(e.getModel());
        entity.setWeight(tons);
        return entity;
    }

    /** Paints the grey parts of a MegaMek unit picture with the camo (or colour), like MegaMek's own icons. */
    private static BufferedImage applyCamo(Image base, Image camo, Color colour) {
        int w = 84;
        int h = 72;
        BufferedImage src = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = src.createGraphics();
        int bw = base.getWidth(null);
        int bh = base.getHeight(null);
        g.drawImage(base, (w - bw) / 2, (h - bh) / 2, null);
        g.dispose();
        BufferedImage camoImg = null;
        if (camo != null && camo.getWidth(null) > 0) {
            camoImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D cg = camoImg.createGraphics();
            cg.drawImage(new ImageIcon(camo).getImage(), 0, 0, w, h, null);
            cg.dispose();
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y);
                int a = (p >>> 24) & 0xff;
                int r = (p >> 16) & 0xff;
                int gg = (p >> 8) & 0xff;
                int b = p & 0xff;
                if (a > 0 && r == gg && gg == b) {           // grey = paintable
                    int c = (camoImg != null) ? camoImg.getRGB(x, y) : colour.getRGB();
                    int cr = (c >> 16) & 0xff;
                    int cgc = (c >> 8) & 0xff;
                    int cb = c & 0xff;
                    r = r * cr / 255;
                    gg = gg * cgc / 255;
                    b = b * cb / 255;
                }
                out.setRGB(x, y, (a << 24) | (r << 16) | (gg << 8) | b);
            }
        }
        return out;
    }
}
