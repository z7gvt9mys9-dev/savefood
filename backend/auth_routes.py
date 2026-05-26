from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import OAuth2PasswordRequestForm
from backend import auth, shop, needy, volunteer
from backend.database import get_db_cursor

router = APIRouter(prefix="/auth", tags=["auth"])

@router.post("/login")
def login(form_data: OAuth2PasswordRequestForm = Depends()):
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM users WHERE username = %s", (form_data.username,))
        user = cur.fetchone()
        
        if not user or not auth.verify_password(form_data.password, user["hashed_password"]):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Incorrect username or password",
                headers={"WWW-Authenticate": "Bearer"},
            )
        
        access_token = auth.create_access_token(
            data={"sub": user["username"], "role": user["role"], "related_id": user["related_id"]}
        )
        return {
            "access_token": access_token,
            "token_type": "bearer",
            "role": user["role"],
            "related_id": user["related_id"],
        }

@router.get("/me")
def read_users_me(current_user: dict = Depends(auth.get_current_user)):
    return current_user
