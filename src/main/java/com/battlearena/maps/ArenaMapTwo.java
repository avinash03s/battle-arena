package com.battlearena.maps;

import com.battlearena.model.Decoration;
import com.battlearena.model.Wall;

import java.util.ArrayList;
import java.util.List;

public class ArenaMapTwo implements MapProvider {

    private final double mapWidth;
    private final double mapHeight;
    private final double wallThickness;

    public ArenaMapTwo(double mapWidth, double mapHeight, double wallThickness) {
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.wallThickness = wallThickness;
    }

    @Override
    public String getName() {
        return "Corner Hideouts";
    }

    @Override
    public List<Wall> getWalls() {

        List<Wall> list = new ArrayList<>();

        list.add(new Wall(0, 0, mapWidth, wallThickness, "border"));

        list.add(new Wall(0, mapHeight - wallThickness, mapWidth, wallThickness, "border"));
        list.add(new Wall(0, 0, wallThickness, mapHeight, "border"));

        list.add(new Wall(mapWidth - wallThickness, 0, wallThickness, mapHeight, "border"));

        list.add(new Wall(150, 150, 250, 40, "building"));

        list.add(new Wall(150, 150, 40, 250, "building"));

        list.add(new Wall(150, 400, 250, 40, "building"));


        list.add(new Wall(mapWidth - 400, 150, 250, 40, "crate"));

        list.add(new Wall(mapWidth - 190, 150, 40, 250, "crate"));

        list.add(new Wall(mapWidth - 400, 400, 250, 40, "crate"));

        list.add(new Wall(150, mapHeight - 190, 250, 40, "building"));
        list.add(new Wall(150, mapHeight - 440, 40, 250, "building"));
        list.add(new Wall(150, mapHeight - 440, 250, 40, "building"));

        list.add(new Wall(mapWidth - 400, mapHeight - 190, 250, 40, "crate"));

        list.add(new Wall(mapWidth - 190, mapHeight - 440, 40, 250, "crate"));

        list.add(new Wall(mapWidth - 400, mapHeight - 440, 250, 40, "crate"));

        double cx = mapWidth / 2;
        double cy = mapHeight / 2;

        // Top-left center block
        list.add(new Wall(cx - 220, cy - 220, 80, 80, "building"));

        // Top-right center block
        list.add(new Wall(cx + 140, cy - 220, 80, 80, "building"));

        // Bottom-left center block
        list.add(new Wall(cx - 220, cy + 140, 80, 80, "building"));
        // Bottom-right center block
        list.add(new Wall(cx + 140, cy + 140, 80, 80, "building"));
        // Center block
        list.add(new Wall(cx - 40, cy - 40, 80, 80, "building"));

        list.add(new Wall(cx - 150, 600, 300, 50, "building"));
        list.add(new Wall(cx - 150, mapHeight - 650, 300, 50, "building"));
        list.add(new Wall(600, cy - 150, 50, 300, "building"));
        list.add(new Wall(mapWidth - 650, cy - 150, 50, 300, "building"));

        list.add(new Wall(900, 900, 70, 70, "crate"));
        list.add(new Wall(1450, 900, 70, 70, "crate"));
        list.add(new Wall(1150, 650, 70, 70, "crate"));
        list.add(new Wall(1150, 1150, 70, 70, "crate"));

        return list;
    }

    @Override
    public List<Decoration> getDecorations() {
        return List.of();
    }
}