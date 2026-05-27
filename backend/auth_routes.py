from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.security import OAuth2PasswordRequestForm
from backend import auth
from backend.database import get_db_cursor
from backend.limiter import limiter

router = APIRouter(prefix="/auth", tags=["auth"])

@router.post("/login")
@limiter.limit("5/minute")
def login(request: Request, form_data: OAuth2PasswordRequestForm = Depends()):
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM users WHERE username = %s", (form_data.username,))
        user = cur.fetchone()

        if not user or not auth.verify_password(form_data.password, user["hashed_password"]):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Incorrect username or password",
                headers={"WWW-Authenticate": "Bearer"},
            )

        if user.get("is_blocked"):
            raise HTTPException(status_code=403, detail="Аккаунт заблокирован администратором")

        access_token = auth.create_access_token(
            data={"sub": user["username"], "role": user["role"], "related_id": user["related_id"]}
        )
        return {
            "access_token": access_token,
            "token_type": "bearer",
            "role": user["role"],
            "related_id": user["related_id"],
        }

@router.post("/refresh")
def refresh_token(current_user: dict = Depends(auth.get_current_user)):
    access_token = auth.create_access_token(
        data={"sub": current_user["sub"], "role": current_user["role"], "related_id": current_user["related_id"]}
    )
    return {"access_token": access_token, "token_type": "bearer"}

@router.get("/me")
def read_users_me(current_user: dict = Depends(auth.get_current_user)):
    return current_user
