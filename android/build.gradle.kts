// VPeeN Android - top-level build file.
//
// The app embeds the SAME pure-Python engine as the desktop builds
// (../vpeen) through Chaquopy, shows the same web UI in a WebView and
// adds an Android VpnService + hev-socks5-tunnel for system-wide VPN.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.chaquo.python") version "16.1.0" apply false
}
