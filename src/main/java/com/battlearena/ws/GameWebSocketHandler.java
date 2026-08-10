package com.battlearena.ws;

import com.battlearena.model.ClientMessage;
import com.battlearena.model.Player;
import com.battlearena.model.PlayerInput;
import com.battlearena.model.Room;
import com.battlearena.service.GameEngine;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final GameEngine engine;
    private final ObjectMapper mapper = new ObjectMapper();

    public GameWebSocketHandler(GameEngine engine) {
        this.engine = engine;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ClientMessage msg = mapper.readValue(message.getPayload(), ClientMessage.class);
        if (msg.getType() == null) return;

        switch (msg.getType()) {
            case "joinGame": {
                String name = "Player";
                if (msg.getData() != null && msg.getData().has("name")) {
                    name = msg.getData().get("name").asText("Player");
                    if (name.length() > 16) name = name.substring(0, 16);
                    if (name.trim().isEmpty()) name = "Player";
                }

                Room room = engine.findOrCreateRoom();
                Player player = engine.joinRoom(room, session, name);

                Map<String, Object> joined = new HashMap<>();
                joined.put("selfId", player.getId());
                joined.put("roomId", room.getId());

                Map<String, Object> mapInfo = new HashMap<>();
                mapInfo.put("width", 2400);
                mapInfo.put("height", 1800);
                mapInfo.put("walls", engine.getWallsForRoom(room));
                mapInfo.put("decorations", engine.getDecorationsForRoom(room));
                joined.put("map", mapInfo);

                engine.sendTo(session, "joined", joined);
                break;
            }
            case "input": {
                Room room = engine.getRoomBySession(session.getId());
                if (room == null || msg.getData() == null) return;
                PlayerInput input = mapper.treeToValue(msg.getData(), PlayerInput.class);
                engine.handleInput(room, session.getId(), input);
                break;
            }
            case "shoot": {
                Room room = engine.getRoomBySession(session.getId());
                if (room == null) return;
                engine.handleShoot(room, session.getId());
                break;
            }
            case "reload": {
                Room room = engine.getRoomBySession(session.getId());
                if (room == null) return;
                engine.handleReload(room, session.getId());
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        engine.handleDisconnect(session.getId());
    }
}