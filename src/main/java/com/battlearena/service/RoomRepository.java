package com.battlearena.service;

import com.battlearena.maps.MapProvider;
import com.battlearena.model.Decoration;
import com.battlearena.model.Room;
import com.battlearena.model.Wall;
import java.util.List;

public interface RoomRepository {

    Room findAvailableRoom();

    Room createRoom(MapProvider mapProvider);

    Room getById(String roomId);

    Room getBySessionId(String sessionId);

    void linkSessionToRoom(String sessionId, String roomId);

    void removeSession(String sessionId);

    List<Wall> getWallsForRoom(Room room);

    List<Decoration> getDecorationsForRoom(Room room);

    void removeRoomIfEmpty(Room room);

    List<Room> getAllRooms();   // <-- ye missing ho sakta hai
}