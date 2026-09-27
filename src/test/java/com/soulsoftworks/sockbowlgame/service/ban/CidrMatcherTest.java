package com.soulsoftworks.sockbowlgame.service.ban;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CidrMatcher} for IPv4 and IPv6 (plan m4-limits WP-G4).
 */
class CidrMatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final Instant LATER = NOW.plus(Duration.ofHours(1));

    private static CidrMatcher matcher(String... cidrs) {
        return matcherExpiring(LATER, cidrs);
    }

    private static CidrMatcher matcherExpiring(Instant expiresAt, String... cidrs) {
        return CidrMatcher.of(java.util.Arrays.stream(cidrs)
                .map(c -> new CidrMatcher.Entry(c, CidrMatcher.Cidr.parse(c), expiresAt))
                .toList());
    }

    /* ---------------------------- IPv4 ---------------------------- */

    @Test
    void v4SingleHost() {
        CidrMatcher m = matcher("203.0.113.7/32");
        assertThat(m.match("203.0.113.7", NOW)).contains(LATER);
        assertThat(m.match("203.0.113.8", NOW)).isEmpty();
    }

    @Test
    void v4BareAddressIsASingleHost() {
        CidrMatcher.Cidr cidr = CidrMatcher.Cidr.parse("198.51.100.20");
        assertThat(cidr.prefix()).isEqualTo(32);
        assertThat(cidr.canonical()).isEqualTo("198.51.100.20/32");
    }

    @Test
    void v4Range() {
        CidrMatcher m = matcher("10.20.0.0/16");
        assertThat(m.match("10.20.0.1", NOW)).isPresent();
        assertThat(m.match("10.20.255.254", NOW)).isPresent();
        assertThat(m.match("10.21.0.1", NOW)).isEmpty();
        assertThat(m.match("10.19.255.255", NOW)).isEmpty();
    }

    @Test
    void v4NonOctetPrefix() {
        CidrMatcher m = matcher("192.0.2.64/27");
        assertThat(m.match("192.0.2.64", NOW)).isPresent();
        assertThat(m.match("192.0.2.95", NOW)).isPresent();
        assertThat(m.match("192.0.2.96", NOW)).isEmpty();
        assertThat(m.match("192.0.2.63", NOW)).isEmpty();
    }

    @Test
    void hostBitsAreClearedInTheCanonicalForm() {
        assertThat(CidrMatcher.Cidr.parse("10.1.2.3/16").canonical()).isEqualTo("10.1.0.0/16");
        assertThat(CidrMatcher.Cidr.parse(" 192.0.2.77/27 ").canonical()).isEqualTo("192.0.2.64/27");
    }

    @Test
    void v4MappedV6ClientMatchesV4Ban() {
        CidrMatcher m = matcher("203.0.113.0/24");
        assertThat(m.match("::ffff:203.0.113.9", NOW)).isPresent();
        assertThat(m.match("[::ffff:203.0.113.9]", NOW)).isPresent();
    }

    /* ---------------------------- IPv6 ---------------------------- */

    @Test
    void v6SingleHost() {
        CidrMatcher m = matcher("2001:db8::1");
        assertThat(m.match("2001:db8:0:0:0:0:0:1", NOW)).isPresent();
        assertThat(m.match("2001:db8::2", NOW)).isEmpty();
    }

    @Test
    void v6Range() {
        CidrMatcher m = matcher("2001:db8:abcd::/48");
        assertThat(m.match("2001:db8:abcd:12::1", NOW)).isPresent();
        assertThat(m.match("2001:db8:abcd:ffff:ffff:ffff:ffff:ffff", NOW)).isPresent();
        assertThat(m.match("2001:db8:abce::1", NOW)).isEmpty();
    }

    @Test
    void v6With64AndZoneAndBrackets() {
        CidrMatcher m = matcher("2001:db8:1:2::/64");
        assertThat(m.match("[2001:db8:1:2:aaaa:bbbb:cccc:dddd]", NOW)).isPresent();
        assertThat(m.match("fe80::1%eth0", NOW)).isEmpty();
        assertThat(m.match("2001:db8:1:3::1", NOW)).isEmpty();
    }

    @Test
    void v6CanonicalForm() {
        assertThat(CidrMatcher.Cidr.parse("2001:0DB8:ABCD:0012:0000:0000:0000:0001/48").canonical())
                .isEqualTo("2001:db8:abcd::/48");
        assertThat(CidrMatcher.Cidr.parse("2001:db8::1").canonical()).isEqualTo("2001:db8::1/128");
    }

    @Test
    void familiesNeverCrossMatch() {
        assertThat(matcher("10.0.0.0/16").match("2001:db8::1", NOW)).isEmpty();
        assertThat(matcher("2001:db8::/48").match("10.0.0.1", NOW)).isEmpty();
    }

    /* ---------------------------- expiry --------------------------- */

    @Test
    void expiredEntriesNeverMatch() {
        CidrMatcher m = matcher("203.0.113.7/32");
        assertThat(m.match("203.0.113.7", LATER.minusMillis(1))).isPresent();
        assertThat(m.match("203.0.113.7", LATER)).isEmpty();
        assertThat(m.match("203.0.113.7", LATER.plusSeconds(1))).isEmpty();
    }

    @Test
    void overlappingBansReportTheLatestExpiry() {
        Instant muchLater = NOW.plus(Duration.ofDays(2));
        CidrMatcher m = CidrMatcher.of(List.of(
                new CidrMatcher.Entry("a", CidrMatcher.Cidr.parse("203.0.113.0/24"), LATER),
                new CidrMatcher.Entry("b", CidrMatcher.Cidr.parse("203.0.113.7/32"), muchLater)));
        assertThat(m.match("203.0.113.7", NOW)).contains(muchLater);
        assertThat(m.match("203.0.113.8", NOW)).contains(LATER);
    }

    @Test
    void emptyMatcherMatchesNothing() {
        assertThat(CidrMatcher.EMPTY.match("127.0.0.1", NOW)).isEmpty();
        assertThat(CidrMatcher.of(List.of())).isSameAs(CidrMatcher.EMPTY);
    }

    /* ---------------------------- parsing -------------------------- */

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-an-ip", "localhost", "example.com/32", "10.0.0.0/33",
            "2001:db8::/129", "10.0.0.0/-1", "10.0.0.0/abc", "10.0.0/24", "999.1.1.1", "10.0.0.0/"})
    void invalidRangesAreRejected(String text) {
        assertThatThrownBy(() -> CidrMatcher.Cidr.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullIsRejected() {
        assertThatThrownBy(() -> CidrMatcher.Cidr.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unparseableClientAddressNeverMatches() {
        CidrMatcher m = matcher("10.0.0.0/16");
        assertThat(m.match(null, NOW)).isEmpty();
        assertThat(m.match("unknown", NOW)).isEmpty();
        assertThat(m.match("some.host.name", NOW)).isEmpty();
    }
}
