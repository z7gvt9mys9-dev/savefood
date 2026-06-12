import ipaddress
import os

from slowapi import Limiter
from slowapi.util import get_remote_address

# Whether to trust CF-Connecting-IP / X-Real-IP / X-Forwarded-For:
#   auto  (default) — only when the direct peer is a private/loopback address,
#                     i.e. our own nginx inside the docker network. If the API
#                     port is ever exposed publicly, a spoofed header can no
#                     longer rotate rate-limit buckets at will.
#   always / never  — explicit override for unusual topologies.
TRUST_PROXY_HEADERS = os.getenv("TRUST_PROXY_HEADERS", "auto").strip().lower()


def _peer_is_trusted_proxy(request) -> bool:
    if TRUST_PROXY_HEADERS in ("always", "1", "true", "yes"):
        return True
    if TRUST_PROXY_HEADERS in ("never", "0", "false", "no"):
        return False
    try:
        peer = ipaddress.ip_address(get_remote_address(request) or "")
    except ValueError:
        return False
    return peer.is_private or peer.is_loopback


def get_real_ip(request):
    """Resolve the originating client IP behind nginx/proxy.

    Without this the limiter sees only the docker-network IP of the proxy,
    so every request from every user looks like the same client and the
    /auth/login bucket is effectively shared across the world.

    Behind a Cloudflare Tunnel even nginx's $remote_addr is 127.0.0.1 for
    everyone, so the real client IP only exists in CF-Connecting-IP — check it
    first (nginx forwards it through to us). The headers are honoured only when
    the request actually came through our proxy (see _peer_is_trusted_proxy).
    """
    if _peer_is_trusted_proxy(request):
        cf_ip = request.headers.get("CF-Connecting-IP")
        if cf_ip:
            return cf_ip.strip()
        real_ip = request.headers.get("X-Real-IP")
        if real_ip:
            return real_ip.strip()
        forwarded = request.headers.get("X-Forwarded-For")
        if forwarded:
            return forwarded.split(",")[0].strip()
    return get_remote_address(request)


# No global default_limits: with SlowAPIMiddleware installed, a default would
# throttle EVERY endpoint (dashboards poll/load many requests). Only routes that
# opt in via @limiter.limit(...) are rate-limited (e.g. /auth/login at 5/minute).
limiter = Limiter(key_func=get_real_ip)
