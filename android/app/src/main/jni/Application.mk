# The JNI natives are registered on com.vpeen.app.TProxyService
# (see src/hev-jni.c: FindClass(PKGNAME "/" CLSNAME)).
APP_OPTIM := release
APP_CFLAGS := -O3 -DPKGNAME=com/vpeen/app -DCLSNAME=TProxyService
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
NDK_TOOLCHAIN_VERSION := clang
