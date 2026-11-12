package com.physicscube.luma.shared.ratelimit;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;

/**
 * The key a client is counted under. The servlet remote address is already the real client
 * when the request came through a trusted proxy (server.tomcat.remoteip, LUMA_TRUSTED_PROXIES);
 * a forwarded address from any other peer is ignored by Tomcat. IPv6 clients are counted per
 * /64, the smallest block a home or mobile connection is usually given, so rotating addresses
 * inside it does not escape the limit.
 */
final class ClientAddress {
    private ClientAddress() {
    }

    static String key(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank() || !remoteAddress.matches("[0-9A-Fa-f:.%a-zA-Z]{2,64}")) {
            return "unknown";
        }
        try {
            // Literal addresses only: getByName on an IP literal does no DNS lookup, and the pattern above rejects names.
            if (!remoteAddress.contains(":") && !remoteAddress.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                return "unknown";
            }
            InetAddress address = InetAddress.getByName(remoteAddress.split("%")[0]);
            if (address instanceof Inet4Address) {
                return address.getHostAddress();
            }
            if (address instanceof Inet6Address) {
                byte[] prefix = new byte[8];
                System.arraycopy(address.getAddress(), 0, prefix, 0, 8);
                return HexFormat.of().formatHex(prefix) + "::/64";
            }
            return "unknown";
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
