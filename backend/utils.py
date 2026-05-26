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
    """
    Priority Score = f(family_size) + f(urgency) + f(days_without_help)
    """
    score = family_size * 10
    if is_urgent:
        score += 50
    
    if last_delivery_date:
        days_since = (datetime.now() - last_delivery_date).days
        score += days_since * 5 # More days = higher priority
    else:
        score += 100 # Never received help = highest priority
        
    return score

def is_within_weekly_limit(last_delivery_date: datetime):
    if not last_delivery_date:
        return True
    return (datetime.now() - last_delivery_date).days >= 7
