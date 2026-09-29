package com.battlearena.repository;

import com.battlearena.model.PlayerProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryPlayerProfileRepositoryTest {

    private InMemoryPlayerProfileRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPlayerProfileRepository();
    }

    @Test
    void shouldCreateNewProfileWhenPlayerDoesNotExist() {
        PlayerProfile profile = repository.findOrCreate("player-1");

        assertNotNull(profile);
        assertEquals("player-1", profile.getPlayerId());
    }

    @Test
    void shouldReturnExistingProfile() {
        PlayerProfile first = repository.findOrCreate("player-1");
        PlayerProfile second = repository.findOrCreate("player-1");

        assertSame(first, second);
    }

    @Test
    void shouldFindExistingProfile() {
        repository.findOrCreate("player-1");

        PlayerProfile profile = repository.find("player-1");

        assertNotNull(profile);
        assertEquals("player-1", profile.getPlayerId());
    }

    @Test
    void shouldReturnNullWhenProfileDoesNotExist() {
        PlayerProfile profile = repository.find("unknown-player");

        assertNull(profile);
    }

    @Test
    void shouldCreateProfileWithDefaultCharacter() {
        PlayerProfile profile = repository.findOrCreate("player-1");

        assertEquals("penguin", profile.getSelectedCharacter());
        assertTrue(profile.getUnlockedCharacters().contains("penguin"));
    }
}