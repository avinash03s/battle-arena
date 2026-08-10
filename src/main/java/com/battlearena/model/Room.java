package com.battlearena.model;

import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
public class Room {
    public enum State { WAITING, COUNTDOWN, PLAYING, ENDED }

    private String id;
    private Map<String, Player> players = new ConcurrentHashMap<>();
    private List<Bullet> bullets = new CopyOnWriteArrayList<>();
    private volatile State state = State.WAITING;
    private volatile int countdownValue = 3;
    private volatile long startTime;
    private volatile int maxPlayers = 4;

    public Room(String id) {
        this.id = id;
    }
}