package com.battlearena.service;

import com.battlearena.model.CharacterDefinition;
import com.battlearena.service.serviceImp.CharacterServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CharacterServiceImplTest {

    private CharacterServiceImpl characterService;

    @BeforeEach
    void setUp() {
        characterService = new CharacterServiceImpl();
    }

    @Test
    void shouldReturnAllCharacters() {
        List<CharacterDefinition> characters = characterService.getAllCharacters();
        assertNotNull(characters);
        assertEquals(10, characters.size());
    }

    @Test
    void shouldReturnAvailableForPenguin() {
        assertTrue(characterService.isAvailable("penguin"));
    }

    @Test
    void shouldReturnAvailableForBear() {
        assertTrue(characterService.isAvailable("bear"));
    }

    @Test
    void shouldReturnAvailableForFox() {
        assertTrue(characterService.isAvailable("fox"));
    }

    @Test
    void shouldReturnAvailableForWolf() {
        assertTrue(characterService.isAvailable("wolf"));
    }

    @Test
    void shouldReturnAvailableForHorse() {
        assertTrue(characterService.isAvailable("horse"));
    }

    @Test
    void shouldReturnFalseForLockedCharacter() {
        assertFalse(characterService.isAvailable("panda"));
        assertFalse(characterService.isAvailable("lion"));
        assertFalse(characterService.isAvailable("tiger"));
        assertFalse(characterService.isAvailable("eagle"));
        assertFalse(characterService.isAvailable("dragon"));
    }

    @Test
    void shouldReturnFalseForUnknownCharacter() {
        assertFalse(characterService.isAvailable("unknown"));
    }

    @Test
    void shouldContainCorrectCharacterNames() {
        List<CharacterDefinition> characters = characterService.getAllCharacters();

        assertEquals("Penguin", characters.get(0).getName());
        assertEquals("Bear", characters.get(1).getName());
        assertEquals("Fox", characters.get(2).getName());
        assertEquals("Wolf", characters.get(3).getName());
        assertEquals("Horse", characters.get(4).getName());
    }
}