from datetime import datetime, timezone


def ensure_aware_utc(value):
    if isinstance(value, str):
        if value.endswith("Z"):
            value = value[:-1] + "+00:00"
        value = datetime.fromisoformat(value)
    if isinstance(value, datetime) and (value.tzinfo is None or value.tzinfo.utcoffset(value) is None):
        value = value.replace(tzinfo=timezone.utc)
    return value
