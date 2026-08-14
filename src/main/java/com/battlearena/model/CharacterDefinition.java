package com.battlearena.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CharacterDefinition {

    private String id;
    private String name;
    private boolean available;
}
