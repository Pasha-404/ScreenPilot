package ru.pavelkuzmin.screenpilot.player.mpv;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MpvJsonProtocolTest {

    @Test
    void encodesCommandWithCorrelatingRequestId() throws Exception {
        String json = MpvJsonProtocol.encodeCommand(7, List.of("get_property", "mpv-version"));
        JsonNode message = MpvJsonProtocol.decodeResponse(json);

        assertThat(message.path("request_id").asInt()).isEqualTo(7);
        assertThat(message.path("command").get(0).asText()).isEqualTo("get_property");
        assertThat(message.path("command").get(1).asText()).isEqualTo("mpv-version");
    }
}
