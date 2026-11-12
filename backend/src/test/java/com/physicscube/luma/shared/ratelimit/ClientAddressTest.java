package com.physicscube.luma.shared.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ClientAddressTest {
    @Test
    void ipv4IsKeptAndIpv6IsReducedToItsSlash64() {
        assertEquals("203.0.113.7", ClientAddress.key("203.0.113.7"));
        assertEquals("20010db800010002::/64", ClientAddress.key("2001:db8:1:2::abcd"));
        assertEquals("20010db800010002::/64", ClientAddress.key("2001:0db8:0001:0002:ffff:ffff:ffff:ffff"));
        assertEquals("0000000000000000::/64", ClientAddress.key("0:0:0:0:0:0:0:1"));
        assertEquals("fe80000000000000::/64", ClientAddress.key("fe80::1%eth0"));
    }

    @Test
    void anythingThatIsNotAnAddressLiteralIsUnknownAndNeverLookedUp() {
        for (String bad : new String[] {null, "", " ", "example.com", "localhost", "999.1.1.1x", "1.2.3", "a".repeat(80), "1.2.3.4; drop"}) {
            assertEquals("unknown", ClientAddress.key(bad), String.valueOf(bad));
        }
    }

    @Test
    void windowsAreAlignedToTheEpoch() {
        Instant now = Instant.parse("2027-01-13T12:34:56Z");
        assertEquals(Instant.parse("2027-01-13T12:30:00Z"), RateLimitCounters.windowStart(now, Duration.ofMinutes(5)));
        assertEquals(Instant.parse("2027-01-13T12:00:00Z"), RateLimitCounters.windowStart(now, Duration.ofHours(1)));
    }
}
