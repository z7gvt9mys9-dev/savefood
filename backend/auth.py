import os
from datetime import datetime, timedelta, timezone
from typing import Optional
from jose import JWTError, jwt
from passlib.context import CryptContext
from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer

from backend.database import get_db_cursor

SECRET_KEY = os.getenv("SECRET_KEY")
if not SECRET_KEY or len(SECRET_KEY) < 32:
    raise RuntimeError(
        "SECRET_KEY env var must be set to a random string of at least 32 characters. "
        "Generate one with: python -c \"import secrets; print(secrets.token_urlsafe(64))\""
    )
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 60 * 24 # 1 day

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="auth/login")

def verify_password(plain_password, hashed_password):
    # A malformed/empty hash in the DB must read as "wrong password" (401),
    # not crash the login endpoint with a 500.
    try:
        return pwd_context.verify(plain_password, hashed_password)
    except ValueError:
        return False

def get_password_hash(password):
    return pwd_context.hash(password)

def create_access_token(data: dict, expires_delta: Optional[timedelta] = None):
    to_encode = data.copy()
    if expires_delta:
        expire = datetime.now(timezone.utc) + expires_delta
    else:
        expire = datetime.now(timezone.utc) + timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    to_encode.update({"exp": expire})
    encoded_jwt = jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)
    return encoded_jwt

def decode_access_token(token: str):
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        return payload
    except JWTError:
        return None

def ensure_owner_or_admin(current_user: dict, role: str, owner_id):
    """Allow the request only for an admin, or for a user of `role` whose token is
    bound (via related_id) to `owner_id`. Raises 403 otherwise."""
    if current_user.get("role") == "admin":
        return
    if current_user.get("role") == role and current_user.get("related_id") == owner_id:
        return
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Forbidden")


def get_current_user(token: str = Depends(oauth2_scheme)):
    payload = decode_access_token(token)
    if payload is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Could not validate credentials",
            headers={"WWW-Authenticate": "Bearer"},
        )
    # Reject blocked users immediately — otherwise an already-issued token would
    # keep working until it expires (login alone can't revoke an active session).
    username = payload.get("sub")
    if username:
        with get_db_cursor() as cur:
            cur.execute("SELECT is_blocked FROM users WHERE username = %s", (username,))
            row = cur.fetchone()
        if row and row["is_blocked"]:
            raise HTTPException(status_code=403, detail="Аккаунт заблокирован администратором")
    return payload
