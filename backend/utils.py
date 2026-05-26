import math
from datetime import datetime, timezone

def ensure_aware_utc(dt):
    if dt is None:
        return None
    if hasattr(dt, 'tzinfo') and dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt

def haversine(lat1, lon1, lat2, lon2):
    """
    Calculate the great-circle distance between two points 
    on the Earth in meters.
    """
    R = 6371000  # Earth radius in meters
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    
    a = math.sin(dphi / 2)**2 + \
        math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2)**2
    
    return 2 * R * math.atan2(math.sqrt(a), math.sqrt(1 - a))

def calculate_priority_score(family_size: int, is_urgent: bool, last_delivery_date: datetime):
    score = family_size * 10
    if is_urgent:
        score += 50
    if last_delivery_date:
        last_aware = ensure_aware_utc(last_delivery_date)
        score += (datetime.now(timezone.utc) - last_aware).days * 5
    else:
        score += 100
    return score

def is_within_weekly_limit(last_delivery_date: datetime):
    if not last_delivery_date:
        return True
    last_aware = ensure_aware_utc(last_delivery_date)
    return (datetime.now(timezone.utc) - last_aware).days >= 7
