package com.battlearena.maps;

import com.battlearena.model.Decoration;
import com.battlearena.model.Wall;

import java.util.ArrayList;
import java.util.List;

public class ArenaMapOne implements MapProvider {

    private final double mapWidth;
    private final double mapHeight;
    private final double wallThickness;

    public ArenaMapOne(double mapWidth, double mapHeight, double wallThickness) {
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.wallThickness = wallThickness;
    }

    @Override
    public String getName() {
        return "Crate Yard";
    }

    @Override
    public List<Wall> getWalls() {
        List<Wall> list = new ArrayList<Wall>();

        list.add(new Wall(0, 0, mapWidth, wallThickness, "border"));
        list.add(new Wall(0, mapHeight - wallThickness, mapWidth, wallThickness, "border"));
        list.add(new Wall(0, 0, wallThickness, mapHeight, "border"));
        list.add(new Wall(mapWidth - wallThickness, 0, wallThickness, mapHeight, "border"));

        list.add(new Wall(250, 200, 300, 220, "building"));


        list.add(new Wall(1750, 200, 300, 220, "building"));

        list.add(new Wall(1000, 550, 350, 250, "building"));

        list.add(new Wall(700, 350, 500, 50, "crate"));
        list.add(new Wall(1250, 350, 300, 50, "crate"));
        list.add(new Wall(1150, 800, 70, 70, "crate"));
        list.add(new Wall(700, 950, 70, 70, "crate"));

        list.add(new Wall(200, 1250, 320, 240, "building"));

        list.add(new Wall(1850, 1250, 320, 240, "building"));

        list.add(new Wall(1000, 1450, 350, 200, "building"));

        return list;
    }

    @Override
    public List<Decoration> getDecorations() {
        List<Decoration> d = new ArrayList<Decoration>();

        // grass patches scattered around edges (non-blocking)
        d.add(new Decoration(150, 700, "grassPatch"));
        d.add(new Decoration(2100, 700, "grassPatch"));
        d.add(new Decoration(150, 1050, "grassPatch"));
        d.add(new Decoration(2100, 1050, "grassPatch"));
        d.add(new Decoration(1200, 100, "grassPatch"));
        d.add(new Decoration(1200, 1700, "grassPatch"));

        // trees near building corners (visual only, no collision)
        d.add(new Decoration(230, 190, "tree"));
        d.add(new Decoration(580, 190, "tree"));
        d.add(new Decoration(1730, 190, "tree"));
        d.add(new Decoration(2080, 190, "tree"));
        d.add(new Decoration(180, 1240, "tree"));
        d.add(new Decoration(550, 1500, "tree"));
        d.add(new Decoration(1830, 1240, "tree"));
        d.add(new Decoration(2200, 1500, "tree"));

        // bushes along streets
        d.add(new Decoration(650, 500, "bush"));
        d.add(new Decoration(1650, 500, "bush"));
        d.add(new Decoration(650, 1150, "bush"));
        d.add(new Decoration(1650, 1150, "bush"));

        return d;
    }
}