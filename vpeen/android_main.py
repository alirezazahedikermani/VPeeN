"""
VPeeN Android entry point (called from Java through Chaquopy).

The Android app is a thin native shell around the exact same engine the
desktop builds use:

    MainActivity (WebView)  --http-->  vpeen.webui (127.0.0.1:<port>)
                                           |
                                    vpeen.core / localproxy  (SOCKS5 + HTTP)
                                           ^
    VpeenVpnService (TUN fd) -> hev-socks5-tunnel (JNI) --SOCKS5--+

Java calls start(files_dir) once per process and loads the returned URL in
its WebView.  Calling it again (activity re-created after rotation, or the
user re-opening the app while the VPN keeps the process alive) returns the
URL of the server that is already running.
"""
import os
import threading

_lock = threading.Lock()
_ui = None


def _prepare_env(files_dir: str) -> None:
    # ~/.vpeen must live in the app's private storage; DEFAULT_STATE_PATH
    # and settings.CONFIG_DIR are computed at import time, so HOME has to
    # be right BEFORE any other vpeen module is imported.
    if files_dir:
        os.environ["HOME"] = files_dir
    os.environ["VPEEN_ANDROID"] = "1"


def _android_defaults() -> None:
    """First launch on a phone: VPN mode on, desktop-only features off."""
    from . import settings as cfgmod
    if os.path.exists(cfgmod.CONFIG_PATH):
        return
    cfg = cfgmod.load()
    cfg["tunnel_enabled"] = True        # the whole point on a phone
    cfg["auto_system_proxy"] = False    # no system proxy API to drive
    cfg["auto_update"] = False          # updates arrive as a new APK
    cfgmod.save(cfg)


def start(files_dir: str = "") -> str:
    """Start (once) the local web UI server and return its URL."""
    global _ui
    with _lock:
        if _ui is not None:
            return _ui.url
        _prepare_env(files_dir)
        _android_defaults()
        from .webui import WebUI
        _ui = WebUI(native_window=True).start()
        return _ui.url


def is_connected() -> bool:
    """For the Java side (e.g. to decide whether to keep the process)."""
    with _lock:
        ui = _ui
    if ui is None:
        return False
    try:
        return ui.bridge.phase in ("connected", "connecting")
    except Exception:
        return False


def request_disconnect() -> None:
    """Notification "Disconnect" action: the normal UI disconnect path."""
    with _lock:
        ui = _ui
    if ui is not None:
        ui.bridge.request_disconnect()


def stop() -> None:
    global _ui
    with _lock:
        ui, _ui = _ui, None
    if ui is not None:
        ui.stop()
