package io.groundedaccess.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class TraceContextTest {

    @Test
    void takesTheTraceIdOfAValidTraceparentHeader() {
        assertThat(TraceContext.fromTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void generatesAnIdInsteadOfTrustingAMalformedHeader() {
        for (String header : List.of("", "garbage", "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01", "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\nlevel=ERROR forged log line")) {
            assertThat(TraceContext.fromTraceparent(header)).matches("[0-9a-f]{32}").isNotEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        }
        assertThat(TraceContext.fromTraceparent(null)).matches("[0-9a-f]{32}");
    }
}
