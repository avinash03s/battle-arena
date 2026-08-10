package com.battlearena.service.serviceImp;

import com.battlearena.model.Decoration;
import com.battlearena.model.Room;
import com.battlearena.model.Wall;
import com.battlearena.maps.MapProvider;
import com.battlearena.service.RoomRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryRoomRepository implements RoomRepository {

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToRoom = new ConcurrentHashMap<>();
    private final Map<String, List<Wall>> roomWalls = new ConcurrentHashMap<>();
    private final Map<String, List<Decoration>> roomDecorations = new ConcurrentHashMap<>();

    @Override
    public synchronized Room findAvailableRoom(int requestedSize) {
        for (Room r : rooms.values()) {
            if (r.getState() == Room.State.WAITING
                    && r.getMaxPlayers() == requestedSize
                    && r.getPlayers().size() < requestedSize) {
                return r;
            }
        }
        return null;
    }

    @Override
    public synchronized Room createRoom(MapProvider mapProvider, int maxPlayers) {
        String id = "room-" + UUID.randomUUID().toString().substring(0, 6);
        Room room = new Room(id);
        room.setMaxPlayers(maxPlayers);
        rooms.put(id, room);
        roomWalls.put(id, mapProvider.getWalls());
        roomDecorations.put(id, mapProvider.getDecorations());
        return room;
    }

    @Override
    public Room getById(String roomId) {
        return roomId != null ? rooms.get(roomId) : null;
    }

    @Override
    public Room getBySessionId(String sessionId) {
        String roomId = sessionToRoom.get(sessionId);
        return getById(roomId);
    }

    @Override
    public void linkSessionToRoom(String sessionId, String roomId) {
        sessionToRoom.put(sessionId, roomId);
    }

    @Override
    public void removeSession(String sessionId) {
        sessionToRoom.remove(sessionId);
    }

    @Override
    public List<Wall> getWallsForRoom(Room room) {
        return roomWalls.get(room.getId());
    }

    @Override
    public List<Decoration> getDecorationsForRoom(Room room) {
        return roomDecorations.get(room.getId());
    }

    @Override
    public void removeRoomIfEmpty(Room room) {
        if (room.getPlayers().isEmpty()) {
            rooms.remove(room.getId());
            roomWalls.remove(room.getId());
            roomDecorations.remove(room.getId());
        }
    }

    @Override
    public List<Room> getAllRooms() {
        return new ArrayList<>(rooms.values());
    }
}