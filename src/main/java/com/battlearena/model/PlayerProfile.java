package com.battlearena.model;

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class PlayerProfile {

    private String playerId;

    private int coins = 0;

    private String selectedCharacter = "penguin";

    private Set<String> unlockedCharacters = new HashSet<String>();

    public PlayerProfile(String playerId) {
        this.playerId = playerId;
        // Default character
        unlockedCharacters.add("penguin");
    }
}