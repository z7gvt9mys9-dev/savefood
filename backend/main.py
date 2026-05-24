from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
import os

from backend.shop import db, routes as shop_routes
from backend.needy import db as needy_db, routes as needy_routes
from backend.volunteer import db as vol_db, routes as vol_routes

app = FastAPI(title="SaveFood - Backend")
# ensure upload directories exist before mounting static files
os.makedirs(shop_routes.UPLOAD_DIR, exist_ok=True)
os.makedirs(needy_routes.UPLOAD_DIR, exist_ok=True)

@app.on_event("startup")
def startup():
    db.init_db()
    needy_db.init_db()
    vol_db.init_db()

app.mount("/uploads", StaticFiles(directory=shop_routes.UPLOAD_DIR), name="uploads")
app.mount("/needy_uploads", StaticFiles(directory=needy_routes.UPLOAD_DIR), name="needy_uploads")
app.include_router(shop_routes.router)
app.include_router(needy_routes.router)
app.include_router(vol_routes.router)

