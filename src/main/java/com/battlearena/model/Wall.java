package com.battlearena.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Wall {
    private double x;
    private double y;
    private double w;
    private double h;
    private String type;
}