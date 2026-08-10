package com.battlearena.maps;

import com.battlearena.model.Decoration;
import com.battlearena.model.Wall;
import java.util.List;

// Har map is interface ko implement karega
public interface MapProvider {
    String getName();
    List<Wall> getWalls();
    List<Decoration> getDecorations();
}