package com.battlearena.repository;

import com.battlearena.model.PlayerProfile;
import org.springframework.stereotype.Repository;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryPlayerProfileRepository implements PlayerProfileRepository {

    private final Map<String, PlayerProfile> profiles = new ConcurrentHashMap<>();

    @Override
    public PlayerProfile findOrCreate(String playerId) {

        PlayerProfile profile = profiles.get(playerId);

        if (profile == null) {

            profile = new PlayerProfile(playerId);

            profiles.put(playerId, profile);
        }

        return profile;
    }

    @Override
    public PlayerProfile find(String playerId) {
        return profiles.get(playerId);
    }
}