package com.fivegoodmins.fivegoodmins.shared.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bucket key, which is where ADR-002's rate limiting is bypassed if it is
 * bypassed at all.
 *
 * <p>Three properties are asserted rather than described: an IPv6 client is
 * keyed on the prefix its ISP delegated and not the address it chose, an
 * address that cannot be parsed shares one key with every other such value
 * instead of minting its own, and nothing in here can be made to perform a DNS
 * lookup.
 */
class ClientAddressTest {

    @Test
    void keysAnIpv4ClientOnItsOwnAddress() {
        assertThat(ClientAddress.bucketKey("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void acceptsTheEdgesOfTheIpv4Range() {
        assertThat(ClientAddress.bucketKey("0.0.0.0")).isEqualTo("0.0.0.0");
        assertThat(ClientAddress.bucketKey("255.255.255.255")).isEqualTo("255.255.255.255");
        assertThat(ClientAddress.bucketKey("127.0.0.1")).isEqualTo("127.0.0.1");
    }

    @Test
    void collapsesAnIpv6ClientOntoItsSixtyFourBitPrefix() {
        assertThat(ClientAddress.bucketKey("2a02:1234:5678:9abc:dead:beef:0:1"))
                .isEqualTo("2a02:1234:5678:9abc::/64");
    }

    /**
     * The whole reason for the masking: a household gets a {@code /64}, so
     * without this one attacker has 2^64 buckets and the limit is decoration.
     */
    @Test
    void twoAddressesInOnePrefixShareOneBucket() {
        assertThat(ClientAddress.bucketKey("2a02:1234:5678:9abc::1"))
                .isEqualTo(ClientAddress.bucketKey("2a02:1234:5678:9abc:ffff:ffff:ffff:ffff"));
    }

    @Test
    void twoAddressesInDifferentPrefixesDoNot() {
        assertThat(ClientAddress.bucketKey("2a02:1234:5678:9abc::1"))
                .isNotEqualTo(ClientAddress.bucketKey("2a02:1234:5678:9abd::1"));
    }

    @Test
    void compressesZeroGroupsTheWayTheyAreWritten() {
        assertThat(ClientAddress.bucketKey("2001:db8::1")).isEqualTo("2001:db8:0:0::/64");
    }

    /**
     * Loopback and the unspecified address land on the same key. Harmless and
     * worth pinning: both are local, neither reaches the application from a
     * client, and the alternative would be a special case to explain.
     */
    @Test
    void keysLoopbackAndTheUnspecifiedAddressTogether() {
        assertThat(ClientAddress.bucketKey("::1")).isEqualTo("0:0:0:0::/64");
        assertThat(ClientAddress.bucketKey("::")).isEqualTo("0:0:0:0::/64");
    }

    /**
     * Java hands back an {@code Inet4Address} for the mapped form, and the real
     * client is that IPv4 address -- so it must share a bucket with the same
     * client arriving unmapped, not get a second one.
     */
    @Test
    void treatsAnIpv4MappedAddressAsTheIpv4ClientItIs() {
        assertThat(ClientAddress.bucketKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ClientAddress.bucketKey("::ffff:203.0.113.7"))
                .isEqualTo(ClientAddress.bucketKey("203.0.113.7"));
    }

    @Test
    void dropsAZoneIdentifierRatherThanKeyingOnIt() {
        assertThat(ClientAddress.bucketKey("fe80::1%eth0")).isEqualTo("fe80:0:0:0::/64");
        assertThat(ClientAddress.bucketKey("fe80::1%eth0"))
                .isEqualTo(ClientAddress.bucketKey("fe80::1%eth1"));
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(ClientAddress.bucketKey("  203.0.113.7  ")).isEqualTo("203.0.113.7");
    }

    /**
     * {@code RemoteIpValve} does not validate the entry it takes out of
     * {@code X-Forwarded-For}, so every one of these can reach
     * {@code getRemoteAddr()} from a forged header. Each must land on the one
     * shared key: a key per forgery is the bypass.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "1.2.3",
            "1.2.3.4.5",
            "1.2.3.256",
            "1.2.3.4a",
            "01.2.3.4",
            "1..2.3",
            ".1.2.3",
            "1.2.3.",
            "-1.2.3.4",
            "::zz",
            "2001:db8:::1",
            "example.com",
            "localhost",
            "unknown",
            "0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0"})
    void fallsBackToOneSharedKeyForAnythingUnparseable(String forged) {
        assertThat(ClientAddress.bucketKey(forged)).isEqualTo(ClientAddress.UNRESOLVED);
    }

    @Test
    void treatsAMissingRemoteAddressAsUnresolvedRatherThanThrowing() {
        assertThat(ClientAddress.bucketKey(null)).isEqualTo(ClientAddress.UNRESOLVED);
    }

    /**
     * The one assertion here that is about performance rather than correctness,
     * and it is a denial-of-service property: a resolver call per login attempt,
     * against a name the caller chooses, is a worse weapon than the one being
     * defended against. {@code InetAddress.getByName} resolves numeric-looking
     * hostnames, which is why the dotted-quad form is parsed by hand; a
     * regression would show up here as a wall-clock jump rather than a wrong
     * answer, so the value is asserted too.
     */
    @Test
    void neverResolvesAName() {
        long before = System.nanoTime();
        String key = ClientAddress.bucketKey("1.2.3.4.5.example.invalid");
        long millis = (System.nanoTime() - before) / 1_000_000;

        assertThat(key).isEqualTo(ClientAddress.UNRESOLVED);
        assertThat(millis).as("a DNS lookup would not be this fast").isLessThan(50);
    }
}
