"""Env must be ready BEFORE any backend import: auth.py validates SECRET_KEY
and main.py validates CORS_ORIGIN at module import time."""
import os
import sys

os.environ.setdefault("SECRET_KEY", "test-secret-key-for-pytest-0123456789abcdef")
os.environ.setdefault("CORS_ORIGIN", "http://localhost:3000")

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
