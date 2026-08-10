package com.battlearena.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

@Data
@NoArgsConstructor
public class ClientMessage {
    private String type;
    private JsonNode data;
}