package com.battlearena.repository;

import com.battlearena.maps.ArenaMapOne;
import com.battlearena.maps.MapProvider;
import com.battlearena.model.Decoration;
import com.battlearena.model.Player;
import com.battlearena.model.Room;
import com.battlearena.model.Wall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRoomRepositoryTest {

    private InMemoryRoomRepository repository;
    private MapProvider mapProvider;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRoomRepository();
        mapProvider = new ArenaMapOne(3000, 2000, 40);
    }

    @Test
    void shouldCreateRoom() {
        Room room = repository.createRoom(mapProvider, 4);

        assertNotNull(room);
        assertNotNull(room.getId());
        assertTrue(room.getId().startsWith("room-"));
        assertEquals(4, room.getMaxPlayers());
    }

    @Test
    void shouldFindRoomById() {
        Room room = repository.createRoom(mapProvider, 4);

        Room found = repository.getById(room.getId());

        assertNotNull(found);
        assertSame(room, found);
    }

    @Test
    void shouldReturnNullForUnknownRoomId() {
        Room room = repository.getById("unknown-room");

        assertNull(room);
    }

    @Test
    void shouldFindAvailableWaitingRoom() {
        Room room = repository.createRoom(mapProvider, 4);

        Room found = repository.findAvailableRoom(4);

        assertNotNull(found);
        assertSame(room, found);
    }

    @Test
    void shouldNotFindRoomWithDifferentSize() {
        repository.createRoom(mapProvider, 4);

        Room found = repository.findAvailableRoom(2);

        assertNull(found);
    }

    @Test
    void shouldLinkSessionToRoom() {
        Room room = repository.createRoom(mapProvider, 4);

        repository.linkSessionToRoom("session-1", room.getId());

        Room found = repository.getBySessionId("session-1");

        assertNotNull(found);
        assertEquals(room.getId(), found.getId());
    }

    @Test
    void shouldRemoveSessionMapping() {
        Room room = repository.createRoom(mapProvider, 4);

        repository.linkSessionToRoom("session-1", room.getId());
        repository.removeSession("session-1");

        assertNull(repository.getBySessionId("session-1"));
    }

    @Test
    void shouldReturnWallsForRoom() {
        Room room = repository.createRoom(mapProvider, 4);

        List<Wall> walls = repository.getWallsForRoom(room);

        assertNotNull(walls);
        assertFalse(walls.isEmpty());
    }

    @Test
    void shouldReturnDecorationsForRoom() {
        Room room = repository.createRoom(mapProvider, 4);

        List<Decoration> decorations = repository.getDecorationsForRoom(room);

        assertNotNull(decorations);
        assertFalse(decorations.isEmpty());
    }

    @Test
    void shouldReturnAllRooms() {
        repository.createRoom(mapProvider, 2);
        repository.createRoom(mapProvider, 4);

        List<Room> rooms = repository.getAllRooms();

        assertEquals(2, rooms.size());
    }

    @Test
    void shouldRemoveEmptyRoom() {
        Room room = repository.createRoom(mapProvider, 4);

        repository.removeRoomIfEmpty(room);

        assertNull(repository.getById(room.getId()));
    }

    @Test
    void shouldNotFindFullRoomAsAvailable() {
        Room room = repository.createRoom(mapProvider, 2);

        Player player1 = new Player("p1", "Player 1", 100, 100, "#3aa0ff");
        Player player2 = new Player("p2", "Player 2", 200, 100, "#ff4d4d");

        room.getPlayers().put(player1.getId(), player1);
        room.getPlayers().put(player2.getId(), player2);

        Room found = repository.findAvailableRoom(2);

        assertNull(found);
    }
}