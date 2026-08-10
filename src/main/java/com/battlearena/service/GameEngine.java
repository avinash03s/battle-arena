package com.battlearena.service;

import com.battlearena.model.Decoration;
import com.battlearena.model.Player;
import com.battlearena.model.PlayerInput;
import com.battlearena.model.Room;
import com.battlearena.model.Wall;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

public interface GameEngine {
    Room findOrCreateRoom(int requestedSize);

    List<Wall> getWallsForRoom(Room room);

    List<Decoration> getDecorationsForRoom(Room room);

    Player joinRoom(Room room, WebSocketSession session, String name);

    void handleDisconnect(String sessionId);

    Room getRoomBySession(String sessionId);

    void handleInput(Room room, String sessionId, PlayerInput input);

    void handleShoot(Room room, String sessionId);

    void handleReload(Room room, String sessionId);

    void broadcast(Room room, String type, Object data);

    void sendTo(WebSocketSession session, String type, Object data);
}