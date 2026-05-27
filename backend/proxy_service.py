"""
VLESS proxy service using python-v2ray (wraps xray-core).

Reads VLESS_URL from environment, starts a local SOCKS5 tunnel via xray-core,
and exposes get_proxy_url() for use by httpx clients.
"""
import os
import socket
import time
import logging
from pathlib import Path
from typing import Optional

VLESS_URL = os.getenv("VLESS_URL", "").strip()
SOCKS_HOST = "127.0.0.1"
SOCKS_PORT = 10808

_VENDOR_DIR = Path(__file__).parent / "vendor"
_xray_core = None


def _ensure_binary() -> bool:
    """Download xray binary if it is not already present in vendor/."""
    from python_v2ray.downloader import BinaryDownloader

    _VENDOR_DIR.mkdir(parents=True, exist_ok=True)
    downloader = BinaryDownloader(project_root=Path(__file__).parent)
    # Downloads to backend/vendor/xray_linux (Linux) or xray_macos (macOS)
    return downloader.ensure_binary("xray", _VENDOR_DIR, "GFW-knocker/Xray-core")


def start_proxy() -> Optional[str]:
    """
    Start xray-core with the VLESS config from env.
    Returns 'socks5://127.0.0.1:{port}' on success, None if disabled/failed.
    """
    global _xray_core

    if not VLESS_URL:
        return None

    from python_v2ray.config_parser import XrayConfigBuilder, parse_uri
    from python_v2ray.core import XrayCore

    if not _ensure_binary():
        logging.error("[proxy] Failed to obtain xray binary — proxy disabled")
        return None

    params = parse_uri(VLESS_URL)
    if params is None:
        logging.error("[proxy] Failed to parse VLESS_URL — check the format")
        return None

    builder = XrayConfigBuilder()

    # Local SOCKS5 inbound
    builder.add_inbound({
        "tag": "socks-in",
        "listen": SOCKS_HOST,
        "port": SOCKS_PORT,
        "protocol": "socks",
        "settings": {"auth": "noauth", "udp": True},
        "sniffing": {"enabled": False},
    })

    # Outbound built from the parsed VLESS params
    outbound = builder.build_outbound_from_params(params, explicit_tag="proxy")
    if outbound is None:
        logging.error("[proxy] Could not build outbound from VLESS params")
        return None
    builder.add_outbound(outbound)

    # Routing: all traffic via proxy by default
    builder.config["routing"] = {
        "rules": [{"type": "field", "outboundTag": "proxy", "network": "tcp,udp"}]
    }

    try:
        _xray_core = XrayCore(vendor_path=str(_VENDOR_DIR), config_builder=builder)
        _xray_core.start()
    except FileNotFoundError as exc:
        logging.error("[proxy] xray binary missing after download attempt: %s", exc)
        return None
    except Exception as exc:
        logging.error("[proxy] Failed to start xray: %s", exc)
        return None

    if not _xray_core.is_running():
        logging.error("[proxy] xray process exited immediately — check VLESS_URL")
        return None

    # Wait up to 6 s for the SOCKS5 port to open
    for _ in range(30):
        time.sleep(0.2)
        try:
            with socket.create_connection((SOCKS_HOST, SOCKS_PORT), timeout=0.2):
                break
        except OSError:
            continue
    else:
        logging.warning("[proxy] SOCKS5 port did not open — proxy may still be starting")

    proxy_url = f"socks5://{SOCKS_HOST}:{SOCKS_PORT}"
    logging.info("[proxy] VLESS proxy ready: %s → %s:%s", proxy_url, params.address, params.port)
    return proxy_url


def get_proxy_url() -> Optional[str]:
    """Return the SOCKS5 proxy URL if xray is running, else None."""
    if _xray_core and _xray_core.is_running():
        return f"socks5://{SOCKS_HOST}:{SOCKS_PORT}"
    return None


def stop_proxy():
    """Gracefully stop xray-core."""
    if _xray_core:
        _xray_core.stop()
        logging.info("[proxy] xray stopped")
