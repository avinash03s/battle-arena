package com.battlearena.ws;

import com.battlearena.model.ClientMessage;
import com.battlearena.model.Player;
import com.battlearena.model.PlayerInput;
import com.battlearena.model.Room;
import com.battlearena.service.GameEngine;
import com.battlearena.service.serviceImp.GameEngineServiceImpl;

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
                int roomSize = 4;
                if (msg.getData() != null) {
                    if (msg.getData().has("name")) {
                        name = msg.getData().get("name").asText("Player");
                        if (name.length() > 16) name = name.substring(0, 16);
                        if (name.trim().isEmpty()) name = "Player";
                    }
                    if (msg.getData().has("roomSize")) {
                        roomSize = msg.getData().get("roomSize").asInt(4);
                        if (roomSize != 2 && roomSize != 4 && roomSize != 6) roomSize = 4;
                    }
                }

                Room room = engine.findOrCreateRoom(roomSize);
                Player player = engine.joinRoom(room, session, name);

                Map<String, Object> joined = new HashMap<>();
                joined.put("selfId", player.getId());
                joined.put("roomId", room.getId());

                Map<String, Object> mapInfo = new HashMap<>();
                mapInfo.put("width", GameEngineServiceImpl.MAP_WIDTH);
                mapInfo.put("height", GameEngineServiceImpl.MAP_HEIGHT);
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
            case "leaveMatch": {
                // Player deliberately hit "Leave Match" / "Exit" button in the UI.
                // Distinct from a raw socket disconnect: we still get to run our own
                // cleanup logic here, and the socket itself is closed by the client
                // right after this, which will also trigger afterConnectionClosed —
                // handleLeaveMatch() below is written to be safe if called first.
                Room room = engine.getRoomBySession(session.getId());
                if (room == null) return;
                engine.handleLeaveMatch(room, session.getId());
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