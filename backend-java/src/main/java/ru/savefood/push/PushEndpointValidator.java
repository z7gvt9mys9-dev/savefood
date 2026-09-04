package ru.savefood.push;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import ru.savefood.web.ApiException;
final class PushEndpointValidator {
    private static final int MAX_ENDPOINT_LENGTH = 2048;
    private PushEndpointValidator() { }
    static void validate(String raw) {
        if (!isAllowed(raw)) {
            throw new ApiException(400, "Недопустимый push endpoint");
        }
    }
    static boolean isAllowed(String raw) {
        if (raw == null || raw.length() > MAX_ENDPOINT_LENGTH || raw.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        final URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            return addresses.length > 0 && java.util.Arrays.stream(addresses)
                .allMatch(PushEndpointValidator::isPublicInternetAddress);
        } catch (UnknownHostException e) {
            return false;
        }
    }
    private static boolean isPublicInternetAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            int c = Byte.toUnsignedInt(bytes[2]);
            return a != 0
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 192 && b == 0)
                && !(a == 192 && b == 88 && c == 99)
                && !(a == 192 && b == 0 && c == 2)
                && !(a == 198 && (b == 18 || b == 19))
                && !(a == 198 && b == 51 && c == 100)
                && !(a == 203 && b == 0 && c == 113)
                && a < 224;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            return (first & 0xfe) != 0xfc;
        }
        return false;
    }
}
