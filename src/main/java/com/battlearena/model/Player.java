package com.battlearena.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.socket.WebSocketSession;

@Data
@NoArgsConstructor
public class Player {
    private String id;
    private String name;
    private double x;
    private double y;
    private double angle;
    private int health = 100;
    private boolean alive = true;
    private int kills = 0;
    private int ammo = 20;
    private int reserveAmmo = 60;
    private boolean reloading = false;
    private long lastShot = 0;
    private String color;
//    private String characterType = "penguin";

    @JsonIgnore
    private PlayerInput input = new PlayerInput();

    @JsonIgnore
    private WebSocketSession session;

    public Player(String id, String name, double x, double y, String color) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
        this.color = color;
    }
}