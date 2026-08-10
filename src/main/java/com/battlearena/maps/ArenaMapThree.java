package com.battlearena.maps;

import com.battlearena.model.Decoration;
import com.battlearena.model.Wall;
import java.util.ArrayList;
import java.util.List;

// Map 3: Big open village map -- houses, forests, bunkers, small hiding nooks
public class ArenaMapThree implements MapProvider {

    private final double mapWidth;
    private final double mapHeight;
    private final double wallThickness;

    public ArenaMapThree(double mapWidth, double mapHeight, double wallThickness) {
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.wallThickness = wallThickness;
    }

    @Override
    public String getName() {
        return "Village Outskirts";
    }

    @Override
    public List<Wall> getWalls() {
        List<Wall> list = new ArrayList<Wall>();

        // ===== Border =====
        list.add(new Wall(0, 0, mapWidth, wallThickness, "border"));
        list.add(new Wall(0, mapHeight - wallThickness, mapWidth, wallThickness, "border"));
        list.add(new Wall(0, 0, wallThickness, mapHeight, "border"));
        list.add(new Wall(mapWidth - wallThickness, 0, wallThickness, mapHeight, "border"));

        // ===== Big houses scattered around (top row) =====
        list.add(new Wall(300, 200, 300, 220, "building"));
        list.add(new Wall(1200, 150, 260, 200, "building"));
        list.add(new Wall(2100, 220, 300, 220, "building"));
        list.add(new Wall(3300, 180, 280, 220, "building"));

        // ===== Village cluster (middle) =====
        list.add(new Wall(1600, 900, 220, 180, "building"));
        list.add(new Wall(1900, 950, 180, 150, "building"));
        list.add(new Wall(2200, 850, 200, 200, "building"));

        // ===== Bottom row houses =====
        list.add(new Wall(400, 1600, 280, 220, "building"));
        list.add(new Wall(1300, 1700, 260, 200, "building"));
        list.add(new Wall(2500, 1650, 300, 220, "building"));
        list.add(new Wall(3400, 1550, 260, 220, "building"));

        // ===== Forest clusters (dense small crates = trees, block movement, good cover) =====
        // Forest 1 - top-left
        list.add(new Wall(150, 700, 60, 60, "crate"));
        list.add(new Wall(230, 730, 60, 60, "crate"));
        list.add(new Wall(180, 810, 60, 60, "crate"));
        list.add(new Wall(280, 790, 60, 60, "crate"));

        // Forest 2 - top-right
        list.add(new Wall(3500, 700, 60, 60, "crate"));
        list.add(new Wall(3580, 730, 60, 60, "crate"));
        list.add(new Wall(3530, 810, 60, 60, "crate"));

        // Forest 3 - bottom-left
        list.add(new Wall(700, 2200, 60, 60, "crate"));
        list.add(new Wall(780, 2230, 60, 60, "crate"));
        list.add(new Wall(730, 2310, 60, 60, "crate"));

        // Forest 4 - bottom-right (hiding zone for kids/weaker players)
        list.add(new Wall(3100, 2200, 60, 60, "crate"));
        list.add(new Wall(3180, 2230, 60, 60, "crate"));
        list.add(new Wall(3130, 2310, 60, 60, "crate"));
        list.add(new Wall(3230, 2280, 60, 60, "crate"));

        // ===== Scattered single trees (small obstacles, easy to hide behind) =====
        list.add(new Wall(900, 500, 50, 50, "crate"));
        list.add(new Wall(1050, 600, 50, 50, "crate"));
        list.add(new Wall(2700, 500, 50, 50, "crate"));
        list.add(new Wall(2850, 600, 50, 50, "crate"));
        list.add(new Wall(600, 1200, 50, 50, "crate"));
        list.add(new Wall(2000, 1400, 50, 50, "crate"));
        list.add(new Wall(2900, 1200, 50, 50, "crate"));

        // ===== Rocks / boulders (medium cover) =====
        list.add(new Wall(500, 1000, 90, 70, "crate"));
        list.add(new Wall(2400, 500, 90, 70, "crate"));
        list.add(new Wall(3200, 1000, 90, 70, "crate"));
        list.add(new Wall(1000, 2000, 90, 70, "crate"));

        // ===== Hidden bunker spots (L-shape corners -- great hiding spots, incl. for kids) =====
        list.add(new Wall(1700, 1400, 160, 40, "building"));
        list.add(new Wall(1700, 1400, 40, 160, "building"));

        list.add(new Wall(2600, 300, 160, 40, "building"));
        list.add(new Wall(2720, 300, 40, 160, "building"));

        list.add(new Wall(200, 1900, 160, 40, "building"));
        list.add(new Wall(200, 1900, 40, 160, "building"));

        list.add(new Wall(3300, 2000, 160, 40, "building"));
        list.add(new Wall(3300, 2000, 40, 160, "building"));

        // ===== Extra small safe nook near center (low-risk hiding spot) =====
        list.add(new Wall(1850, 1150, 120, 40, "crate"));
        list.add(new Wall(1850, 1150, 40, 120, "crate"));

        return list;
    }

    @Override
    public List<Decoration> getDecorations() {
        List<Decoration> d = new ArrayList<Decoration>();

        int seed = 0;
        for (double gx = 250; gx < mapWidth - 250; gx += 350) {
            for (double gy = 250; gy < mapHeight - 250; gy += 350) {
                seed++;
                int mod = seed % 5;
                if (mod == 0) {
                    d.add(new Decoration(gx, gy, "tree"));
                } else if (mod == 1 || mod == 2) {
                    d.add(new Decoration(gx, gy, "grassPatch"));
                } else if (mod == 3) {
                    d.add(new Decoration(gx, gy, "bush"));
                }
            }
        }

        return d;
    }
}