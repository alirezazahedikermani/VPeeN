# VPeeN: build hev-socks5-tunnel (tun2socks for Android) as a JNI library.
# The sources are fetched by scripts/fetch-android-deps.sh into
# ./hev-socks5-tunnel (git-ignored, pinned tag, with submodules).
LOCAL_PATH := $(call my-dir)
include $(LOCAL_PATH)/hev-socks5-tunnel/Android.mk
