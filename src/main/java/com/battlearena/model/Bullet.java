package com.battlearena.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
public class Bullet {
    private String id;
    private String ownerId;
    private double x;
    private double y;
    private double dx;
    private double dy;
    private int life = 90;

    public Bullet(String ownerId, double x, double y, double dx, double dy) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.ownerId = ownerId;
        this.x = x;
        this.y = y;
        this.dx = dx;
        this.dy = dy;
    }
}