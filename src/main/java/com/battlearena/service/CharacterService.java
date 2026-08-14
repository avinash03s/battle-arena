package com.battlearena.service;

import com.battlearena.model.CharacterDefinition;

import java.util.List;

public interface CharacterService {

    List<CharacterDefinition> getAllCharacters();

    boolean isAvailable(String characterId);
}
