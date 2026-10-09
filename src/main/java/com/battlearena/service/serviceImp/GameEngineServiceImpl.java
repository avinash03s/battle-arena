package com.battlearena.service.serviceImp;

import com.battlearena.maps.ArenaMapThree;
import com.battlearena.maps.MapProvider;
import com.battlearena.model.*;
import com.battlearena.repository.RoomRepository;
import com.battlearena.service.GameEngine;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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

    // Seconds a room can sit with only 1 player before bots fill it
    private static final int SOLO_WAIT_SECONDS = 20;

    // Characters bots may use (must match CharacterServiceImpl "available" characters)
    private static final String[] CHARACTER_TYPES = {"penguin", "bear", "fox", "wolf", "horse"};
    private static final String[] BOT_NAMES = {
            "Rocky", "Blaze", "Shadow", "Nova", "Ghost", "Ranger", "Storm", "Havoc"
    };

    private final List<MapProvider> availableMaps =
            List.of(new ArenaMapThree(MAP_WIDTH, MAP_HEIGHT, WALL_THICKNESS));

    private final RoomRepository roomRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService countdownExecutor = Executors.newScheduledThreadPool(4);
    // FIX: bigger pool so one slow phone cannot starve everyone else's sends
    private final ExecutorService broadcastExecutor = Executors.newFixedThreadPool(16);
    private final Random random = new Random();

    // FIX: sessions that are still busy sending a "state" frame. A slow client simply
    // skips frames instead of building an ever-growing queue (which caused the lag).
    private final Set<String> stateSendBusy = ConcurrentHashMap.newKeySet();

    // Solo-wait countdown task per room (cancelled if a real player joins)
    private final Map<String, ScheduledFuture<?>> soloWaitTasks = new ConcurrentHashMap<>();
    // Seconds remaining shown in the solo-wait UI per room
    private final Map<String, Integer> soloWaitRemaining = new ConcurrentHashMap<>();

    public GameEngineServiceImpl(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    /** Finds a waiting room of the requested size, or creates a new one with a random map. */
    @Override
    public Room findOrCreateRoom(int requestedSize) {
        Room room = roomRepository.findAvailableRoom(requestedSize);
        if (room != null) {
            return room;
        }
        MapProvider chosenMap = availableMaps.get(random.nextInt(availableMaps.size()));
        return roomRepository.createRoom(chosenMap, requestedSize);
    }

    /** Walls of the room's map (falls back to the default map). */
    @Override
    public List<Wall> getWallsForRoom(Room room) {
        List<Wall> walls = roomRepository.getWallsForRoom(room);
        if (walls == null) {
            walls = availableMaps.get(0).getWalls();
        }
        return walls;
    }

    /** Decorations of the room's map (falls back to the default map). */
    @Override
    public List<Decoration> getDecorationsForRoom(Room room) {
        List<Decoration> decorations = roomRepository.getDecorationsForRoom(room);
        if (decorations == null) {
            decorations = availableMaps.get(0).getDecorations();
        }
        return decorations;
    }

    /** Adds a player to the room with a spawn point and color, then updates everyone. */
    @Override
    public Player joinRoom(Room room, WebSocketSession session, String name, String characterType) {
        int spawnIndex = room.getPlayers().size();
        double[] spawn = spawnPoint(spawnIndex);
        String color = spawnIndex == 0 ? "#3aa0ff" : "#ff4d4d";

        Player player = new Player(session.getId(), name, spawn[0], spawn[1], color);
        player.setSession(session);
        player.setCharacterType(characterType);

        room.getPlayers().put(session.getId(), player);
        roomRepository.linkSessionToRoom(session.getId(), room.getId());

        broadcastRoomUpdate(room);
        maybeStartCountdown(room);
        // Re-evaluate the bot-fill timer whenever room membership changes
        maybeManageSoloWaitTimer(room);
        return player;
    }

    /** Raw socket disconnect: remove the player, notify others, check for a winner. */
    @Override
    public void handleDisconnect(String sessionId) {
        stateSendBusy.remove(sessionId);
        Room room = roomRepository.getBySessionId(sessionId);
        roomRepository.removeSession(sessionId);
        if (room == null) return;

        boolean wasPlaying = room.getState() == Room.State.PLAYING;
        Player leavingPlayer = room.getPlayers().get(sessionId);

        room.getPlayers().remove(sessionId);

        if (wasPlaying && leavingPlayer != null) {
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

        if (room.getState() == Room.State.WAITING) {
            maybeManageSoloWaitTimer(room);
        }
    }

    /** Player deliberately leaves the match (EXIT button). */
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

    private void notifyPlayerLeft(Room room, Player leftPlayer) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", leftPlayer.getId());
        payload.put("name", leftPlayer.getName());
        broadcast(room, "playerLeft", payload);
    }

    @Override
    public Room getRoomBySession(String sessionId) {
        return roomRepository.getBySessionId(sessionId);
    }

    /** Spawn positions (index wraps around). All points must be OUTSIDE walls on the map. */
    private double[] spawnPoint(int index) {
        double[][] spawns = {
                {150, 150},
                {MAP_WIDTH - 150, 150},
                {150, MAP_HEIGHT - 150},
                {MAP_WIDTH - 150, MAP_HEIGHT - 150},
                {MAP_WIDTH / 2, 150},
                // FIX: was {MAP_WIDTH / 2, MAP_HEIGHT - 150}, which is inside a building on Map 3
                {MAP_WIDTH / 2 - 400, MAP_HEIGHT - 150}
        };
        return spawns[index % spawns.length];
    }

    /** Stores movement input + facing angle (only while playing and alive). */
    @Override
    public void handleInput(Room room, String sessionId, PlayerInput input) {
        if (room.getState() != Room.State.PLAYING) return;
        Player p = room.getPlayers().get(sessionId);
        if (p == null || !p.isAlive()) return;
        p.setInput(input);
        p.setAngle(input.getAngle());
    }

    /** Creates a bullet if the player is alive, not reloading and the fire cooldown has passed. */
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

    /** Reload takes 1.2 seconds, then moves ammo from reserve into the magazine. */
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

    /** When the room is full, run the 3-second countdown and then start the match. */
    private void maybeStartCountdown(final Room room) {
        if (room.getState() != Room.State.WAITING) return;
        if (room.getPlayers().size() < room.getMaxPlayers()) return;

        cancelSoloWaitTimer(room.getId());

        room.setState(Room.State.COUNTDOWN);
        room.setCountdownValue(COUNTDOWN_SECONDS);
        broadcast(room, "countdown", room.getCountdownValue());

        final ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];
        futureHolder[0] = countdownExecutor.scheduleAtFixedRate(() -> {
            room.setCountdownValue(room.getCountdownValue() - 1);
            if (room.getCountdownValue() > 0) {
                broadcast(room, "countdown", room.getCountdownValue());
            } else {
                startGame(room);
                if (futureHolder[0] != null) {
                    futureHolder[0].cancel(false);
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /** With one real player waiting, count down 20s and then fill the room with bots. */
    private void maybeManageSoloWaitTimer(final Room room) {
        final String roomId = room.getId();

        boolean shouldBeRunning = room.getState() == Room.State.WAITING
                && room.getPlayers().size() == 1
                && room.getMaxPlayers() > 1;

        if (!shouldBeRunning) {
            cancelSoloWaitTimer(roomId);
            return;
        }

        if (soloWaitTasks.containsKey(roomId)) {
            return;
        }

        soloWaitRemaining.put(roomId, SOLO_WAIT_SECONDS);
        broadcast(room, "soloWait", buildSoloWaitPayload(SOLO_WAIT_SECONDS));

        ScheduledFuture<?> task = countdownExecutor.scheduleAtFixedRate(() -> {
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
        }, 1, 1, TimeUnit.SECONDS);

        soloWaitTasks.put(roomId, task);
    }

    private Map<String, Object> buildSoloWaitPayload(int secondsRemaining) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("secondsRemaining", secondsRemaining);
        return payload;
    }

    private void cancelSoloWaitTimer(String roomId) {
        ScheduledFuture<?> task = soloWaitTasks.remove(roomId);
        if (task != null) {
            task.cancel(false);
        }
        soloWaitRemaining.remove(roomId);
    }

    /** Fills all empty slots with bots, then starts the normal countdown. */
    private void fillRoomWithBots(Room room) {
        if (room.getState() != Room.State.WAITING) return;

        int slotsToFill = room.getMaxPlayers() - room.getPlayers().size();
        for (int i = 0; i < slotsToFill; i++) {
            addBotPlayer(room);
        }

        broadcastRoomUpdate(room);
        maybeStartCountdown(room);
    }

    /** Picks a character that nobody in the room is using yet (random if all are taken). */
    private String pickBotCharacter(Room room) {
        Set<String> used = new HashSet<>();
        for (Player p : room.getPlayers().values()) {
            used.add(p.getCharacterType());
        }
        List<String> free = new ArrayList<>();
        for (String c : CHARACTER_TYPES) {
            if (!used.contains(c)) free.add(c);
        }
        if (free.isEmpty()) {
            return CHARACTER_TYPES[random.nextInt(CHARACTER_TYPES.length)];
        }
        return free.get(random.nextInt(free.size()));
    }

    /** Creates one bot (no WebSocket session) with a unique id, name, spawn and character. */
    private void addBotPlayer(Room room) {
        int spawnIndex = room.getPlayers().size();
        double[] spawn = spawnPoint(spawnIndex);
        String color = spawnIndex == 0 ? "#3aa0ff" : "#ff4d4d";

        String botId = "bot-" + UUID.randomUUID().toString().substring(0, 8);
        String botName = BOT_NAMES[random.nextInt(BOT_NAMES.length)] + " (Bot)";

        Player bot = new Player(botId, botName, spawn[0], spawn[1], color);
        bot.setBot(true);
        bot.setCharacterType(pickBotCharacter(room));

        room.getPlayers().put(botId, bot);
    }

    private void startGame(Room room) {
        room.setState(Room.State.PLAYING);
        room.setStartTime(System.currentTimeMillis());
        Map<String, Object> payload = new HashMap<>();
        payload.put("startTime", room.getStartTime());
        broadcast(room, "gameStart", payload);
    }

    /** Main game loop, roughly every 33 ms. */
    @Scheduled(fixedRate = 33)
    public void tick() {
        for (Room room : roomRepository.getAllRooms()) {
            if (room.getState() != Room.State.PLAYING) continue;
            try {
                tickRoom(room);
            } catch (Exception e) {
                // FIX: one broken room must never stop the loop for every other room
                e.printStackTrace();
            }
        }
    }

    private void tickRoom(Room room) {
        moveBots(room);
        movePlayers(room);
        moveBullets(room);
        broadcastState(room);
        checkWinner(room);
    }

    /** Basic bot AI: chase the nearest living player, aim, shoot within 700 px. */
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

    /** Moves every living player from their input; X and Y are checked separately for wall sliding. */
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

                if (canMoveTo(newX, p.getY(), walls)) p.setX(newX);
                if (canMoveTo(p.getX(), newY, walls)) p.setY(newY);
            }
        }
    }

    /** Moves bullets, handles wall/player hits, damage, kills and bullet expiry. */
    private void moveBullets(Room room) {
        List<Wall> walls = getWallsForRoom(room);
        // FIX: previously the list was cleared and refilled at the end of the tick, which
        // silently deleted any bullet a player fired in the middle of the tick ("shots not
        // registering"). Now only the bullets that really finished are removed.
        Set<Bullet> finished = Collections.newSetFromMap(new IdentityHashMap<>());

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

            if (hit || b.getLife() <= 0) {
                finished.add(b);
            }
        }

        if (!finished.isEmpty()) {
            room.getBullets().removeIf(finished::contains);
        }
    }

    /** True if a player can stand at (x, y): inside the map and not touching any wall. */
    private boolean canMoveTo(double x, double y, List<Wall> walls) {
        if (x - PLAYER_RADIUS < 0 || x + PLAYER_RADIUS > MAP_WIDTH
                || y - PLAYER_RADIUS < 0 || y + PLAYER_RADIUS > MAP_HEIGHT) {
            return false;
        }
        for (Wall w : walls) {
            if (rectCircleColliding(x, y, w)) {
                return false;
            }
        }
        return true;
    }

    /** Circle (player) vs rectangle (wall) overlap test. */
    private boolean rectCircleColliding(double cx, double cy, Wall rect) {
        double distX = Math.abs(cx - rect.getX() - rect.getW() / 2);
        double distY = Math.abs(cy - rect.getY() - rect.getH() / 2);

        if (distX > rect.getW() / 2 + PLAYER_RADIUS) return false;
        if (distY > rect.getH() / 2 + PLAYER_RADIUS) return false;

        if (distX <= rect.getW() / 2) return true;
        if (distY <= rect.getH() / 2) return true;

        double dx = distX - rect.getW() / 2;
        double dy = distY - rect.getH() / 2;
        return dx * dx + dy * dy <= PLAYER_RADIUS * PLAYER_RADIUS;
    }

    /** Samples points along the bullet's path and checks them against walls. */
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

    /** Ends the match when one or zero players are alive and sends the result to everyone. */
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

    /** Sends the full game snapshot (players + bullets) to every client, including dead players (spectators). */
    private void broadcastState(Room room) {
        List<Map<String, Object>> playersInfo = new ArrayList<>();
        for (Player p : room.getPlayers().values()) {
            playersInfo.add(getStringObjectMap(p));
        }

        List<Map<String, Object>> bulletsInfo = new ArrayList<>();
        for (Bullet b : room.getBullets()) {
            Map<String, Object> m = new HashMap<>();
            m.put("x", round1(b.getX()));
            m.put("y", round1(b.getY()));
            bulletsInfo.add(m);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("players", playersInfo);
        payload.put("bullets", bulletsInfo);
        payload.put("elapsed", System.currentTimeMillis() - room.getStartTime());

        broadcast(room, "state", payload);
    }

    // FIX: round numbers before sending. Full doubles like 123.45600000000002 make every
    // frame much bigger; 1 decimal is plenty for a 2D game and cuts bandwidth a lot.
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Map<String, Object> getStringObjectMap(Player p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("x", round1(p.getX()));
        m.put("y", round1(p.getY()));
        m.put("angle", round2(p.getAngle()));
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
     * Sends a message to every connected (non-bot) player in the room.
     * "state" frames are droppable: if a client is still receiving the previous frame we skip it,
     * so a slow phone never builds up a backlog. All other messages (countdown, gameStart,
     * playerEliminated, matchResult...) are always delivered.
     */
    @Override
    public void broadcast(Room room, String type, Object data) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", type);
        envelope.put("data", data);
        final boolean droppable = "state".equals(type);
        try {
            String json = mapper.writeValueAsString(envelope);
            TextMessage message = new TextMessage(json);
            for (Player p : room.getPlayers().values()) {
                final WebSocketSession session = p.getSession();
                if (session == null || !session.isOpen()) continue;

                final String sid = session.getId();
                if (droppable && !stateSendBusy.add(sid)) {
                    continue; // still sending the previous frame: skip this one
                }

                broadcastExecutor.submit(() -> {
                    try {
                        synchronized (session) {
                            session.sendMessage(message);
                        }
                    } catch (Exception e) {
                        // client went away or timed out; the disconnect handler cleans up
                    } finally {
                        if (droppable) stateSendBusy.remove(sid);
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Sends a message to one specific WebSocket client. */
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
}