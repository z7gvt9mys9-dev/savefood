from slowapi import Limiter
from slowapi.util import get_remote_address

# No global default_limits: with SlowAPIMiddleware installed, a default would
# throttle EVERY endpoint (dashboards poll/load many requests). Only routes that
# opt in via @limiter.limit(...) are rate-limited (e.g. /auth/login at 5/minute).
limiter = Limiter(key_func=get_remote_address)
