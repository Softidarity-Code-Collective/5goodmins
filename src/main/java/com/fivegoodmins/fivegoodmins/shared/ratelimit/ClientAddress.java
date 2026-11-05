package com.fivegoodmins.fivegoodmins.shared.ratelimit;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Turns {@code request.getRemoteAddr()} into the string a bucket is keyed on
 * (ADR-002, "the bucket key").
 *
 * <p>Two things happen here, and both are decisions rather than tidying.
 *
 * <p><strong>IPv6 collapses to its {@code /64} prefix.</strong> A {@code /128}
 * is one address out of the block an ISP hands a single household, so keying on
 * the whole thing gives an attacker 2^64 buckets for free and the limit stops
 * existing. The {@code /64} is the unit actually delegated, so it is the unit
 * that is worth limiting. The cost is stated plainly: everyone behind one
 * residential prefix shares a bucket, which is the same trade already accepted
 * for everyone behind one IPv4 NAT.
 *
 * <p><strong>An address that cannot be parsed becomes one shared key rather
 * than its own.</strong> {@code RemoteIpValve} does not validate the entry it
 * takes out of {@code X-Forwarded-For} -- its {@code invalidRemoteAddress}
 * message is about reverse-resolving the host, not about rejecting the
 * address -- so a forged header can put arbitrary text in
 * {@code getRemoteAddr()}. Keying on that text would mint a fresh bucket per
 * forgery, which is the bypass this method exists to prevent; funnelling every
 * unparseable value into {@link #UNRESOLVED} makes the limit stricter instead,
 * which is the direction a limiter should break in.
 *
 * <p><strong>Nothing here may perform a DNS lookup.</strong> One lookup per
 * request on the login path, against a name an attacker chooses, is a far
 * better denial-of-service weapon than the one being defended against.
 * {@code InetAddress.getByName} resolves numeric-looking hostnames -- 
 * {@code "1.2.3.4.5"} is a lookup, not a parse failure -- so the dotted-quad
 * form is parsed here by hand and {@code getByName} is called only for values
 * containing a colon, which that method rejects outright rather than resolving
 * (a colon is not legal in a hostname). {@code InetAddress.ofLiteral} would
 * say all of this in one call and arrives in Java 22.
 */
final class ClientAddress {

    /**
     * The single key every unparseable remote address shares. Not a valid
     * address in any notation, so it cannot collide with a real client.
     */
    static final String UNRESOLVED = "unresolved";

    /** The first 64 bits of an IPv6 address: 8 of its 16 bytes. */
    private static final int IPV6_PREFIX_BYTES = 8;

    /**
     * The longest textual form of any address: 45 characters, which is a fully
     * written IPv6 address with an embedded IPv4 one
     * ({@code 0000:0000:0000:0000:0000:ffff:255.255.255.255}). A bound before
     * the parse, so a megabyte-long forged header costs nothing to reject.
     */
    private static final int MAX_LITERAL_LENGTH = 45;

    private ClientAddress() {
    }

    static String bucketKey(String remoteAddress) {
        if (remoteAddress == null) {
            return UNRESOLVED;
        }
        String literal = withoutZoneId(remoteAddress.trim());
        if (literal.isEmpty() || literal.length() > MAX_LITERAL_LENGTH) {
            return UNRESOLVED;
        }
        return literal.indexOf(':') >= 0 ? sixToPrefix(literal) : fourToDottedQuad(literal);
    }

    /**
     * {@code fe80::1%eth0} -- a link-local scope identifier, which is part of
     * the textual form but not of the address. Dropped before parsing so it
     * cannot split one prefix into several keys.
     */
    private static String withoutZoneId(String literal) {
        int percent = literal.indexOf('%');
        return percent < 0 ? literal : literal.substring(0, percent);
    }

    private static String sixToPrefix(String literal) {
        InetAddress address;
        try {
            address = InetAddress.getByName(literal);
        } catch (UnknownHostException notAnAddress) {
            return UNRESOLVED;
        }
        if (!(address instanceof Inet6Address)) {
            // ::ffff:1.2.3.4 -- Java hands back an Inet4Address, and the real
            // client is that IPv4 address.
            return address.getHostAddress();
        }
        byte[] bytes = address.getAddress();
        StringBuilder prefix = new StringBuilder(24);
        for (int i = 0; i < IPV6_PREFIX_BYTES; i += 2) {
            if (i > 0) {
                prefix.append(':');
            }
            prefix.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff)));
        }
        // "::/64" rather than a bare prefix, so a log line says what it is.
        return prefix.append("::/64").toString();
    }

    /**
     * Four dotted decimal octets, parsed strictly and rebuilt from the parsed
     * values, so {@code 1.2.3.4.5}, {@code 1.2.3}, {@code 1.2.3.256} and
     * {@code 01.2.3.4} all land on {@link #UNRESOLVED} instead of reaching a
     * resolver or becoming a key of their own.
     */
    private static String fourToDottedQuad(String literal) {
        int[] octets = new int[4];
        int octet = 0;
        int digits = 0;
        int value = 0;
        for (int i = 0; i < literal.length(); i++) {
            char character = literal.charAt(i);
            if (character == '.') {
                if (digits == 0 || octet == 3) {
                    return UNRESOLVED;
                }
                octets[octet++] = value;
                digits = 0;
                value = 0;
                continue;
            }
            if (character < '0' || character > '9' || digits == 3) {
                return UNRESOLVED;
            }
            if (digits == 1 && value == 0) {
                return UNRESOLVED;   // a leading zero; 010 is not 10 everywhere
            }
            value = value * 10 + (character - '0');
            digits++;
        }
        if (digits == 0 || octet != 3 || value > 255) {
            return UNRESOLVED;
        }
        octets[3] = value;
        for (int i = 0; i < 3; i++) {
            if (octets[i] > 255) {
                return UNRESOLVED;
            }
        }
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }
}
