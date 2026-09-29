package com.battlearena.maps;

import com.battlearena.model.Decoration;
import com.battlearena.model.Wall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArenaMapTwoTest {

    private ArenaMapTwo arenaMap;

    @BeforeEach
    void setUp() {
        arenaMap = new ArenaMapTwo(3000, 2000, 40);
    }

    @Test
    void shouldReturnCorrectMapName() {
        assertEquals("Corner Hideouts", arenaMap.getName());
    }

    @Test
    void shouldReturnWalls() {
        List<Wall> walls = arenaMap.getWalls();

        assertNotNull(walls);
        assertFalse(walls.isEmpty());
    }

    @Test
    void shouldContainFourBorderWalls() {
        List<Wall> walls = arenaMap.getWalls();

        long borderWallCount = walls.stream()
                .filter(wall -> "border".equals(wall.getType()))
                .count();

        assertEquals(4, borderWallCount);
    }

    @Test
    void shouldContainBuildingWalls() {
        List<Wall> walls = arenaMap.getWalls();

        long buildingWallCount = walls.stream()
                .filter(wall -> "building".equals(wall.getType()))
                .count();

        assertTrue(buildingWallCount > 0);
    }

    @Test
    void shouldContainCrateWalls() {
        List<Wall> walls = arenaMap.getWalls();

        long crateWallCount = walls.stream()
                .filter(wall -> "crate".equals(wall.getType()))
                .count();

        assertTrue(crateWallCount > 0);
    }

    @Test
    void shouldReturnEmptyDecorations() {
        List<Decoration> decorations = arenaMap.getDecorations();

        assertNotNull(decorations);
        assertTrue(decorations.isEmpty());
    }

    @Test
    void shouldCreateMapWithExpectedNumberOfWalls() {
        List<Wall> walls = arenaMap.getWalls();

        assertEquals(29, walls.size());
    }
}