"""
VPeeN Android tunnel controller (VpnService + hev-socks5-tunnel).

On Android there is no elevated helper process, no route table to hijack
and no tun2socks executable: the operating system hands the app a TUN file
descriptor through android.net.VpnService, and the Java side
(com.vpeen.app.VpeenVpnService) feeds that descriptor to the
hev-socks5-tunnel JNI library, which forwards every TCP/UDP flow to the
local SOCKS5 listener of the pure-Python core.

This module mirrors the public surface of vpeen.tunnel.TunnelController so
vpeen.webui.Bridge can drive it unchanged:

    state                     down | starting | up | error
    is_available()            (ok, reason)
    cleanup_orphans(log)      no-op (Android tears the TUN down for us)
    connect(...)              ask Java to (prepare and) start the VpnService
    disconnect()              ask Java to stop it
    update_servers(...)       no-op (anti-loop = the app excludes ITSELF
                              from its own VPN, see VpeenVpnService)

State flows back from Java through the module-level on_vpn_state()
callback, which turns it into the same {"type": "tunnel", "phase": ...}
events the desktop worker produces.
"""
import threading

_JAVA_BRIDGE = "com.vpeen.app.VpnBridge"

_lock = threading.Lock()
_active = None          # the AndroidTunnelController the UI is using


def _bridge():
    """The Java VpnBridge class (Chaquopy), or None off-device."""
    try:
        from java import jclass          # provided by Chaquopy
        return jclass(_JAVA_BRIDGE)
    except Exception:
        return None


def on_vpn_state(phase: str, detail: str = "") -> None:
    """Called from Java (any thread) whenever the VpnService changes state.

    phase: up | down | error | ks_engaged | ks_healed | ks_lockdown"""
    with _lock:
        ctl = _active
    if ctl is not None:
        ctl._on_java_state(str(phase), str(detail or ""))


class AndroidTunnelController:
    """Bridge-side manager for the Android VpnService."""

    def __init__(self, events_q):
        global _active
        self.events = events_q
        self.state = "down"
        with _lock:
            _active = self

    # ---------------------------------------------------------------- events
    def _emit(self, **ev):
        ev["type"] = "tunnel"
        self.events.put(ev)

    def log(self, msg, lvl="info"):
        self._emit(ev="log", level=lvl, line=msg)

    def _on_java_state(self, phase: str, detail: str) -> None:
        if phase == "up":
            self.state = "up"
        elif phase in ("down", "error"):
            self.state = "down" if phase == "down" else "error"
        if detail and phase in ("error", "ks_lockdown"):
            self.log(f"Android VPN: {detail}", "warn")
        self._emit(phase=phase, detail=detail)

    # ----------------------------------------------------------- availability
    @staticmethod
    def is_available() -> "tuple[bool, str]":
        if _bridge() is None:
            return False, "Android VPN bridge not available"
        return True, ""

    @staticmethod
    def is_admin() -> bool:
        return True       # VpnService needs a user consent, not root

    @staticmethod
    def cleanup_orphans(log=None) -> None:
        # Android destroys the TUN interface when the VpnService process
        # dies - there are no leftover routes to sweep.
        return None

    # --------------------------------------------------------------- connect
    def connect(self, socks_port: int, upstream_ips=None,
                upstream_hosts=None, mtu: int = 1500, dns: bool = True,
                strict_ips: bool = False, kill_switch: bool = True) -> None:
        if self.state in ("starting", "up"):
            return
        br = _bridge()
        if br is None:
            self.state = "error"
            self._emit(phase="error", detail="Android VPN bridge missing")
            return
        self.state = "starting"
        self.log("Starting the Android VPN service (you may be asked to "
                 "allow VPeeN to set up a VPN connection)...")
        try:
            br.start(int(socks_port), int(mtu), bool(dns), bool(kill_switch))
        except Exception as e:      # Java exception surfaces here
            self.state = "error"
            self._emit(phase="error", detail=f"VpnService start failed: {e}")

    def disconnect(self) -> None:
        br = _bridge()
        if br is None:
            self.state = "down"
            self._emit(phase="down", detail="")
            return
        try:
            br.stop()
        except Exception as e:
            self.state = "down"
            self._emit(phase="down", detail=f"stop error: {e}")

    def update_servers(self, upstream_ips, upstream_hosts=None) -> None:
        # Nothing to do: VPeeN's own sockets never enter the TUN because the
        # VpnService excludes the app package (addDisallowedApplication).
        return None
