#!/usr/bin/env bash
#
# 飞哥影视仓 · 一键发布脚本
#
# 流程：读版本号 → 构建 release APK → 生成 update.json → 上传 Cloudflare R2
# 前置条件：
#   1. app/build.gradle.kts 里的 versionName 已手动改好
#      （versionCode 漏改时脚本会询问是否自动 +1）
#   2. 认证二选一：export CLOUDFLARE_API_TOKEN=<R2 专用 Token>（推荐），
#      或 .cf-cli/ 里已 wrangler login（全局凭据，权限过大建议迁移）
#   3. .cf-cli/ 里有 wrangler（npm install wrangler）
#   4. R2 共享桶已创建并绑定自定义域 dl.bsdnme.me
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

# ── 0. 认证：优先 R2 专用 API Token ──────────────────────────────────────
# 来源依次为：环境变量 CLOUDFLARE_API_TOKEN → local.properties 的 cloudflare.apiToken
# （推荐，只授 Account > Cloudflare R2 > Edit 权限）；
# 都没有时退回 wrangler login 的全局 oauth_token（账号级权限，建议尽快迁移）。
# 注意：不要把 R2 专用 Token export 进 ~/.zshrc，否则所有 wrangler 命令
# （Workers / Pages 部署等）都会改用这个权限受限的 Token。
if [ -z "${CLOUDFLARE_API_TOKEN:-}" ]; then
  LOCAL_TOKEN=$(grep -E '^cloudflare\.apiToken=' "$PROJECT_DIR/local.properties" 2>/dev/null | cut -d'=' -f2- | tr -d '[:space:]')
  if [ -n "$LOCAL_TOKEN" ]; then CLOUDFLARE_API_TOKEN="$LOCAL_TOKEN"; fi
fi
if [ -n "${CLOUDFLARE_API_TOKEN:-}" ]; then
  export CLOUDFLARE_API_TOKEN
  echo "→ 认证方式：R2 专用 API Token"
else
  echo "⚠ 未配置 R2 专用 Token，使用 wrangler 全局登录凭据（建议在 local.properties 加 cloudflare.apiToken）"
fi

# ── 1. 从 build.gradle.kts 读取版本号 ─────────────────────────────────────
VERSION_CODE=$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$GRADLE_FILE" | head -1 | tr -dc '0-9')
VERSION_NAME=$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$GRADLE_FILE" | head -1 | sed -E 's/[^"]*"([^"]+)".*/\1/')
[ -n "$VERSION_CODE" ] && [ "$VERSION_CODE" -gt 0 ] 2>/dev/null || fail "没读到有效的 versionCode，检查 app/build.gradle.kts"
[ -n "$VERSION_NAME" ] || fail "没读到 versionName，检查 app/build.gradle.kts"

# versionCode 不高于线上已发布版本（客户端收不到更新提示）：询问是否自动 +1。
# 基准优先用线上 update.json（真正决定客户端能否收到提示），拉不到再退回上次提交。
# 注意：变量后不要直接接全角字符（$X（…），set -u 下会被 bash 当成变量名一部分），一律加花括号。
PUBLISHED_CODE=$(curl -sf --max-time 10 "$DL_BASE/$APP_ID/update.json" 2>/dev/null \
  | python3 -c 'import json,sys; print(json.load(sys.stdin).get("versionCode",""))' 2>/dev/null || true)
if [ -n "$PUBLISHED_CODE" ]; then
  echo "→ 线上已发布 versionCode：$PUBLISHED_CODE"
  BASE_CODE="$PUBLISHED_CODE"
else
  BASE_CODE=$(git -C "$PROJECT_DIR" show HEAD:app/build.gradle.kts 2>/dev/null | grep -E '^[[:space:]]*versionCode[[:space:]]*=' | head -1 | tr -dc '0-9')
fi
if [ -n "$BASE_CODE" ] && [ "$VERSION_CODE" -le "$BASE_CODE" ] 2>/dev/null; then
  echo "⚠ 当前 versionCode ${VERSION_CODE} 未高于已发布的 ${BASE_CODE}，客户端将收不到更新提示"
  printf '自动 versionCode+1 并继续发布？(y=自动+1 / n=取消) '
  read -r ANSWER
  case "$ANSWER" in
    y|Y)
      NEW_CODE=$((BASE_CODE + 1))
      # versionName 末段同步 +1（1.0.7 → 1.0.8）：只加 versionCode 会让
      # APK 文件名和已发布版本撞车，覆盖同名 URL
      NEW_NAME=$(echo "$VERSION_NAME" | awk -F. -v OFS=. '{$NF=$NF+1; print}')
      python3 - "$GRADLE_FILE" "$VERSION_CODE" "$NEW_CODE" "$VERSION_NAME" "$NEW_NAME" <<'PY' || fail "版本号更新失败，请手动检查"
from pathlib import Path
import re
import sys

path, old_code, new_code, old_name, new_name = sys.argv[1:]
file = Path(path)
text = file.read_text(encoding="utf-8")
code_pattern = r'(?m)^(\s*versionCode\s*=\s*)' + re.escape(old_code) + r'\s*$'
name_pattern = r'(?m)^(\s*versionName\s*=\s*)"' + re.escape(old_name) + r'"\s*$'
text, code_count = re.subn(code_pattern, lambda match: match.group(1) + new_code, text)
text, name_count = re.subn(name_pattern, lambda match: match.group(1) + '"' + new_name + '"', text)
if code_count != 1 or name_count != 1:
    sys.exit("无法唯一匹配原版本号，未修改文件")
file.write_text(text, encoding="utf-8")
PY
      VERSION_CODE=$NEW_CODE
      VERSION_NAME="$NEW_NAME"
      echo "  versionCode: ${BASE_CODE} → ${NEW_CODE}，versionName → ${NEW_NAME}"
      ;;
    *) echo "已取消"; exit 1 ;;
  esac
fi

echo "→ 版本：v$VERSION_NAME (versionCode $VERSION_CODE)"

APK_NAME="$APK_PREFIX-$VERSION_NAME.apk"

# ── 2. 构建签名 release 包 ────────────────────────────────────────────────
echo "→ 构建 release APK…"
cd "$PROJECT_DIR"
./gradlew :app:assembleRelease -q || fail "构建失败"
[ -f "$APK" ] || fail "找不到 $APK"
python3 - "$PROJECT_DIR/app/build/outputs/apk/release/output-metadata.json" "$VERSION_CODE" "$VERSION_NAME" <<'PY' || fail "构建产物版本与待发布版本不一致"
import json
import sys

with open(sys.argv[1], encoding="utf-8") as file:
    metadata = json.load(file)
elements = metadata.get("elements", [])
if len(elements) != 1 or str(elements[0].get("versionCode")) != sys.argv[2] or elements[0].get("versionName") != sys.argv[3]:
    sys.exit("APK 版本与更新清单不一致")
PY
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
echo "✔ 上传完成 v$VERSION_NAME ($VERSION_CODE)"
echo "  $DL_BASE/$APP_ID/update.json"
echo "  $DL_BASE/$APP_ID/$APK_NAME"

# ── 5. 清理历史 APK（保留线上当前版本和最近的旧版）──────────────────────
if [ "${SKIP_APK_CLEANUP:-0}" = "1" ]; then
  echo "→ 保留所有历史 APK"
  exit 0
fi
KEEP_APK_COUNT="${KEEP_APK_COUNT:-2}"
ACCOUNT_ID="a8dd0cd54cec86b3c5d64bdc3136ab16"
if [ -z "${CLOUDFLARE_API_TOKEN:-}" ]; then
  CLOUDFLARE_API_TOKEN=$(grep '^oauth_token' "$HOME/Library/Preferences/.wrangler/config/default.toml" | cut -d'"' -f2) || fail "发布成功，但读取清理凭据失败"
  export CLOUDFLARE_API_TOKEN
fi
echo "→ 检查并清理旧版本 APK（保留 $KEEP_APK_COUNT 个）…"
python3 "$PROJECT_DIR/scripts/cleanup_apks.py" \
  --account "$ACCOUNT_ID" --bucket "$BUCKET" --app "$APP_ID" \
  --base-url "$DL_BASE" --wrangler "$W" --keep "$KEEP_APK_COUNT" \
  --expected-apk "$APK_NAME" --apply \
  || fail "发布成功，但旧版本清理失败，请检查上方错误并重试清理"
echo "✔ 发布及清理完成 v$VERSION_NAME ($VERSION_CODE)"
