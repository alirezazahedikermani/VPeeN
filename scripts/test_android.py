#!/usr/bin/env python3
"""Android port verification suite.

The APK itself is built by .github/workflows/android.yml; this suite
checks everything that can be verified WITHOUT a device or the SDK:

  1. the Python engine in the exact on-device layout the Gradle build
     produces (vpeen/ with assets bundled inside the package), started
     through vpeen.android_main with a fake Chaquopy `java` module
  2. AndroidTunnelController <-> Java bridge contract (start/stop, the
     on_vpn_state callback, kill-switch phases) and the web Bridge's
     reaction to those events
  3. the web UI hides desktop-only controls on Android
  4. the Android project wiring (JNI class name, manifest, Gradle)

    python scripts/test_android.py
"""
import json
import os
import queue
import re
import shutil
import sys
import tempfile
import time
import types
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID = os.path.join(REPO, "android")

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"  {'PASS' if cond else 'FAIL'}  {name}"
          + (f"  [{detail}]" if detail and not cond else ""))


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


# --------------------------------------------------------------- fake java
class FakeVpnBridge:
    calls = []

    @classmethod
    def start(cls, socks, mtu, dns, ks):
        cls.calls.append(("start", socks, mtu, dns, ks))

    @classmethod
    def stop(cls):
        cls.calls.append(("stop",))


def install_fake_java():
    mod = types.ModuleType("java")

    def jclass(name):
        if name == "com.vpeen.app.VpnBridge":
            return FakeVpnBridge
        raise ImportError(name)

    mod.jclass = jclass
    sys.modules["java"] = mod


# ------------------------------------------------- on-device layout (Gradle)
def build_device_layout(tmp):
    """Mirror app/build.gradle.kts copyVpeenSources."""
    root = os.path.join(tmp, "python")
    shutil.copytree(os.path.join(REPO, "vpeen"), os.path.join(root, "vpeen"),
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    dst = os.path.join(root, "vpeen", "assets")
    shutil.copytree(os.path.join(REPO, "assets", "flags"),
                    os.path.join(dst, "flags"))
    shutil.copy(os.path.join(REPO, "assets", "icon.png"), dst)
    return root


def http(url, data=None):
    req = urllib.request.Request(
        url, data=None if data is None else json.dumps(data).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.status, r.read()


def test_engine(tmp):
    print("\n[1] engine in the on-device layout")
    root = build_device_layout(tmp)
    files_dir = os.path.join(tmp, "files")
    os.makedirs(files_dir)
    sys.path.insert(0, root)
    install_fake_java()

    import vpeen.android_main as am
    check("1a android_main imported from the device layout",
          am.__file__.startswith(root), am.__file__)
    url = am.start(files_dir)
    check("1b start() returns a loopback URL",
          url.startswith("http://127.0.0.1:"), url)
    check("1c start() is idempotent (activity re-created)",
          am.start(files_dir) == url)
    from vpeen.utils import IS_ANDROID
    check("1d IS_ANDROID detected", IS_ANDROID)
    import vpeen.settings as cfgmod
    check("1e ~/.vpeen lives in the app files dir",
          cfgmod.CONFIG_DIR.startswith(files_dir), cfgmod.CONFIG_DIR)
    cfg = json.loads(read(cfgmod.CONFIG_PATH))
    check("1f first-run defaults: VPN mode on",
          cfg.get("tunnel_enabled") is True)
    check("1g first-run defaults: exe self-update + system proxy off",
          cfg.get("auto_update") is False
          and cfg.get("auto_system_proxy") is False)

    code, body = http(url + "api/state")
    st = json.loads(body)
    check("1h /api/state reports platform=android",
          st.get("platform") == "android", st.get("platform"))
    check("1i tunnel (VpnService) reported available",
          st.get("tunnel_available") is True)
    check("1j self-updater disabled on Android",
          st.get("update", {}).get("status") == "disabled")
    for path in ("", "app.js", "style.css", "icon.png", "flags/nl.png"):
        try:
            code, body = http(url + path)
        except Exception as e:
            code, body = repr(e), b""
        check(f"1k serves /{path} from the packaged layout",
              code == 200 and len(body) > 0, str(code))
    try:
        http(url + "api/update/check", {})
        code = 200
    except urllib.error.HTTPError as e:
        code = e.code
        msg = json.loads(e.read()).get("error", "")
    check("1l update endpoint explains the APK route",
          code == 400 and "APK" in msg, str(code))
    return am


def test_controller(am):
    print("\n[2] tunnel controller <-> Java bridge")
    from vpeen.android_tunnel import AndroidTunnelController, on_vpn_state
    bridge = am._ui.bridge
    check("2a Bridge uses AndroidTunnelController",
          isinstance(bridge.tunnel, AndroidTunnelController),
          type(bridge.tunnel).__name__)

    q = queue.Queue()
    ctl = AndroidTunnelController(q)
    FakeVpnBridge.calls.clear()
    ctl.connect(socks_port=1080, upstream_ips=["1.2.3.4"], mtu=1500,
                dns=True, kill_switch=True)
    check("2b connect() calls VpnBridge.start(socks, mtu, dns, ks)",
          ("start", 1080, 1500, True, True) in FakeVpnBridge.calls,
          str(FakeVpnBridge.calls))
    check("2c state=starting until Java confirms", ctl.state == "starting")
    on_vpn_state("up", "")
    evs = []
    while not q.empty():
        evs.append(q.get())
    check("2d on_vpn_state('up') -> tunnel phase event",
          any(e.get("type") == "tunnel" and e.get("phase") == "up"
              for e in evs), str(evs))
    check("2e state=up", ctl.state == "up")
    ctl.connect(socks_port=1080)
    check("2f connect() while up is a no-op",
          sum(1 for c in FakeVpnBridge.calls if c[0] == "start") == 1)
    ctl.disconnect()
    check("2g disconnect() calls VpnBridge.stop()",
          ("stop",) in FakeVpnBridge.calls)
    on_vpn_state("error", "VPN permission revoked by the system")
    evs = []
    while not q.empty():
        evs.append(q.get())
    check("2h error phase carries the detail",
          any(e.get("phase") == "error" and "revoked" in e.get("detail", "")
              for e in evs), str(evs))
    check("2i cleanup_orphans is a safe no-op",
          AndroidTunnelController.cleanup_orphans() is None)

    # the web Bridge reacting to Java states (same path as the desktop worker)
    bridge.handle_event({"type": "tunnel", "phase": "up", "detail": ""})
    check("2j Bridge: up -> connected / tunnel mode",
          bridge.phase == "connected" and bridge.conn_mode == "tunnel")
    bridge.handle_event({"type": "tunnel", "phase": "ks_engaged"})
    check("2k Bridge: kill switch healing state", bridge.ks_state == "healing")
    bridge.handle_event({"type": "tunnel", "phase": "ks_lockdown"})
    check("2l Bridge: kill switch lockdown state",
          bridge.ks_state == "lockdown")

    # diagnostics from Java land in the UI log, not as a phase change
    q2 = queue.Queue()
    ctl2 = AndroidTunnelController(q2)
    on_vpn_state("log", "err|Tunnel check: apps are sending but NOTHING "
                        "comes back")
    ev = q2.get_nowait() if not q2.empty() else {}
    check("2m Java 'log' diagnostics become an error log line",
          ev.get("ev") == "log" and ev.get("level") == "err"
          and "NOTHING" in ev.get("line", "") and "phase" not in ev, str(ev))
    check("2n a log line does not change the tunnel state",
          ctl2.state == "down")
    bridge.tunnel = ctl2                 # keep the module's _active consistent

    # a failed VPN must NOT turn into a fake "connected" proxy fallback
    with bridge.lock:
        bridge.phase, bridge.conn_mode = "connecting", "proxy"
    bridge.cfg["tun_fallback"] = True
    disconnects = []
    orig = bridge.request_disconnect
    bridge.request_disconnect = lambda: disconnects.append(1)
    try:
        bridge._tun_failed_fallback("VPN permission was not granted")
    finally:
        bridge.request_disconnect = orig
    check("2o Android: VPN failure disconnects instead of proxy fallback",
          disconnects and bridge.phase == "connecting", bridge.phase)
    with bridge.lock:
        bridge.phase, bridge.conn_mode = "connected", "tunnel"
    disconnects.clear()
    bridge._stop_after_tunnel = False
    bridge.request_disconnect = lambda: disconnects.append(1)
    try:
        bridge.handle_event({"type": "tunnel", "phase": "error",
                             "detail": "revoked"})
    finally:
        bridge.request_disconnect = orig
    check("2p Android: tunnel loss disconnects (no proxy-only 'connected')",
          bool(disconnects))
    src = read(REPO, "vpeen", "webui.py")
    check("2q Android always connects as a VPN (no proxy-only mode)",
          "IS_ANDROID or bool(cfg.get(\"tunnel_enabled\"))" in src)


def test_frontend():
    print("\n[3] web UI on Android")
    html = read(REPO, "vpeen", "web", "index.html")
    css = read(REPO, "vpeen", "web", "style.css")
    js = read(REPO, "vpeen", "web", "app.js")
    for anchor in ('id="sw-system"', 'id="sw-tunnel"', 'id="set-tunfallback"', 'id="set-autoproxy"', 'id="set-bypass"',
                   'id="set-sweep"', 'id="set-autoupdate"'):
        i = html.index(anchor)
        start = max(html.rfind("<div", 0, i), html.rfind("<label", 0, i))
        # walk outwards to the nearest element carrying the marker
        seg = html[max(0, i - 600):i]
        check(f"3a {anchor} is inside a data-desktop-only block",
              "data-desktop-only" in seg and start >= 0)
    check("3b CSS hides desktop-only controls under .plat-android",
          ".plat-android [data-desktop-only]" in css)
    check("3c app.js tags the page on platform=android",
          'st.platform === "android"' in js and "plat-android" in js)
    check("3d phone-width top bar rule present",
          "@media (max-width: 480px)" in css)


def test_project():
    print("\n[4] Android project wiring")
    app_mk = read(ANDROID, "app", "src", "main", "jni", "Application.mk")
    m = re.search(r"-DPKGNAME=(\S+)\s+-DCLSNAME=(\S+)", app_mk)
    jni_cls = f"{m.group(1)}/{m.group(2)}" if m else ""
    java = read(ANDROID, "app", "src", "main", "java", "com", "vpeen", "app",
                "TProxyService.java")
    pkg = re.search(r"^package\s+([\w.]+);", java, re.M).group(1)
    check("4a JNI natives registered on com/vpeen/app/TProxyService",
          jni_cls == pkg.replace(".", "/") + "/TProxyService", jni_cls)
    for sig in ("boolean TProxyStartService(String configPath, int fd)",
                "boolean TProxyStopService()", "boolean TProxyIsRunning()",
                "long[] TProxyGetStats()"):
        check(f"4b native signature: {sig}", sig in java)
    man = read(ANDROID, "app", "src", "main", "AndroidManifest.xml")
    check("4c VpnService declared with BIND_VPN_SERVICE",
          ".VpeenVpnService" in man
          and "android.permission.BIND_VPN_SERVICE" in man)
    check("4d loopback cleartext allowed for the WebView",
          "@xml/network_security_config" in man)
    svc = read(ANDROID, "app", "src", "main", "java", "com", "vpeen", "app",
               "VpeenVpnService.java")
    check("4e anti-loop: the app excludes itself from its VPN",
          "addDisallowedApplication(getPackageName())" in svc)
    check("4e2 IPv4-only VPN (IPv6 blocked by Android, never half-routed)",
          'addRoute("::"' not in svc and "ipv6:" not in svc)
    check("4e3 traffic self-check reports into the app log",
          "TProxyGetStats()" in svc and 'report("log"' in svc)
    py_tun = read(REPO, "vpeen", "android_tunnel.py")
    for phase in ("up", "down", "error", "ks_engaged", "ks_healed",
                  "ks_lockdown"):
        check(f"4f Java reports phase '{phase}' documented in Python",
              f'"{phase}"' in svc and phase in py_tun)
    gradle = read(ANDROID, "app", "build.gradle.kts")
    check("4g Gradle reads the version from vpeen/__init__.py",
          "vpeen/__init__.py" in gradle)
    check("4h Gradle bundles assets inside the vpeen package",
          'into("vpeen/assets")' in gradle and 'extractPackages("vpeen")'
          in gradle)
    check("4i gradle wrapper committed",
          os.path.isfile(os.path.join(ANDROID, "gradle", "wrapper",
                                      "gradle-wrapper.jar")))


def main():
    print("VPeeN Android port verification\n" + "=" * 52)
    tmp = tempfile.mkdtemp(prefix="vpeen-android-")
    os.environ.pop("VPeeN_DEMO_DATA", None)
    am = None
    try:
        am = test_engine(tmp)
        test_controller(am)
        test_frontend()
        test_project()
    finally:
        if am is not None:
            try:
                am.stop()
            except Exception:
                pass
        time.sleep(0.2)
        shutil.rmtree(tmp, ignore_errors=True)
    print("-" * 52)
    print(f"RESULT: {len(PASS)} passed, {len(FAIL)} failed")
    if FAIL:
        print("FAILED:", *FAIL, sep="\n  - ")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
