#!/usr/bin/env bash
#
# 飞哥影视仓 · 一键发布脚本
#
# 流程：读版本号 → 构建 release APK → 生成 update.json → 上传 Cloudflare R2
# 前置条件：
#   1. app/build.gradle.kts 里的 versionCode / versionName 已手动改好
#   2. .cf-cli/ 里有 wrangler（npm install wrangler），且已 wrangler login
#   3. R2 共享桶已创建并绑定自定义域 dl.bsdnme.me
#
# 多 App 约定：所有 App 共用桶 dl-apps 和域名 dl.bsdnme.me，
# 以 App 全拼做目录前缀区分：
#   dl.bsdnme.me/<APP_ID>/update.json
#   dl.bsdnme.me/<APP_ID>/<APK_PREFIX>-<version>.apk
# 新 App 接入：复制本脚本到新项目，改下面 3 个变量即可。
#
# 用法（版本号自动从 app/build.gradle.kts 读取，只需传更新说明）：
#   ./scripts/release.sh "修复xxx；新增xxx"
#   ./scripts/release.sh "修复xxx" 1   # 第2个参数可选：minVersionCode，低于它的安装强制升级
#
set -euo pipefail

CHANGELOG="${1:?用法: ./release.sh \"更新说明\" [minVersionCode]}"
MIN_VERSION_CODE="${2:-1}"

# ── App 专属配置（新 App 复制脚本时只改这里）────────────────────────────────
APP_ID="feige-yingshicang"                                # App 全拼，桶内目录前缀
APK_PREFIX="feige-yingshicang"                            # 安装包文件名前缀
BUCKET="dl-apps"                                          # 共享桶
DL_BASE="https://dl.bsdnme.me"                            # 下载域名
# ─────────────────────────────────────────────────────────────────────────────

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$PROJECT_DIR/app/build.gradle.kts"
CF_CLI="$PROJECT_DIR/.cf-cli"
W="$CF_CLI/node_modules/.bin/wrangler"
APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
export ANDROID_HOME="${ANDROID_HOME:-/Users/fei.yang/tools/android-sdk}"

fail() { echo "✘ $1"; exit 1; }

# ── 1. 从 build.gradle.kts 读取版本号 ─────────────────────────────────────
VERSION_CODE=$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$GRADLE_FILE" | head -1 | tr -dc '0-9')
VERSION_NAME=$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$GRADLE_FILE" | head -1 | sed -E 's/[^"]*"([^"]+)".*/\1/')
[ -n "$VERSION_CODE" ] && [ "$VERSION_CODE" -gt 0 ] 2>/dev/null || fail "没读到有效的 versionCode，检查 app/build.gradle.kts"
[ -n "$VERSION_NAME" ] || fail "没读到 versionName，检查 app/build.gradle.kts"

# versionCode 没动过就提醒（发了客户端也收不到更新），确认后可继续
if git -C "$PROJECT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  OLD_CODE=$(git -C "$PROJECT_DIR" show HEAD:app/build.gradle.kts 2>/dev/null | grep -E '^[[:space:]]*versionCode[[:space:]]*=' | head -1 | tr -dc '0-9')
  if [ -n "$OLD_CODE" ] && [ "$VERSION_CODE" = "$OLD_CODE" ]; then
    echo "⚠ versionCode 仍是 $OLD_CODE（和上次提交一样），客户端将收不到更新提示"
    printf '继续发布？(y/N) '
    read -r ANSWER
    [ "$ANSWER" = "y" ] || { echo "已取消"; exit 1; }
  fi
fi

echo "→ 版本：v$VERSION_NAME (versionCode $VERSION_CODE)"

APK_NAME="$APK_PREFIX-$VERSION_NAME.apk"

# ── 2. 构建签名 release 包 ────────────────────────────────────────────────
echo "→ 构建 release APK…"
cd "$PROJECT_DIR"
./gradlew :app:assembleRelease -q || fail "构建失败"
[ -f "$APK" ] || fail "找不到 $APK"
echo "  $(du -h "$APK" | cut -f1) · $(shasum -a 256 "$APK" | cut -c1-16)…"

# ── 3. 生成 update.json ──────────────────────────────────────────────────
TMP="$(mktemp -d)"
python3 - "$VERSION_CODE" "$VERSION_NAME" "$CHANGELOG" "$MIN_VERSION_CODE" "$APK_NAME" "$DL_BASE" "$APP_ID" > "$TMP/update.json" <<'PY'
import json, sys
code, name, log, mincode, apk, base, app_id = sys.argv[1:8]
print(json.dumps({
    "versionCode": int(code),
    "versionName": name,
    "minVersionCode": int(mincode),
    "apkUrl": f"{base}/{app_id}/{apk}",
    "changelog": log,
    "force": False,
}, ensure_ascii=False, indent=2))
PY
echo "→ update.json:"
cat "$TMP/update.json"

# ── 4. 上传 R2（先 APK 后 update.json，客户端先见新 JSON 时 APK 已就位）─────
cd "$CF_CLI"
echo "→ 上传 $BUCKET/$APP_ID/$APK_NAME …"
WRANGLER_SEND_METRICS=false "$W" r2 object put "$BUCKET/$APP_ID/$APK_NAME" \
  --file "$APK" --remote \
  --content-type "application/vnd.android.package-archive" > /dev/null \
  || fail "APK 上传失败"
echo "→ 上传 $BUCKET/$APP_ID/update.json …"
WRANGLER_SEND_METRICS=false "$W" r2 object put "$BUCKET/$APP_ID/update.json" \
  --file "$TMP/update.json" --remote \
  --content-type "application/json; charset=utf-8" > /dev/null \
  || fail "update.json 上传失败"

echo
echo "✔ 发布完成 v$VERSION_NAME ($VERSION_CODE)"
echo "  $DL_BASE/$APP_ID/update.json"
echo "  $DL_BASE/$APP_ID/$APK_NAME"
