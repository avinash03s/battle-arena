package com.battlearena.service;

import com.battlearena.model.Decoration;
import com.battlearena.model.Room;
import com.battlearena.model.Wall;
import com.battlearena.maps.MapProvider;

import java.util.List;

public interface RoomRepository {
    Room findAvailableRoom(int requestedSize);

    Room createRoom(MapProvider mapProvider, int maxPlayers);

    Room getById(String roomId);

    Room getBySessionId(String sessionId);

    void linkSessionToRoom(String sessionId, String roomId);

    void removeSession(String sessionId);

    List<Wall> getWallsForRoom(Room room);

    List<Decoration> getDecorationsForRoom(Room room);

    void removeRoomIfEmpty(Room room);

    List<Room> getAllRooms();
}