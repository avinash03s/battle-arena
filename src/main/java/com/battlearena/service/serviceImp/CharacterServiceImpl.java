package com.battlearena.service.serviceImp;

import com.battlearena.model.CharacterDefinition;
import com.battlearena.service.CharacterService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CharacterServiceImpl implements CharacterService {

    private final Map<String, CharacterDefinition> characters = new LinkedHashMap<String, CharacterDefinition>();

    public CharacterServiceImpl() {

        // Available characters
        characters.put("penguin", new CharacterDefinition("penguin", "Penguin", true));

        characters.put("bear", new CharacterDefinition("bear", "Bear", true));

        characters.put("fox", new CharacterDefinition("fox", "Fox", true));

        characters.put("wolf", new CharacterDefinition("wolf", "Wolf", true));

        characters.put("horse", new CharacterDefinition("horse", "Horse", true));

        // Coming Soon
        characters.put("panda", new CharacterDefinition("panda", "Panda", false));

        characters.put("lion", new CharacterDefinition("lion", "Lion", false));

        characters.put("tiger", new CharacterDefinition("tiger", "Tiger", false));

        characters.put("eagle", new CharacterDefinition("eagle", "Eagle", false));

        characters.put("dragon", new CharacterDefinition("dragon", "Dragon", false));
    }

    @Override
    public List<CharacterDefinition> getAllCharacters() {
        return new ArrayList<CharacterDefinition>(characters.values());
    }

    @Override
    public boolean isAvailable(String characterId) {
        CharacterDefinition character = characters.get(characterId);
        return character != null && character.isAvailable();
    }
}