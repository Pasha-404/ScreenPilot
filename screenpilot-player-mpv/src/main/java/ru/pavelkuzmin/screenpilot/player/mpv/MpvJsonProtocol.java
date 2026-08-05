package ru.pavelkuzmin.screenpilot.player.mpv;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Objects;

/** JSON IPC framing helpers for mpv. Each frame is a single UTF-8 JSON line. */
public final class MpvJsonProtocol {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MpvJsonProtocol() {
    }

    public static String encodeCommand(long requestId, List<?> command) throws JsonProcessingException {
        if (requestId < 1) {
            throw new IllegalArgumentException("requestId must be positive");
        }
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        ObjectNode message = MAPPER.createObjectNode();
        message.put("request_id", requestId);
        message.set("command", MAPPER.valueToTree(command));
        return MAPPER.writeValueAsString(message);
    }

    public static JsonNode decodeResponse(String jsonLine) throws JsonProcessingException {
        return MAPPER.readTree(Objects.requireNonNull(jsonLine, "jsonLine"));
    }
}
