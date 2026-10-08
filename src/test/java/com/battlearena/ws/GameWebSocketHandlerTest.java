package com.battlearena.ws;

import com.battlearena.model.CharacterDefinition;
import com.battlearena.model.Player;
import com.battlearena.model.PlayerInput;
import com.battlearena.model.PlayerProfile;
import com.battlearena.model.Room;
import com.battlearena.model.Decoration;
import com.battlearena.model.Wall;
import com.battlearena.repository.PlayerProfileRepository;
import com.battlearena.service.CharacterService;
import com.battlearena.service.GameEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameWebSocketHandlerTest {

    private TestGameEngine engine;
    private TestCharacterService characterService;
    private TestPlayerProfileRepository profileRepository;
    private GameWebSocketHandler handler;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        engine = new TestGameEngine();
        characterService = new TestCharacterService();
        profileRepository = new TestPlayerProfileRepository();

        handler = new GameWebSocketHandler(engine, characterService, profileRepository);
        session = createSession("session-1");
    }

    @Test
    void shouldHandleJoinGame() throws Exception {
        String json = """
            {
                "type": "joinGame",
                "data": {
                    "name": "Avinash",
                    "roomSize": 4,
                    "characterType": "bear"
                }
            }
            """;

        handler.handleMessage(session, new TextMessage(json));

        assertEquals(1, engine.joinRoomCalls);
        assertEquals("Avinash", engine.lastPlayerName);

        // Character is set directly on the player by GameWebSocketHandler
        assertEquals("bear", engine.joinedPlayer.getCharacterType());

        // Character is also saved to the player profile
        assertEquals("bear", profileRepository.profile.getSelectedCharacter());
    }

    @Test
    void shouldUseDefaultValuesWhenJoinDataIsMissing() throws Exception {
        String json = """
            {
                "type": "joinGame"
            }
            """;

        handler.handleMessage(session, new TextMessage(json));

        assertEquals(1, engine.joinRoomCalls);
        assertEquals("Player", engine.lastPlayerName);
        assertEquals(4, engine.lastRoomSize);

        // Default character should be penguin
        assertEquals("penguin", engine.joinedPlayer.getCharacterType());
    }

    @Test
    void shouldLimitPlayerNameToSixteenCharacters() throws Exception {
        String json = """
                {
                    "type": "joinGame",
                    "data": {
                        "name": "ABCDEFGHIJKLMNOPQRST",
                        "roomSize": 4,
                        "characterType": "penguin"
                    }
                }
                """;

        handler.handleMessage(session, new TextMessage(json));
        assertEquals("ABCDEFGHIJKLMNOP", engine.lastPlayerName);
    }

    @Test
    void shouldUseDefaultNameWhenNameIsBlank() throws Exception {
        String json = """
                {
                    "type": "joinGame",
                    "data": {
                        "name": "   "
                    }
                }
                """;

        handler.handleMessage(session, new TextMessage(json));
        assertEquals("Player", engine.lastPlayerName);
    }

    @Test
    void shouldUseDefaultRoomSizeForInvalidRoomSize() throws Exception {
        String json = """
                {
                    "type": "joinGame",
                    "data": {
                        "name": "Avinash",
                        "roomSize": 10
                    }
                }
                """;

        handler.handleMessage(session, new TextMessage(json));
        assertEquals(4, engine.lastRoomSize);
    }

    @Test
    void shouldFallbackToPenguinForUnavailableCharacter() throws Exception {
        String json = """
            {
                "type": "joinGame",
                "data": {
                    "name": "Avinash",
                    "roomSize": 4,
                    "characterType": "dragon"
                }
            }
            """;

        handler.handleMessage(session, new TextMessage(json));

        // Unsupported character should fall back to penguin
        assertEquals("penguin", engine.joinedPlayer.getCharacterType());
    }

    @Test
    void shouldHandleInputMessage() throws Exception {
        engine.roomForSession = new Room("room-1");

        String json = """
                {
                    "type": "input",
                    "data": {
                        "up": true,
                        "down": false,
                        "left": false,
                        "right": true,
                        "angle": 1.5
                    }
                }
                """;

        handler.handleMessage(session, new TextMessage(json));

        assertEquals(1, engine.handleInputCalls);
        assertNotNull(engine.lastInput);
        assertTrue(engine.lastInput.isUp());
        assertTrue(engine.lastInput.isRight());
        assertEquals(1.5, engine.lastInput.getAngle());
    }

    @Test
    void shouldHandleShootMessage() throws Exception {
        engine.roomForSession = new Room("room-1");

        String json = """
                {
                    "type": "shoot"
                }
                """;

        handler.handleMessage(session, new TextMessage(json));

        assertEquals(1, engine.handleShootCalls);
    }

    @Test
    void shouldHandleReloadMessage() throws Exception {
        engine.roomForSession = new Room("room-1");

        String json = """
                {
                    "type": "reload"
                }
                """;

        handler.handleMessage(session, new TextMessage(json));
        assertEquals(1, engine.handleReloadCalls);
    }

    @Test
    void shouldHandleLeaveMatchMessage() throws Exception {
        engine.roomForSession = new Room("room-1");

        String json = """
                {
                    "type": "leaveMatch"
                }
                """;

        handler.handleMessage(session, new TextMessage(json));
        assertEquals(1, engine.handleLeaveMatchCalls);
    }

    @Test
    void shouldHandleConnectionClosed() {
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertEquals("session-1", engine.disconnectedSessionId);
    }

    private WebSocketSession createSession(String id) {
        return (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(),
                new Class[]{WebSocketSession.class},
                (proxy, method, args) -> {
                    if ("getId".equals(method.getName())) {
                        return id;
                    }

                    if ("isOpen".equals(method.getName())) {
                        return true;
                    }

                    return defaultValue(method.getReturnType());
                }
        );
    }

    private Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0.0;
        }
        return null;
    }

    static class TestGameEngine implements GameEngine {

        int joinRoomCalls;
        int handleInputCalls;
        int handleShootCalls;
        int handleReloadCalls;
        int handleLeaveMatchCalls;

        String lastPlayerName;
        int lastRoomSize = 4;
        String lastCharacterType;
        String disconnectedSessionId;

        Player joinedPlayer;
        PlayerInput lastInput;

        Room roomForSession = new Room("room-1");

        @Override
        public Room findOrCreateRoom(int requestedSize) {
            lastRoomSize = requestedSize;

            Room room = new Room("room-1");
            room.setMaxPlayers(requestedSize);

            return room;
        }

        @Override
        public List<Wall> getWallsForRoom(Room room) {
            return new ArrayList<>();
        }

        @Override
        public List<Decoration> getDecorationsForRoom(Room room) {
            return new ArrayList<>();
        }

        @Override
        public Player joinRoom(Room room, WebSocketSession session, String name) {
            joinRoomCalls++;
            lastPlayerName = name;
            joinedPlayer = new Player(session.getId(), name,100,100,"#3aa0ff");
            room.getPlayers().put(joinedPlayer.getId(), joinedPlayer);

            return joinedPlayer;
        }

        @Override
        public void handleDisconnect(String sessionId) {
            disconnectedSessionId = sessionId;
        }

        @Override
        public Room getRoomBySession(String sessionId) {
            return roomForSession;
        }

        @Override
        public void handleInput(Room room, String sessionId, PlayerInput input) {
            handleInputCalls++;
            lastInput = input;
        }

        @Override
        public void handleShoot(Room room, String sessionId) {
            handleShootCalls++;
        }

        @Override
        public void handleReload(Room room, String sessionId) {
            handleReloadCalls++;
        }

        @Override
        public void handleLeaveMatch(Room room, String sessionId) {
            handleLeaveMatchCalls++;
        }

        @Override
        public void broadcast(Room room, String type, Object data) {
        }

        @Override
        public void sendTo(WebSocketSession session, String type, Object data) {
        }
    }

    static class TestCharacterService implements CharacterService {

        @Override
        public List<CharacterDefinition> getAllCharacters() {
            return List.of(new CharacterDefinition("penguin","Penguin",true),
                    new CharacterDefinition("bear", "Bear", true));
        }

        @Override
        public boolean isAvailable(String characterId) {
            return "penguin".equals(characterId) || "bear".equals(characterId);
        }
    }

    static class TestPlayerProfileRepository implements PlayerProfileRepository {

        PlayerProfile profile;

        @Override
        public PlayerProfile findOrCreate(String playerId) {
            if (profile == null) {
                profile = new PlayerProfile(playerId);
            }
            return profile;
        }

        @Override
        public PlayerProfile find(String playerId) {
            return profile;
        }
    }
}