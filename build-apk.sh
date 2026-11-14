#!/bin/bash
set -e

# ============ 环境 ============
SDK="$LOCALAPPDATA/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
JAVA_HOME_BIN="${JAVA_HOME:?请先设置 JAVA_HOME}/bin"
BUILD="$(cd "$(dirname "$0")" && pwd)"
KS_PASS="${SHIYE_KS_PASS:?请先设置 SHIYE_KS_PASS（签名口令）}"

AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER="$BT/apksigner.bat"
D8="$BT/d8.bat"
JAVAC="$JAVA_HOME_BIN/javac.exe"
JAR="$JAVA_HOME_BIN/jar.exe"
KEYTOOL="$JAVA_HOME_BIN/keytool.exe"

cd "$BUILD"

echo "[1/7] 清理旧产物"
rm -rf build obj
mkdir -p build/gen build/classes build/dex build/apk obj

echo "[2/7] aapt2 compile 资源"
"$AAPT2" compile --dir res -o build/res.zip

echo "[3/7] aapt2 link 资源 + 生成 R.java"
"$AAPT2" link -o build/base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  -A assets \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --version-code 6 \
  --version-name 1.2.3 \
  build/res.zip

echo "[4/7] javac 编译 MainActivity + R.java"
"$JAVAC" -source 8 -target 8 -bootclasspath "$PLATFORM" \
  -classpath "$PLATFORM" \
  -d build/classes \
  src/shiye/reader/MainActivity.java \
  build/gen/com/shiye/reader/R.java

echo "[5/7] d8 打包 dex"
"$D8" --release --lib "$PLATFORM" \
  --output build/dex \
  $(find build/classes -name '*.class')

# 把 dex 塞进 base.apk
cd build/apk
unzip -q -o ../base.apk
cd ../..
cp build/dex/classes.dex build/apk/classes.dex
rm -f build/apk/res.zip 2>/dev/null || true

echo "[6/7] 重新打包为 unsigned apk（resources.arsc/classes.dex 不压缩）"
rm -f build/unsigned.apk
python pack.py

echo "[7/7] 生成签名 + zipalign + apksigner"
# 生成 keystore（若不存在）
KS="$BUILD/shiye.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -v \
    -keystore "$KS" \
    -alias shiye \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Shiye, OU=Reader, O=Shiye, L=Beijing, ST=Beijing, C=CN" 2>/dev/null
fi

"$ZIPALIGN" -f 4 build/unsigned.apk build/aligned.apk

"$APKSIGNER" sign \
  --ks "$KS" \
  --ks-key-alias shiye \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --out "$BUILD/shiye.apk" \
  build/aligned.apk

echo ""
echo "========== 完成 =========="
ls -la "$BUILD/shiye.apk"
