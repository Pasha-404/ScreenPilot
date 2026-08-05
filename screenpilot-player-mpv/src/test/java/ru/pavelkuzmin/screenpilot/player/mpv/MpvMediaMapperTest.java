package ru.pavelkuzmin.screenpilot.player.mpv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.display.RefreshRate;
import ru.pavelkuzmin.screenpilot.domain.media.MediaTrackKind;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MpvMediaMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mapsMediaMetadataTracksAndHdrFromMpvProperties() throws Exception {
        Map<String, JsonNode> properties = Map.of(
                "media-title", JSON.readTree("\"Demo clip\""),
                "file-format", JSON.readTree("\"matroska\""),
                "video-format", JSON.readTree("\"hevc\""),
                "container-fps", JSON.readTree("23.976"),
                "video-params", JSON.readTree("""
                        {"w":3840,"h":2160,"interlaced":false,"bits-per-component":10,
                         "colormatrix":"bt.2020-ncl","gamma":"pq","hw-pixelformat":"p010"}
                        """),
                "track-list", JSON.readTree("""
                        [
                          {"id":1,"type":"video","codec":"hevc","selected":true},
                          {"id":2,"type":"audio","lang":"rus","codec":"eac3","demux-channel-count":6,"selected":true},
                          {"id":3,"type":"sub","lang":"eng","codec":"subrip","forced":true}
                        ]
                        """)
        );

        var media = MpvMediaMapper.mediaInfo(Path.of("fixtures/demo.mkv"), properties);

        assertThat(media.displayName()).isEqualTo("Demo clip");
        assertThat(media.container()).contains("matroska");
        assertThat(media.videoCodec()).contains("hevc");
        assertThat(media.width()).hasValue(3840);
        assertThat(media.height()).hasValue(2160);
        assertThat(media.framesPerSecond()).contains(RefreshRate.of(24_000, 1_001));
        assertThat(media.bitDepth()).hasValue(10);
        assertThat(media.highDynamicRange()).isTrue();
        assertThat(media.selectedAudioTrackId()).hasValue(2);
        assertThat(media.selectedSubtitleTrackId()).isEmpty();
        assertThat(media.tracks()).extracting(track -> track.kind())
                .containsExactly(MediaTrackKind.VIDEO, MediaTrackKind.AUDIO, MediaTrackKind.SUBTITLE);
        assertThat(media.tracks().get(1).channelCount()).hasValue(6);
        assertThat(media.tracks().get(2).forced()).isTrue();
    }

    @Test
    void mapsMpvAudioOutputListWithoutLeakingJsonToDomain() throws Exception {
        var devices = MpvMediaMapper.audioOutputs(JSON.readTree("""
                [
                  {"name":"auto","description":"Automatic"},
                  {"name":"wasapi/{0.0.0.00000000}.Device","description":"HDMI output"}
                ]
                """));

        assertThat(devices).extracting(device -> device.id())
                .containsExactly("auto", "wasapi/{0.0.0.00000000}.Device");
        assertThat(devices.get(1).description()).isEqualTo("HDMI output");
    }
}
