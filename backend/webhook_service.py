"""Outgoing webhooks for Enterprise shops (ERP integration, роадмап v2.2).

Events: lot.taken, lot.confirmed, receipt.parsed. Delivery is a daemon thread
per (event, endpoint) — the request path that triggered the event never waits
for a partner's server. The body is signed with the webhook's secret:

    X-SaveFood-Event:     lot.taken
    X-SaveFood-Signature: sha256=<hex HMAC-SHA256 of the raw body>

Partners verify by recomputing the HMAC over the exact bytes they received.
No retries in v1 — last_status/last_delivery_at on the webhook row make
failures visible in the dashboard instead.
"""
import hashlib
import hmac
import ipaddress
import json
import logging
import socket
import threading
from datetime import datetime, timezone
from typing import Any, Dict
from urllib.parse import urlparse

import httpx

from backend.database import get_db_cursor

EVENTS = ("lot.taken", "lot.confirmed", "receipt.parsed")
DELIVERY_TIMEOUT = 10


def _is_safe_webhook_url(url: str) -> bool:
    """SSRF guard: partners control the webhook URL, so block requests that
    would reach internal infrastructure (cloud metadata, loopback, private
    ranges). Resolves the hostname and checks every resolved IP — a public DNS
    name can still point at an internal address."""
    try:
        parsed = urlparse(url)
    except Exception:
        return False
    if parsed.scheme not in ("http", "https"):
        return False
    host = parsed.hostname
    if not host:
        return False
    try:
        infos = socket.getaddrinfo(host, parsed.port or None, proto=socket.IPPROTO_TCP)
    except socket.gaierror:
        return False
    for info in infos:
        addr = info[4][0]
        try:
            ip = ipaddress.ip_address(addr)
        except ValueError:
            return False
        if (
            ip.is_private
            or ip.is_loopback
            or ip.is_link_local
            or ip.is_reserved
            or ip.is_multicast
            or ip.is_unspecified
        ):
            return False
    return True


def sign(secret: str, body: bytes) -> str:
    digest = hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    return f"sha256={digest}"


def event_matches(events_csv: str, event: str) -> bool:
    subscribed = [e.strip() for e in (events_csv or "*").split(",") if e.strip()]
    return "*" in subscribed or event in subscribed


def _deliver(webhook_id: int, url: str, secret: str, event: str, body: bytes):
    status = None
    if not _is_safe_webhook_url(url):
        logging.warning(
            "[webhook] delivery to %s blocked: resolves to an internal/unsafe address", url
        )
    else:
        try:
            resp = httpx.post(
                url,
                content=body,
                headers={
                    "content-type": "application/json",
                    "x-savefood-event": event,
                    "x-savefood-signature": sign(secret, body),
                },
                timeout=DELIVERY_TIMEOUT,
            )
            status = resp.status_code
        except Exception as e:
            logging.warning("[webhook] delivery to %s failed: %s", url, e)
    try:
        with get_db_cursor() as cur:
            cur.execute(
                "UPDATE webhooks SET last_status = %s, last_delivery_at = %s WHERE id = %s",
                (status, datetime.now(timezone.utc), webhook_id),
            )
    except Exception:
        pass


def fire(shop_id: int, event: str, data: Dict[str, Any]):
    """Fan the event out to every matching active webhook of the shop."""
    try:
        with get_db_cursor() as cur:
            cur.execute(
                "SELECT id, url, secret, events FROM webhooks WHERE shop_id = %s AND active",
                (shop_id,),
            )
            hooks = cur.fetchall()
    except Exception as e:
        logging.warning("[webhook] fire(%s, %s) lookup failed: %s", shop_id, event, e)
        return
    targets = [h for h in hooks if event_matches(h["events"], event)]
    if not targets:
        return
    body = json.dumps(
        {"event": event, "created_at": datetime.now(timezone.utc).isoformat(), "data": data},
        ensure_ascii=False,
    ).encode()
    for h in targets:
        threading.Thread(
            target=_deliver, args=(h["id"], h["url"], h["secret"], event, body), daemon=True
        ).start()
