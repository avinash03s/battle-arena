package com.battlearena.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PlayerInput {
    private boolean up;
    private boolean down;
    private boolean left;
    private boolean right;
    private double angle;
}