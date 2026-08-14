package com.battlearena.repository;

import com.battlearena.model.PlayerProfile;

public interface PlayerProfileRepository {

    PlayerProfile findOrCreate(String playerId);

    PlayerProfile find(String playerId);
}
