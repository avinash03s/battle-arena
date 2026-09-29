package com.battlearena.service;

import com.battlearena.maps.ArenaMapThree;
import com.battlearena.maps.MapProvider;
import com.battlearena.model.Decoration;
import com.battlearena.model.Room;
import com.battlearena.model.Wall;
import com.battlearena.repository.InMemoryRoomRepository;
import com.battlearena.repository.RoomRepository;
import com.battlearena.service.serviceImp.GameEngineServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameEngineServiceImplTest {

    private RoomRepository roomRepository;
    private GameEngineServiceImpl gameEngine;

    @BeforeEach
    void setUp() {
        roomRepository = new InMemoryRoomRepository();
        gameEngine = new GameEngineServiceImpl(roomRepository);
    }

    @Test
    void shouldCreateRoomWhenNoAvailableRoomExists() {
        Room room = gameEngine.findOrCreateRoom(4);

        assertNotNull(room);
        assertNotNull(room.getId());
        assertTrue(room.getId().startsWith("room-"));
        assertEquals(4, room.getMaxPlayers());
    }

    @Test
    void shouldReturnExistingAvailableRoom() {
        MapProvider mapProvider =
                new ArenaMapThree(3000, 2000, 40);

        Room existingRoom =
                roomRepository.createRoom(mapProvider, 4);

        Room result =
                gameEngine.findOrCreateRoom(4);

        assertSame(existingRoom, result);
    }

    @Test
    void shouldReturnWallsForRoom() {
        Room room = gameEngine.findOrCreateRoom(4);

        List<Wall> walls =
                gameEngine.getWallsForRoom(room);

        assertNotNull(walls);
        assertFalse(walls.isEmpty());
    }

    @Test
    void shouldReturnDecorationsForRoom() {
        Room room = gameEngine.findOrCreateRoom(4);

        List<Decoration> decorations =
                gameEngine.getDecorationsForRoom(room);

        assertNotNull(decorations);
    }

    @Test
    void shouldUseDefaultMapWhenRoomHasNoStoredWalls() {
        Room room = new Room("test-room");

        List<Wall> walls =
                gameEngine.getWallsForRoom(room);

        assertNotNull(walls);
        assertFalse(walls.isEmpty());
    }

    @Test
    void shouldUseDefaultMapWhenRoomHasNoStoredDecorations() {
        Room room = new Room("test-room");

        List<Decoration> decorations =
                gameEngine.getDecorationsForRoom(room);

        assertNotNull(decorations);
    }

    @Test
    void shouldUseCorrectMapDimensions() {
        assertEquals(3000, GameEngineServiceImpl.MAP_WIDTH);
        assertEquals(2000, GameEngineServiceImpl.MAP_HEIGHT);
    }
}