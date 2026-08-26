package com.battlearena.service.serviceImp;

import com.battlearena.maps.ArenaMapThree;
import com.battlearena.model.*;
import com.battlearena.service.GameEngine;
import com.battlearena.maps.MapProvider;
import com.battlearena.repository.RoomRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class GameEngineServiceImpl implements GameEngine {

    public static final double MAP_WIDTH = 3000;
    public static final double MAP_HEIGHT = 2000;
    private static final double WALL_THICKNESS = 40;

    private static final double PLAYER_RADIUS = 18;
    private static final double PLAYER_SPEED = 4.2;
    private static final double BULLET_SPEED = 12;
    private static final int BULLET_DAMAGE = 12;
    private static final double BULLET_RADIUS = 4;
    private static final long FIRE_COOLDOWN_MS = 220;
    private static final int COUNTDOWN_SECONDS = 3;

    // How long (seconds) a room can sit with only 1 player before a bot fills it
    private static final int SOLO_WAIT_SECONDS = 20;

    private static final String[] CHARACTER_TYPES = {"penguin", "bear"};
    private static final String[] BOT_NAMES = {
            "Rocky", "Blaze", "Shadow", "Nova", "Ghost", "Ranger", "Storm", "Havoc"
    };

    private final List<MapProvider> availableMaps = List.of(new ArenaMapThree(MAP_WIDTH, MAP_HEIGHT, WALL_THICKNESS));

    private final RoomRepository roomRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService countdownExecutor = Executors.newScheduledThreadPool(4);
    private final ExecutorService broadcastExecutor = Executors.newFixedThreadPool(4);
    private final Random random = new Random();

    // Tracks the solo-wait countdown task per room so it can be cancelled if a real player joins
    private final Map<String, ScheduledFuture<?>> soloWaitTasks = new ConcurrentHashMap<>();
    // Tracks the "seconds remaining" value for the solo-wait UI countdown per room
    private final Map<String, Integer> soloWaitRemaining = new ConcurrentHashMap<>();

    public GameEngineServiceImpl(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    /**
     * Finds an available room for the requested player count.
     * If a suitable room does not exist, it selects a map and creates a new room.
     **/
    @Override
    public Room findOrCreateRoom(int requestedSize) {
        Room room = roomRepository.findAvailableRoom(requestedSize);
        if (room != null) {
            return room;
        }
        MapProvider chosenMap = availableMaps.get(random.nextInt(availableMaps.size()));
        return roomRepository.createRoom(chosenMap, requestedSize);
    }

    /**
     * Returns the walls belonging to the given room.
     * If the room has no stored walls, it uses the walls from the default map.
     */
    @Override
    public List<Wall> getWallsForRoom(Room room) {
        List<Wall> walls = roomRepository.getWallsForRoom(room);
        if (walls == null) {
            walls = availableMaps.get(0).getWalls();
        }
        return walls;
    }

    /**
     * Returns the decorations belonging to the given room.
     * If the room has no stored decorations, it uses the decorations from the default map.
     */
    @Override
    public List<Decoration> getDecorationsForRoom(Room room) {
        List<Decoration> decorations = roomRepository.getDecorationsForRoom(room);
        if (decorations == null) {
            decorations = availableMaps.get(0).getDecorations();
        }
        return decorations;
    }

    /**
     * Adds a new player to the room and assigns a spawn position and color.
     * The player is connected to the room through their WebSocket session,
     * then the updated room information and waiting state are broadcast.
     */
    @Override
    public Player joinRoom(Room room, WebSocketSession session, String name) {
        int spawnIndex = room.getPlayers().size();
        double[] spawn = spawnPoint(spawnIndex);
        String color = spawnIndex == 0 ? "#3aa0ff" : "#ff4d4d";

        Player player = new Player(session.getId(), name, spawn[0], spawn[1], color);
        player.setSession(session);
//        player.setCharacterType(CHARACTER_TYPES[random.nextInt(CHARACTER_TYPES.length)]);

        room.getPlayers().put(session.getId(), player);
        roomRepository.linkSessionToRoom(session.getId(), room.getId());

        broadcastRoomUpdate(room);
        maybeStartCountdown(room);
        // Re-evaluate solo-wait bot-fill timer whenever room membership changes
        maybeManageSoloWaitTimer(room);
        return player;
    }

    /**
     * Handles a player disconnecting from the game.
     * It removes the player and session from the room, marks them dead if the match
     * is running, notifies remaining players, and checks whether the match should end.
     */
    @Override
    public void handleDisconnect(String sessionId) {
        Room room = roomRepository.getBySessionId(sessionId);
        roomRepository.removeSession(sessionId);
        if (room == null) return;

        boolean wasPlaying = room.getState() == Room.State.PLAYING;
        Player leavingPlayer = room.getPlayers().get(sessionId);

        room.getPlayers().remove(sessionId);

        if (wasPlaying && leavingPlayer != null) {
            // Mark them as no longer alive so checkWinner treats it correctly
            leavingPlayer.setAlive(false);
            notifyPlayerLeft(room, leavingPlayer);
        }

        roomRepository.removeRoomIfEmpty(room);

        if (!room.getPlayers().isEmpty()) {
            broadcastRoomUpdate(room);
            if (wasPlaying) {
                checkWinner(room);
            }
        } else {
            cancelSoloWaitTimer(room.getId());
        }

        // If we were in WAITING state and someone left, re-check the solo-wait timer
        if (room.getState() == Room.State.WAITING) {
            maybeManageSoloWaitTimer(room);
        }
    }

    /**
     * Handles a player manually leaving the current match.
     * It removes the player from the room, marks them as no longer alive,
     * notifies remaining players, and checks the winner if the match was running.
     */
    @Override
    public void handleLeaveMatch(Room room, String sessionId) {
        if (room == null) return;
        Player leavingPlayer = room.getPlayers().get(sessionId);
        if (leavingPlayer == null) return;

        boolean wasPlaying = room.getState() == Room.State.PLAYING;

        roomRepository.removeSession(sessionId);
        room.getPlayers().remove(sessionId);
        leavingPlayer.setAlive(false);

        if (wasPlaying) {
            notifyPlayerLeft(room, leavingPlayer);
        }

        roomRepository.removeRoomIfEmpty(room);

        if (!room.getPlayers().isEmpty()) {
            broadcastRoomUpdate(room);
            if (wasPlaying) {
                checkWinner(room);
            }
        } else {
            cancelSoloWaitTimer(room.getId());
        }

        if (room.getState() == Room.State.WAITING) {
            maybeManageSoloWaitTimer(room);
        }
    }

    /**
     * Sends a notification to all players in the room when a player leaves.
     * The message contains the leaving player's ID and name.
     */
    private void notifyPlayerLeft(Room room, Player leftPlayer) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", leftPlayer.getId());
        payload.put("name", leftPlayer.getName());
        broadcast(room, "playerLeft", payload);
    }

    /**
     * Finds the room associated with a player's WebSocket session.
     * The session ID is used as the connection between the player and room.
     */
    @Override
    public Room getRoomBySession(String sessionId) {
        return roomRepository.getBySessionId(sessionId);
    }

    /**
     * Returns one of the predefined spawn positions based on the player's index.
     * The index is wrapped so the six available spawn points can be reused.
     */
    private double[] spawnPoint(int index) {
        double[][] spawns = {
                {150, 150},
                {MAP_WIDTH - 150, 150},
                {150, MAP_HEIGHT - 150},
                {MAP_WIDTH - 150, MAP_HEIGHT - 150},
                {MAP_WIDTH / 2, 150},
                {MAP_WIDTH / 2, MAP_HEIGHT - 150}
        };
        return spawns[index % spawns.length];
    }

    /**
     * Stores the player's current movement input and facing angle.
     * The input is only accepted while the match is running and the player is alive;
     * the actual movement is performed later by the game loop.
     */
    @Override
    public void handleInput(Room room, String sessionId, PlayerInput input) {
        if (room.getState() != Room.State.PLAYING) return;
        Player p = room.getPlayers().get(sessionId);
        if (p == null || !p.isAlive()) return;
        p.setInput(input);
        p.setAngle(input.getAngle());
    }

    /**
     * Handles player shooting by checking the game state, player status, reload state,
     * and fire cooldown before creating a bullet using the player's current angle.
     */
    @Override
    public void handleShoot(Room room, String sessionId) {
        if (room.getState() != Room.State.PLAYING) return;
        Player p = room.getPlayers().get(sessionId);
        if (p == null || !p.isAlive() || p.isReloading()) return;

        long now = System.currentTimeMillis();
        if (now - p.getLastShot() < FIRE_COOLDOWN_MS) return;
//        if (p.getAmmo() <= 0) return;

        p.setLastShot(now);
//        p.setAmmo(p.getAmmo() - 1);

        double bx = p.getX() + Math.cos(p.getAngle()) * (PLAYER_RADIUS + 6);
        double by = p.getY() + Math.sin(p.getAngle()) * (PLAYER_RADIUS + 6);
        double dx = Math.cos(p.getAngle()) * BULLET_SPEED;
        double dy = Math.sin(p.getAngle()) * BULLET_SPEED;

        room.getBullets().add(new Bullet(p.getId(), bx, by, dx, dy));
    }

    /**
     * Handles weapon reloading when the player has missing magazine ammo and reserve ammo.
     * It marks the player as reloading, waits 1.2 seconds, then moves the required ammo
     * from reserve ammo into the magazine.
     */
    @Override
    public void handleReload(final Room room, final String sessionId) {
        if (room.getState() != Room.State.PLAYING) return;
        final Player p = room.getPlayers().get(sessionId);
        if (p == null || !p.isAlive() || p.isReloading()) return;
        if (p.getAmmo() == 20 || p.getReserveAmmo() <= 0) return;

        p.setReloading(true);
        countdownExecutor.schedule(() -> {
            if (!room.getPlayers().containsKey(sessionId)) return;
            int needed = 20 - p.getAmmo();
            int take = Math.min(needed, p.getReserveAmmo());
            p.setAmmo(p.getAmmo() + take);
            p.setReserveAmmo(p.getReserveAmmo() - take);
            p.setReloading(false);
        }, 1200, TimeUnit.MILLISECONDS);
    }

    /**
     * Starts the normal 3-second countdown when the room reaches its maximum player count.
     * The countdown runs once per second and starts the match when it reaches zero.
     */
    private void maybeStartCountdown(final Room room) {
        if (room.getState() != Room.State.WAITING) return;
        if (room.getPlayers().size() < room.getMaxPlayers()) return;

        cancelSoloWaitTimer(room.getId());

        room.setState(Room.State.COUNTDOWN);
        room.setCountdownValue(COUNTDOWN_SECONDS);
        broadcast(room, "countdown", room.getCountdownValue());

        final ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];
        futureHolder[0] = countdownExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                room.setCountdownValue(room.getCountdownValue() - 1);
                if (room.getCountdownValue() > 0) {
                    broadcast(room, "countdown", room.getCountdownValue());
                } else {
                    startGame(room);
                    if (futureHolder[0] != null) {
                        futureHolder[0].cancel(false);
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Manages the waiting timer when a room contains only one real player.
     * It starts a 20-second countdown, cancels it when another player joins,
     * and fills the remaining slots with bots when the timer reaches zero.
     */
    private void maybeManageSoloWaitTimer(final Room room) {
        String roomId = room.getId();

        boolean shouldBeRunning = room.getState() == Room.State.WAITING
                && room.getPlayers().size() == 1
                && room.getMaxPlayers() > 1;

        if (!shouldBeRunning) {
            cancelSoloWaitTimer(roomId);
            return;
        }

        // Already running for this room, don't restart it
        if (soloWaitTasks.containsKey(roomId)) {
            return;
        }

        soloWaitRemaining.put(roomId, SOLO_WAIT_SECONDS);
        broadcast(room, "soloWait", buildSoloWaitPayload(SOLO_WAIT_SECONDS));

        ScheduledFuture<?> task = countdownExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                // Room may have been removed or filled in the meantime
                Room current = roomRepository.getById(roomId);
                if (current == null || current.getState() != Room.State.WAITING
                        || current.getPlayers().size() != 1) {
                    cancelSoloWaitTimer(roomId);
                    return;
                }

                int remaining = soloWaitRemaining.getOrDefault(roomId, SOLO_WAIT_SECONDS) - 1;
                soloWaitRemaining.put(roomId, remaining);

                if (remaining > 0) {
                    broadcast(current, "soloWait", buildSoloWaitPayload(remaining));
                } else {
                    cancelSoloWaitTimer(roomId);
                    fillRoomWithBots(current);
                }
            }
        }, 1, 1, TimeUnit.SECONDS);

        soloWaitTasks.put(roomId, task);
    }

    /**
     * Creates the data sent to the client for the solo waiting countdown.
     * The payload contains the number of seconds remaining.
     */
    private Map<String, Object> buildSoloWaitPayload(int secondsRemaining) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("secondsRemaining", secondsRemaining);
        return payload;
    }

    /**
     * Cancels the solo waiting timer for a room and removes its stored countdown state.
     * This is used when another player joins, the room starts, or the room becomes empty.
     */
    private void cancelSoloWaitTimer(String roomId) {
        ScheduledFuture<?> task = soloWaitTasks.remove(roomId);
        if (task != null) {
            task.cancel(false);
        }
        soloWaitRemaining.remove(roomId);
    }


    /**
     * Fills all empty player slots with bot players.
     * After the room is full, it broadcasts the updated room and starts the normal countdown.
     */
    private void fillRoomWithBots(Room room) {
        if (room.getState() != Room.State.WAITING) return;

        int slotsToFill = room.getMaxPlayers() - room.getPlayers().size();
        for (int i = 0; i < slotsToFill; i++) {
            addBotPlayer(room);
        }

        broadcastRoomUpdate(room);
        maybeStartCountdown(room);
    }

    /**
     * Creates one bot player with a unique ID, random bot name, and spawn position.
     * The bot is marked as a bot and added directly to the room without a WebSocket session.
     */
    private void addBotPlayer(Room room) {
        int spawnIndex = room.getPlayers().size();
        double[] spawn = spawnPoint(spawnIndex);
        String color = spawnIndex == 0 ? "#3aa0ff" : "#ff4d4d";

        String botId = "bot-" + UUID.randomUUID().toString().substring(0, 8);
        String botName = BOT_NAMES[random.nextInt(BOT_NAMES.length)] + " (Bot)";

        Player bot = new Player(botId, botName, spawn[0], spawn[1], color);
        bot.setBot(true);
        // No WebSocketSession for bots — broadcast()/sendTo() already null-check sessions

        room.getPlayers().put(botId, bot);
    }

    /**
     * Changes the room state to PLAYING and records the match start time.
     * It then notifies all connected clients that the game has started.
     */
    private void startGame(Room room) {
        room.setState(Room.State.PLAYING);
        room.setStartTime(System.currentTimeMillis());
        Map<String, Object> payload = new HashMap<>();
        payload.put("startTime", room.getStartTime());
        broadcast(room, "gameStart", payload);
    }

    /**
     * Main game loop that runs approximately every 33 milliseconds.
     * Each tick updates bots, players, and bullets, sends the latest game state
     * to clients, and checks whether the match has a winner.
     */
    @Scheduled(fixedRate = 33)
    public void tick() {
        for (Room room : roomRepository.getAllRooms()) {
            if (room.getState() != Room.State.PLAYING) continue;
            tickRoom(room);
        }
    }


    /**
     * Updates one active room for a single game tick.
     * It processes bot movement, player movement, bullet movement, state broadcasting,
     * and winner detection in that order.
     */
    private void tickRoom(Room room) {
        moveBots(room);
        movePlayers(room);
        moveBullets(room);
        broadcastState(room);
        checkWinner(room);
    }

    /**
     * Controls the basic bot AI during each game tick.
     * Each alive bot finds the nearest living player, moves toward the target,
     * aims at the target, and shoots when the target is within 700 pixels.
     */
    private void moveBots(Room room) {
        for (Player bot : room.getPlayers().values()) {
            if (!bot.isBot() || !bot.isAlive()) continue;

            Player target = findNearestAlivePlayer(room, bot);
            PlayerInput input = new PlayerInput();

            if (target != null) {
                double dx = target.getX() - bot.getX();
                double dy = target.getY() - bot.getY();
                double angle = Math.atan2(dy, dx);

                input.setUp(dy < -10);
                input.setDown(dy > 10);
                input.setLeft(dx < -10);
                input.setRight(dx > 10);
                input.setAngle(angle);

                bot.setInput(input);
                bot.setAngle(angle);

                double dist = Math.hypot(dx, dy);
                if (dist < 700) {
                    handleShoot(room, bot.getId());
                }
            } else {
                bot.setInput(input);
            }
        }
    }

    /**
     * Finds the closest living player to the given bot.
     * It checks every alive player, calculates the distance, and keeps the closest one.
     */
    private Player findNearestAlivePlayer(Room room, Player from) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : room.getPlayers().values()) {
            if (p.getId().equals(from.getId()) || !p.isAlive()) continue;
            double dist = Math.hypot(p.getX() - from.getX(), p.getY() - from.getY());
            if (dist < best) {
                best = dist;
                nearest = p;
            }
        }
        return nearest;
    }

    /**
     * Updates the position of every alive player based on their stored input.
     * Movement is normalized to keep diagonal movement from being faster,
     * and separate X/Y collision checks prevent players from passing through walls.
     */
    private void movePlayers(Room room) {
        List<Wall> walls = getWallsForRoom(room);
        for (Player p : room.getPlayers().values()) {
            if (!p.isAlive()) continue;
            PlayerInput in = p.getInput();
            double dx = 0, dy = 0;
            if (in.isUp()) dy -= 1;
            if (in.isDown()) dy += 1;
            if (in.isLeft()) dx -= 1;
            if (in.isRight()) dx += 1;

            if (dx != 0 || dy != 0) {
                double len = Math.sqrt(dx * dx + dy * dy);
                dx = (dx / len) * PLAYER_SPEED;
                dy = (dy / len) * PLAYER_SPEED;

                double newX = p.getX() + dx;
                double newY = p.getY() + dy;

                if (isBlocked(newX, p.getY(), walls)) p.setX(newX);
                if (isBlocked(p.getX(), newY, walls)) p.setY(newY);
            }
        }
    }

    /**
     * Updates every bullet during each game tick.
     * It moves bullets, decreases their lifetime, checks wall and player collisions,
     * applies damage and kills, and removes bullets that hit something or expire.
     */
    private void moveBullets(Room room) {
        List<Wall> walls = getWallsForRoom(room);
        List<Bullet> remaining = new ArrayList<>();

        for (Bullet b : room.getBullets()) {
            double prevX = b.getX();
            double prevY = b.getY();
            b.setX(b.getX() + b.getDx());
            b.setY(b.getY() + b.getDy());
            b.setLife(b.getLife() - 1);

            boolean hit = false;

            if (segmentHitsWall(prevX, prevY, b.getX(), b.getY(), walls)) {
                hit = true;
            }

            if (b.getX() < 0 || b.getX() > MAP_WIDTH || b.getY() < 0 || b.getY() > MAP_HEIGHT) {
                hit = true;
            }

            if (!hit) {
                for (Player p : room.getPlayers().values()) {
                    if (!p.isAlive() || p.getId().equals(b.getOwnerId())) continue;

                    double dist = Math.hypot(p.getX() - b.getX(), p.getY() - b.getY());
                    if (dist <= PLAYER_RADIUS + BULLET_RADIUS) {
                        p.setHealth(p.getHealth() - BULLET_DAMAGE);
                        hit = true;

                        if (p.getHealth() <= 0 && p.isAlive()) {
                            p.setHealth(0);
                            p.setAlive(false);

                            Player shooter = room.getPlayers().get(b.getOwnerId());
                            if (shooter != null) {
                                shooter.setKills(shooter.getKills() + 1);
                            }

                            Map<String, Object> payload = new HashMap<>();
                            payload.put("id", p.getId());
                            payload.put("name", p.getName());
                            payload.put("by", shooter != null ? shooter.getName() : "Unknown");
                            broadcast(room, "playerEliminated", payload);
                        }
                        break;
                    }
                }
            }

            if (!hit && b.getLife() > 0) {
                remaining.add(b);
            }
        }

        room.getBullets().clear();
        room.getBullets().addAll(remaining);
    }

    /**
     * Checks whether a player can move to the given position.
     * It prevents the player from leaving the map and rejects positions that collide with walls.
     */
    private boolean isBlocked(double x, double y, List<Wall> walls) {
        if (x - GameEngineServiceImpl.PLAYER_RADIUS < 0 || x + GameEngineServiceImpl.PLAYER_RADIUS > MAP_WIDTH || y - GameEngineServiceImpl.PLAYER_RADIUS < 0 || y + GameEngineServiceImpl.PLAYER_RADIUS > MAP_HEIGHT) {
            return false;
        }
        for (Wall w : walls) {
            if (rectCircleColliding(x, y, w)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks collision between the player's circular body and a rectangular wall.
     * It calculates the distance from the player center to the wall and compares it
     * with the player's radius to determine whether the two objects overlap.
     */
    private boolean rectCircleColliding(double cx, double cy, Wall rect) {
        double distX = Math.abs(cx - rect.getX() - rect.getW() / 2);
        double distY = Math.abs(cy - rect.getY() - rect.getH() / 2);

        if (distX > rect.getW() / 2 + GameEngineServiceImpl.PLAYER_RADIUS) return false;
        if (distY > rect.getH() / 2 + GameEngineServiceImpl.PLAYER_RADIUS) return false;

        if (distX <= rect.getW() / 2) return true;
        if (distY <= rect.getH() / 2) return true;

        double dx = distX - rect.getW() / 2;
        double dy = distY - rect.getH() / 2;
        return dx * dx + dy * dy <= GameEngineServiceImpl.PLAYER_RADIUS * GameEngineServiceImpl.PLAYER_RADIUS;
    }

    /**
     * Checks whether a bullet's movement path crosses a wall.
     * The path between the bullet's previous and new position is divided into
     * several points, and those points are checked against the room's walls.
     */
    private boolean segmentHitsWall(double x1, double y1, double x2, double y2, List<Wall> walls) {
        int steps = 6;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = x1 + (x2 - x1) * t;
            double py = y1 + (y2 - y1) * t;

            for (Wall w : walls) {
                if (px >= w.getX() && px <= w.getX() + w.getW()
                        && py >= w.getY() && py <= w.getY() + w.getH()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks how many players are still alive after each game tick.
     * When one or zero players remain alive and the room has at least two players,
     * the match ends and the result, winner, kills, and match duration are broadcast.
     */
    private void checkWinner(Room room) {
        if (room.getState() != Room.State.PLAYING) return;

        List<Player> alive = new ArrayList<>();
        for (Player p : room.getPlayers().values()) {
            if (p.isAlive()) {
                alive.add(p);
            }
        }

        if (alive.size() <= 1) {
            room.setState(Room.State.ENDED);

            Player winner = alive.isEmpty() ? null : alive.get(0);
            Map<String, Object> payload = new HashMap<>();

            if (winner != null) {
                Map<String, Object> winnerInfo = new HashMap<>();
                winnerInfo.put("name", winner.getName());
                winnerInfo.put("kills", winner.getKills());
                winnerInfo.put("characterType", winner.getCharacterType());
                payload.put("winner", winnerInfo);
            } else {
                payload.put("winner", null);
            }

            List<Map<String, Object>> playersInfo = new ArrayList<>();
            for (Player p : room.getPlayers().values()) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", p.getName());
                m.put("kills", p.getKills());
                m.put("alive", p.isAlive());
                m.put("characterType", p.getCharacterType());
                playersInfo.add(m);
            }
            payload.put("players", playersInfo);
            payload.put("elapsed", System.currentTimeMillis() - room.getStartTime());

            broadcast(room, "matchResult", payload);
        }
    }

    /**
     * Builds the player list used for the room lobby/update screen.
     * It includes basic information such as player ID, name, color, and room state,
     * then broadcasts that information to all players in the room.
     */
    private void broadcastRoomUpdate(Room room) {
        List<Map<String, Object>> playersInfo = new ArrayList<>();
        for (Player p : room.getPlayers().values()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("color", p.getColor());
            m.put("characterType", p.getCharacterType());
            playersInfo.add(m);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("roomId", room.getId());
        payload.put("players", playersInfo);
        payload.put("state", room.getState().toString().toLowerCase());
        payload.put("minPlayers", room.getMaxPlayers());
        payload.put("maxPlayers", room.getMaxPlayers());

        broadcast(room, "roomUpdate", payload);
    }

    /**
     * Builds the current game state for all players and bullets.
     * The state includes player positions, health, angle, ammo, kills, and bullet positions,
     * then sends the latest snapshot to every connected client.
     */
    private void broadcastState(Room room) {
        List<Map<String, Object>> playersInfo = new ArrayList<>();
        for (Player p : room.getPlayers().values()) {
            playersInfo.add(getStringObjectMap(p));
        }

        List<Map<String, Object>> bulletsInfo = new ArrayList<>();
        for (Bullet b : room.getBullets()) {
            Map<String, Object> m = new HashMap<>();
            m.put("x", b.getX());
            m.put("y", b.getY());
            bulletsInfo.add(m);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("players", playersInfo);
        payload.put("bullets", bulletsInfo);
        payload.put("elapsed", System.currentTimeMillis() - room.getStartTime());

        broadcast(room, "state", payload);
    }

    /**
     * Converts a Player object into a simple key-value map for JSON transmission.
     * It contains the player information required by the frontend to render the game state.
     */
    private static Map<String, Object> getStringObjectMap(Player p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("x", p.getX());
        m.put("y", p.getY());
        m.put("angle", p.getAngle());
        m.put("health", p.getHealth());
        m.put("alive", p.isAlive());
        m.put("kills", p.getKills());
        m.put("ammo", p.getAmmo());
        m.put("reserveAmmo", p.getReserveAmmo());
        m.put("reloading", p.isReloading());
        m.put("color", p.getColor());
        m.put("characterType", p.getCharacterType());
        return m;
    }

    /**
     * Sends a message to every connected player in the room.
     * The message type and data are wrapped into a JSON object and sent through WebSocket.
     */
    @Override
    public void broadcast(Room room, String type, Object data) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", type);
        envelope.put("data", data);
        try {
            String json = mapper.writeValueAsString(envelope);
            TextMessage message = new TextMessage(json);
            for (Player p : room.getPlayers().values()) {
                WebSocketSession session = p.getSession();
                if (session != null && session.isOpen()) {
                    broadcastExecutor.submit(() -> {
                        try {
                            synchronized (session) {
                                session.sendMessage(message);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
//    @Override
//    public void broadcast(Room room, String type, Object data) {
//        Map<String, Object> envelope = new HashMap<>();
//        envelope.put("type", type);
//        envelope.put("data", data);
//        try {
//            String json = mapper.writeValueAsString(envelope);
//            TextMessage message = new TextMessage(json);
//            for (Player p : room.getPlayers().values()) {
//                WebSocketSession session = p.getSession();
//                if (session != null && session.isOpen()) {
//                    synchronized (session) {
//                        session.sendMessage(message);
//                    }
//                }
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }

    /**
     * Sends a message to one specific WebSocket client.
     * The message type and data are converted to JSON and sent through that session.
     */

    @Override
    public void sendTo(WebSocketSession session, String type, Object data) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", type);
        envelope.put("data", data);
        try {
            String json = mapper.writeValueAsString(envelope);
            TextMessage message = new TextMessage(json);
            broadcastExecutor.submit(() -> {
                try {
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
//    @Override
//    public void sendTo(WebSocketSession session, String type, Object data) {
//        Map<String, Object> envelope = new HashMap<>();
//        envelope.put("type", type);
//        envelope.put("data", data);
//        try {
//            String json = mapper.writeValueAsString(envelope);
//            synchronized (session) {
//                session.sendMessage(new TextMessage(json));
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }
}



/*
package com.battlearena.service.serviceImp;

import com.battlearena.maps.ArenaMapThree;
import com.battlearena.model.*;
import com.battlearena.service.GameEngine;
import com.battlearena.maps.MapProvider;
import com.battlearena.repository.RoomRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class GameEngineServiceImpl implements GameEngine {

    public static final double MAP_WIDTH = 3000;
    public static final double MAP_HEIGHT = 2000;
    private static final double WALL_THICKNESS = 40;

    private static final double PLAYER_RADIUS = 18;
    private static final double PLAYER_SPEED = 4.2;
    private static final double BULLET_SPEED = 12;
    private static final int BULLET_DAMAGE = 12;
    private static final double BULLET_RADIUS = 4;
    private static final long FIRE_COOLDOWN_MS = 220;
    private static final int COUNTDOWN_SECONDS = 3;

    // How long (seconds) a room can sit with only 1 player before a bot fills it
    private static final int SOLO_WAIT_SECONDS = 20;

    private static final String[] CHARACTER_TYPES = {"penguin", "bear"};
    private static final String[] BOT_NAMES = {
            "Rocky", "Blaze", "Shadow", "Nova", "Ghost", "Ranger", "Storm", "Havoc"
    };

    private final List<MapProvider> availableMaps = List.of(new ArenaMapThree(MAP_WIDTH, MAP_HEIGHT, WALL_THICKNESS));

    private final RoomRepository roomRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService countdownExecutor = Executors.newScheduledThreadPool(4);
    private final ExecutorService broadcastExecutor = Executors.newFixedThreadPool(4);
    private final Random random = new Random();

    // Tracks the solo-wait countdown task per room so it can be cancelled if a real player joins
    private final Map<String, ScheduledFuture<?>> soloWaitTasks = new ConcurrentHashMap<>();
    // Tracks the "seconds remaining" value for the solo-wait UI countdown per room
    private final Map<String, Integer> soloWaitRemaining = new ConcurrentHashMap<>();

    public GameEngineServiceImpl(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    */
/**
     * Finds an available room for the requested player count.
     * If a suitable room does not exist, it selects a map and creates a new room.
     **//*

    @Override
    public Room findOrCreateRoom(int requestedSize) {
        Room room = roomRepository.findAvailableRoom(requestedSize);
        if (room != null) {
            return room;
        }
        MapProvider chosenMap = availableMaps.get(random.nextInt(availableMaps.size()));
        return roomRepository.createRoom(chosenMap, requestedSize);
    }

    */
/**
     * Returns the walls belonging to the given room.
     * If the room has no stored walls, it uses the walls from the default map.
     *//*

    @Override
    public List<Wall> getWallsForRoom(Room room) {
        List<Wall> walls = roomRepository.getWallsForRoom(room);
        if (walls == null) {
            walls = availableMaps.get(0).getWalls();
        }
        return walls;
    }

    */
/**
     * Returns the decorations belonging to the given room.
     * If the room has no stored decorations, it uses the decorations from the default map.
     *//*

    @Override
    public List<Decoration> getDecorationsForRoom(Room room) {
        List<Decoration> decorations = roomRepository.getDecorationsForRoom(room);
        if (decorations == null) {
            decorations = availableMaps.get(0).getDecorations();
        }
        return decorations;
    }

    */
/**
     * Adds a new player to the room and assigns a spawn position and color.
     * The player is connected to the room through their WebSocket session,
     * then the updated room information and waiting state are broadcast.
     *//*

    @Override
    public Player joinRoom(Room room, WebSocketSession session, String name) {
        int spawnIndex = room.getPlayers().size();
        double[] spawn = spawnPoint(spawnIndex);
        String color = spawnIndex == 0 ? "#3aa0ff" : "#ff4d4d";

        Player player = new Player(session.getId(), name, spawn[0], spawn[1], color);
        player.setSession(session);
//        player.setCharacterType(CHARACTER_TYPES[random.nextInt(CHARACTER_TYPES.length)]);

        room.getPlayers().put(session.getId(), player);
        roomRepository.linkSessionToRoom(session.getId(), room.getId());

        broadcastRoomUpdate(room);
        maybeStartCountdown(room);
        // Re-evaluate solo-wait bot-fill timer whenever room membership changes
        maybeManageSoloWaitTimer(room);
        return player;
    }

    */
/**
     * Handles a player disconnecting from the game.
     * It removes the player and session from the room, marks them dead if the match
     * is running, notifies remaining players, and checks whether the match should end.
     *//*

    @Override
    public void handleDisconnect(String sessionId) {
        Room room = roomRepository.getBySessionId(sessionId);
        roomRepository.removeSession(sessionId);
        if (room == null) return;

        boolean wasPlaying = room.getState() == Room.State.PLAYING;
        Player leavingPlayer = room.getPlayers().get(sessionId);

        room.getPlayers().remove(sessionId);

        if (wasPlaying && leavingPlayer != null) {
            // Mark them as no longer alive so checkWinner treats it correctly
            leavingPlayer.setAlive(false);
            notifyPlayerLeft(room, leavingPlayer);
        }

        roomRepository.removeRoomIfEmpty(room);

        if (!room.getPlayers().isEmpty()) {
            broadcastRoomUpdate(room);
            if (wasPlaying) {
                checkWinner(room);
            }
        } else {
            cancelSoloWaitTimer(room.getId());
        }

        // If we were in WAITING state and someone left, re-check the solo-wait timer
        if (room.getState() == Room.State.WAITING) {
            maybeManageSoloWaitTimer(room);
        }
    }

    */
/**
     * Handles a player manually leaving the current match.
     * It removes the player from the room, marks them as no longer alive,
     * notifies remaining players, and checks the winner if the match was running.
     *//*

    @Override
    public void handleLeaveMatch(Room room, String sessionId) {
        if (room == null) return;
        Player leavingPlayer = room.getPlayers().get(sessionId);
        if (leavingPlayer == null) return;

        boolean wasPlaying = room.getState() == Room.State.PLAYING;

        roomRepository.removeSession(sessionId);
        room.getPlayers().remove(sessionId);
        leavingPlayer.setAlive(false);

        if (wasPlaying) {
            notifyPlayerLeft(room, leavingPlayer);
        }

        roomRepository.removeRoomIfEmpty(room);

        if (!room.getPlayers().isEmpty()) {
            broadcastRoomUpdate(room);
            if (wasPlaying) {
                checkWinner(room);
            }
        } else {
            cancelSoloWaitTimer(room.getId());
        }

        if (room.getState() == Room.State.WAITING) {
            maybeManageSoloWaitTimer(room);
        }
    }

    */
/**
     * Sends a notification to all players in the room when a player leaves.
     * The message contains the leaving player's ID and name.
     *//*

    private void notifyPlayerLeft(Room room, Player leftPlayer) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", leftPlayer.getId());
        payload.put("name", leftPlayer.getName());
        broadcast(room, "playerLeft", payload);
    }

    */
/**
     * Finds the room associated with a player's WebSocket session.
     * The session ID is used as the connection between the player and room.
     *//*

    @Override
    public Room getRoomBySession(String sessionId) {
        return roomRepository.getBySessionId(sessionId);
    }

    */
/**
     * Returns one of the predefined spawn positions based on the player's index.
     * The index is wrapped so the six available spawn points can be reused.
     *//*

    private double[] spawnPoint(int index) {
        double[][] spawns = {
                {150, 150},
                {MAP_WIDTH - 150, 150},
                {150, MAP_HEIGHT - 150},
                {MAP_WIDTH - 150, MAP_HEIGHT - 150},
                {MAP_WIDTH / 2, 150},
                {MAP_WIDTH / 2, MAP_HEIGHT - 150}
        };
        return spawns[index % spawns.length];
    }

    */
/**
     * Stores the player's current movement input and facing angle.
     * The input is only accepted while the match is running and the player is alive;
     * the actual movement is performed later by the game loop.
     *//*

    @Override
    public void handleInput(Room room, String sessionId, PlayerInput input) {
        if (room.getState() != Room.State.PLAYING) return;
        Player p = room.getPlayers().get(sessionId);
        if (p == null || !p.isAlive()) return;
        p.setInput(input);
        p.setAngle(input.getAngle());
    }

    */
/**
     * Handles player shooting by checking the game state, player status, reload state,
     * and fire cooldown before creating a bullet using the player's current angle.
     *//*

    @Override
    public void handleShoot(Room room, String sessionId) {
        if (room.getState() != Room.State.PLAYING) return;
        Player p = room.getPlayers().get(sessionId);
        if (p == null || !p.isAlive() || p.isReloading()) return;

        long now = System.currentTimeMillis();
        if (now - p.getLastShot() < FIRE_COOLDOWN_MS) return;
//        if (p.getAmmo() <= 0) return;

        p.setLastShot(now);
//        p.setAmmo(p.getAmmo() - 1);

        double bx = p.getX() + Math.cos(p.getAngle()) * (PLAYER_RADIUS + 6);
        double by = p.getY() + Math.sin(p.getAngle()) * (PLAYER_RADIUS + 6);
        double dx = Math.cos(p.getAngle()) * BULLET_SPEED;
        double dy = Math.sin(p.getAngle()) * BULLET_SPEED;

        room.getBullets().add(new Bullet(p.getId(), bx, by, dx, dy));
    }

    */
/**
     * Handles weapon reloading when the player has missing magazine ammo and reserve ammo.
     * It marks the player as reloading, waits 1.2 seconds, then moves the required ammo
     * from reserve ammo into the magazine.
     *//*

    @Override
    public void handleReload(final Room room, final String sessionId) {
        if (room.getState() != Room.State.PLAYING) return;
        final Player p = room.getPlayers().get(sessionId);
        if (p == null || !p.isAlive() || p.isReloading()) return;
        if (p.getAmmo() == 20 || p.getReserveAmmo() <= 0) return;

        p.setReloading(true);
        countdownExecutor.schedule(() -> {
            if (!room.getPlayers().containsKey(sessionId)) return;
            int needed = 20 - p.getAmmo();
            int take = Math.min(needed, p.getReserveAmmo());
            p.setAmmo(p.getAmmo() + take);
            p.setReserveAmmo(p.getReserveAmmo() - take);
            p.setReloading(false);
        }, 1200, TimeUnit.MILLISECONDS);
    }

    */
/**
     * Starts the normal 3-second countdown when the room reaches its maximum player count.
     * The countdown runs once per second and starts the match when it reaches zero.
     *//*

    private void maybeStartCountdown(final Room room) {
        if (room.getState() != Room.State.WAITING) return;
        if (room.getPlayers().size() < room.getMaxPlayers()) return;

        cancelSoloWaitTimer(room.getId());

        room.setState(Room.State.COUNTDOWN);
        room.setCountdownValue(COUNTDOWN_SECONDS);
        broadcast(room, "countdown", room.getCountdownValue());

        final ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];
        futureHolder[0] = countdownExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                room.setCountdownValue(room.getCountdownValue() - 1);
                if (room.getCountdownValue() > 0) {
                    broadcast(room, "countdown", room.getCountdownValue());
                } else {
                    startGame(room);
                    if (futureHolder[0] != null) {
                        futureHolder[0].cancel(false);
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    */
/**
     * Manages the waiting timer when a room contains only one real player.
     * It starts a 20-second countdown, cancels it when another player joins,
     * and fills the remaining slots with bots when the timer reaches zero.
     *//*

    private void maybeManageSoloWaitTimer(final Room room) {
        String roomId = room.getId();

        boolean shouldBeRunning = room.getState() == Room.State.WAITING
                && room.getPlayers().size() == 1
                && room.getMaxPlayers() > 1;

        if (!shouldBeRunning) {
            cancelSoloWaitTimer(roomId);
            return;
        }

        // Already running for this room, don't restart it
        if (soloWaitTasks.containsKey(roomId)) {
            return;
        }

        soloWaitRemaining.put(roomId, SOLO_WAIT_SECONDS);
        broadcast(room, "soloWait", buildSoloWaitPayload(SOLO_WAIT_SECONDS));

        ScheduledFuture<?> task = countdownExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                // Room may have been removed or filled in the meantime
                Room current = roomRepository.getById(roomId);
                if (current == null || current.getState() != Room.State.WAITING
                        || current.getPlayers().size() != 1) {
                    cancelSoloWaitTimer(roomId);
                    return;
                }

                int remaining = soloWaitRemaining.getOrDefault(roomId, SOLO_WAIT_SECONDS) - 1;
                soloWaitRemaining.put(roomId, remaining);

                if (remaining > 0) {
                    broadcast(current, "soloWait", buildSoloWaitPayload(remaining));
                } else {
                    cancelSoloWaitTimer(roomId);
                    fillRoomWithBots(current);
                }
            }
        }, 1, 1, TimeUnit.SECONDS);

        soloWaitTasks.put(roomId, task);
    }

    */
/**
     * Creates the data sent to the client for the solo waiting countdown.
     * The payload contains the number of seconds remaining.
     *//*

    private Map<String, Object> buildSoloWaitPayload(int secondsRemaining) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("secondsRemaining", secondsRemaining);
        return payload;
    }

    */
/**
     * Cancels the solo waiting timer for a room and removes its stored countdown state.
     * This is used when another player joins, the room starts, or the room becomes empty.
     *//*

    private void cancelSoloWaitTimer(String roomId) {
        ScheduledFuture<?> task = soloWaitTasks.remove(roomId);
        if (task != null) {
            task.cancel(false);
        }
        soloWaitRemaining.remove(roomId);
    }


    */
/**
     * Fills all empty player slots with bot players.
     * After the room is full, it broadcasts the updated room and starts the normal countdown.
     *//*

    private void fillRoomWithBots(Room room) {
        if (room.getState() != Room.State.WAITING) return;

        int slotsToFill = room.getMaxPlayers() - room.getPlayers().size();
        for (int i = 0; i < slotsToFill; i++) {
            addBotPlayer(room);
        }

        broadcastRoomUpdate(room);
        maybeStartCountdown(room);
    }

    */
/**
     * Creates one bot player with a unique ID, random bot name, and spawn position.
     * The bot is marked as a bot and added directly to the room without a WebSocket session.
     *//*

    private void addBotPlayer(Room room) {
        int spawnIndex = room.getPlayers().size();
        double[] spawn = spawnPoint(spawnIndex);
        String color = spawnIndex == 0 ? "#3aa0ff" : "#ff4d4d";

        String botId = "bot-" + UUID.randomUUID().toString().substring(0, 8);
        String botName = BOT_NAMES[random.nextInt(BOT_NAMES.length)] + " (Bot)";

        Player bot = new Player(botId, botName, spawn[0], spawn[1], color);
        bot.setBot(true);
        // No WebSocketSession for bots — broadcast()/sendTo() already null-check sessions

        room.getPlayers().put(botId, bot);
    }

    */
/**
     * Changes the room state to PLAYING and records the match start time.
     * It then notifies all connected clients that the game has started.
     *//*

    private void startGame(Room room) {
        room.setState(Room.State.PLAYING);
        room.setStartTime(System.currentTimeMillis());
        Map<String, Object> payload = new HashMap<>();
        payload.put("startTime", room.getStartTime());
        broadcast(room, "gameStart", payload);
    }

    */
/**
     * Main game loop that runs approximately every 33 milliseconds.
     * Each tick updates bots, players, and bullets, sends the latest game state
     * to clients, and checks whether the match has a winner.
     *//*

    @Scheduled(fixedRate = 33)
    public void tick() {
        for (Room room : roomRepository.getAllRooms()) {
            if (room.getState() != Room.State.PLAYING) continue;
            tickRoom(room);
        }
    }


    */
/**
     * Updates one active room for a single game tick.
     * It processes bot movement, player movement, bullet movement, state broadcasting,
     * and winner detection in that order.
     *//*

    private void tickRoom(Room room) {
        moveBots(room);
        movePlayers(room);
        moveBullets(room);
        broadcastState(room);
        checkWinner(room);
    }

    */
/**
     * Controls the basic bot AI during each game tick.
     * Each alive bot finds the nearest living player, moves toward the target,
     * aims at the target, and shoots when the target is within 700 pixels.
     *//*

    private void moveBots(Room room) {
        for (Player bot : room.getPlayers().values()) {
            if (!bot.isBot() || !bot.isAlive()) continue;

            Player target = findNearestAlivePlayer(room, bot);
            PlayerInput input = new PlayerInput();

            if (target != null) {
                double dx = target.getX() - bot.getX();
                double dy = target.getY() - bot.getY();
                double angle = Math.atan2(dy, dx);

                input.setUp(dy < -10);
                input.setDown(dy > 10);
                input.setLeft(dx < -10);
                input.setRight(dx > 10);
                input.setAngle(angle);

                bot.setInput(input);
                bot.setAngle(angle);

                double dist = Math.hypot(dx, dy);
                if (dist < 700) {
                    handleShoot(room, bot.getId());
                }
            } else {
                bot.setInput(input);
            }
        }
    }

    */
/**
     * Finds the closest living player to the given bot.
     * It checks every alive player, calculates the distance, and keeps the closest one.
     *//*

    private Player findNearestAlivePlayer(Room room, Player from) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : room.getPlayers().values()) {
            if (p.getId().equals(from.getId()) || !p.isAlive()) continue;
            double dist = Math.hypot(p.getX() - from.getX(), p.getY() - from.getY());
            if (dist < best) {
                best = dist;
                nearest = p;
            }
        }
        return nearest;
    }

    */
/**
     * Updates the position of every alive player based on their stored input.
     * Movement is normalized to keep diagonal movement from being faster,
     * and separate X/Y collision checks prevent players from passing through walls.
     *//*

    private void movePlayers(Room room) {
        List<Wall> walls = getWallsForRoom(room);
        for (Player p : room.getPlayers().values()) {
            if (!p.isAlive()) continue;
            PlayerInput in = p.getInput();
            double dx = 0, dy = 0;
            if (in.isUp()) dy -= 1;
            if (in.isDown()) dy += 1;
            if (in.isLeft()) dx -= 1;
            if (in.isRight()) dx += 1;

            if (dx != 0 || dy != 0) {
                double len = Math.sqrt(dx * dx + dy * dy);
                dx = (dx / len) * PLAYER_SPEED;
                dy = (dy / len) * PLAYER_SPEED;

                double newX = p.getX() + dx;
                double newY = p.getY() + dy;

                if (isBlocked(newX, p.getY(), walls)) p.setX(newX);
                if (isBlocked(p.getX(), newY, walls)) p.setY(newY);
            }
        }
    }

    */
/**
     * Updates every bullet during each game tick.
     * It moves bullets, decreases their lifetime, checks wall and player collisions,
     * applies damage and kills, and removes bullets that hit something or expire.
     *//*

    private void moveBullets(Room room) {
        List<Wall> walls = getWallsForRoom(room);
        List<Bullet> remaining = new ArrayList<>();

        for (Bullet b : room.getBullets()) {
            double prevX = b.getX();
            double prevY = b.getY();
            b.setX(b.getX() + b.getDx());
            b.setY(b.getY() + b.getDy());
            b.setLife(b.getLife() - 1);

            boolean hit = false;

            if (segmentHitsWall(prevX, prevY, b.getX(), b.getY(), walls)) {
                hit = true;
            }

            if (b.getX() < 0 || b.getX() > MAP_WIDTH || b.getY() < 0 || b.getY() > MAP_HEIGHT) {
                hit = true;
            }

            if (!hit) {
                for (Player p : room.getPlayers().values()) {
                    if (!p.isAlive() || p.getId().equals(b.getOwnerId())) continue;

                    double dist = Math.hypot(p.getX() - b.getX(), p.getY() - b.getY());
                    if (dist <= PLAYER_RADIUS + BULLET_RADIUS) {
                        p.setHealth(p.getHealth() - BULLET_DAMAGE);
                        hit = true;

                        if (p.getHealth() <= 0 && p.isAlive()) {
                            p.setHealth(0);
                            p.setAlive(false);

                            Player shooter = room.getPlayers().get(b.getOwnerId());
                            if (shooter != null) {
                                shooter.setKills(shooter.getKills() + 1);
                            }

                            Map<String, Object> payload = new HashMap<>();
                            payload.put("id", p.getId());
                            payload.put("name", p.getName());
                            payload.put("by", shooter != null ? shooter.getName() : "Unknown");
                            broadcast(room, "playerEliminated", payload);
                        }
                        break;
                    }
                }
            }

            if (!hit && b.getLife() > 0) {
                remaining.add(b);
            }
        }

        room.getBullets().clear();
        room.getBullets().addAll(remaining);
    }

    */
/**
     * Checks whether a player can move to the given position.
     * It prevents the player from leaving the map and rejects positions that collide with walls.
     *//*

    private boolean isBlocked(double x, double y, List<Wall> walls) {
        if (x - GameEngineServiceImpl.PLAYER_RADIUS < 0 || x + GameEngineServiceImpl.PLAYER_RADIUS > MAP_WIDTH || y - GameEngineServiceImpl.PLAYER_RADIUS < 0 || y + GameEngineServiceImpl.PLAYER_RADIUS > MAP_HEIGHT) {
            return false;
        }
        for (Wall w : walls) {
            if (rectCircleColliding(x, y, w)) {
                return false;
            }
        }
        return true;
    }

    */
/**
     * Checks collision between the player's circular body and a rectangular wall.
     * It calculates the distance from the player center to the wall and compares it
     * with the player's radius to determine whether the two objects overlap.
     *//*

    private boolean rectCircleColliding(double cx, double cy, Wall rect) {
        double distX = Math.abs(cx - rect.getX() - rect.getW() / 2);
        double distY = Math.abs(cy - rect.getY() - rect.getH() / 2);

        if (distX > rect.getW() / 2 + GameEngineServiceImpl.PLAYER_RADIUS) return false;
        if (distY > rect.getH() / 2 + GameEngineServiceImpl.PLAYER_RADIUS) return false;

        if (distX <= rect.getW() / 2) return true;
        if (distY <= rect.getH() / 2) return true;

        double dx = distX - rect.getW() / 2;
        double dy = distY - rect.getH() / 2;
        return dx * dx + dy * dy <= GameEngineServiceImpl.PLAYER_RADIUS * GameEngineServiceImpl.PLAYER_RADIUS;
    }

    */
/**
     * Checks whether a bullet's movement path crosses a wall.
     * The path between the bullet's previous and new position is divided into
     * several points, and those points are checked against the room's walls.
     *//*

    private boolean segmentHitsWall(double x1, double y1, double x2, double y2, List<Wall> walls) {
        int steps = 6;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = x1 + (x2 - x1) * t;
            double py = y1 + (y2 - y1) * t;

            for (Wall w : walls) {
                if (px >= w.getX() && px <= w.getX() + w.getW()
                        && py >= w.getY() && py <= w.getY() + w.getH()) {
                    return true;
                }
            }
        }
        return false;
    }

    */
/**
     * Checks how many players are still alive after each game tick.
     * When one or zero players remain alive and the room has at least two players,
     * the match ends and the result, winner, kills, and match duration are broadcast.
     *//*

    private void checkWinner(Room room) {
        if (room.getState() != Room.State.PLAYING) return;

        List<Player> alive = new ArrayList<>();
        for (Player p : room.getPlayers().values()) {
            if (p.isAlive()) {
                alive.add(p);
            }
        }

        if (alive.size() <= 1) {
            room.setState(Room.State.ENDED);

            Player winner = alive.isEmpty() ? null : alive.get(0);
            Map<String, Object> payload = new HashMap<>();

            if (winner != null) {
                Map<String, Object> winnerInfo = new HashMap<>();
                winnerInfo.put("name", winner.getName());
                winnerInfo.put("kills", winner.getKills());
                payload.put("winner", winnerInfo);
            } else {
                payload.put("winner", null);
            }

            List<Map<String, Object>> playersInfo = new ArrayList<>();
            for (Player p : room.getPlayers().values()) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", p.getName());
                m.put("kills", p.getKills());
                m.put("alive", p.isAlive());
                playersInfo.add(m);
            }
            payload.put("players", playersInfo);
            payload.put("elapsed", System.currentTimeMillis() - room.getStartTime());

            broadcast(room, "matchResult", payload);
        }
    }

    */
/**
     * Builds the player list used for the room lobby/update screen.
     * It includes basic information such as player ID, name, color, and room state,
     * then broadcasts that information to all players in the room.
     *//*

    private void broadcastRoomUpdate(Room room) {
        List<Map<String, Object>> playersInfo = new ArrayList<>();
        for (Player p : room.getPlayers().values()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("color", p.getColor());
            m.put("characterType", p.getCharacterType());
            playersInfo.add(m);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("roomId", room.getId());
        payload.put("players", playersInfo);
        payload.put("state", room.getState().toString().toLowerCase());
        payload.put("minPlayers", room.getMaxPlayers());
        payload.put("maxPlayers", room.getMaxPlayers());

        broadcast(room, "roomUpdate", payload);
    }

    */
/**
     * Builds the current game state for all players and bullets.
     * The state includes player positions, health, angle, ammo, kills, and bullet positions,
     * then sends the latest snapshot to every connected client.
     *//*

    private void broadcastState(Room room) {
        List<Map<String, Object>> playersInfo = new ArrayList<>();
        for (Player p : room.getPlayers().values()) {
            playersInfo.add(getStringObjectMap(p));
        }

        List<Map<String, Object>> bulletsInfo = new ArrayList<>();
        for (Bullet b : room.getBullets()) {
            Map<String, Object> m = new HashMap<>();
            m.put("x", b.getX());
            m.put("y", b.getY());
            bulletsInfo.add(m);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("players", playersInfo);
        payload.put("bullets", bulletsInfo);
        payload.put("elapsed", System.currentTimeMillis() - room.getStartTime());

        broadcast(room, "state", payload);
    }

    */
/**
     * Converts a Player object into a simple key-value map for JSON transmission.
     * It contains the player information required by the frontend to render the game state.
     *//*

    private static Map<String, Object> getStringObjectMap(Player p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("x", p.getX());
        m.put("y", p.getY());
        m.put("angle", p.getAngle());
        m.put("health", p.getHealth());
        m.put("alive", p.isAlive());
        m.put("kills", p.getKills());
        m.put("ammo", p.getAmmo());
        m.put("reserveAmmo", p.getReserveAmmo());
        m.put("reloading", p.isReloading());
        m.put("color", p.getColor());
        m.put("characterType", p.getCharacterType());
        return m;
    }

    */
/**
     * Sends a message to every connected player in the room.
     * The message type and data are wrapped into a JSON object and sent through WebSocket.
     *//*

    @Override
    public void broadcast(Room room, String type, Object data) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", type);
        envelope.put("data", data);
        try {
            String json = mapper.writeValueAsString(envelope);
            TextMessage message = new TextMessage(json);
            for (Player p : room.getPlayers().values()) {
                WebSocketSession session = p.getSession();
                if (session != null && session.isOpen()) {
                    broadcastExecutor.submit(() -> {
                        try {
                            synchronized (session) {
                                session.sendMessage(message);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
//    @Override
//    public void broadcast(Room room, String type, Object data) {
//        Map<String, Object> envelope = new HashMap<>();
//        envelope.put("type", type);
//        envelope.put("data", data);
//        try {
//            String json = mapper.writeValueAsString(envelope);
//            TextMessage message = new TextMessage(json);
//            for (Player p : room.getPlayers().values()) {
//                WebSocketSession session = p.getSession();
//                if (session != null && session.isOpen()) {
//                    synchronized (session) {
//                        session.sendMessage(message);
//                    }
//                }
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }

    */
/**
     * Sends a message to one specific WebSocket client.
     * The message type and data are converted to JSON and sent through that session.
     *//*


    @Override
    public void sendTo(WebSocketSession session, String type, Object data) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", type);
        envelope.put("data", data);
        try {
            String json = mapper.writeValueAsString(envelope);
            TextMessage message = new TextMessage(json);
            broadcastExecutor.submit(() -> {
                try {
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
//    @Override
//    public void sendTo(WebSocketSession session, String type, Object data) {
//        Map<String, Object> envelope = new HashMap<>();
//        envelope.put("type", type);
//        envelope.put("data", data);
//        try {
//            String json = mapper.writeValueAsString(envelope);
//            synchronized (session) {
//                session.sendMessage(new TextMessage(json));
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }
}*/
