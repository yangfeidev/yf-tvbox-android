#!/usr/bin/env python3
"""
探测订阅源里点播直链的格式构成 —— 用来回答"iOS 版要不要接 VLCKit"。

用法：
    python3 scripts/probe_formats.py <订阅地址>
    python3 scripts/probe_formats.py <订阅地址> --sites 30 --sample 20

流程：拉订阅 → 拆出可用站点（type 0 XML / type 1 JSON / type 4 远程）→
     每个站点调 ?ac=list 拿分类 → 取分类第一页列表 → 从 vod_play_url 抽直链 →
     按扩展名统计。直播 m3u/txt 订阅不统计（iOS 播 HLS 直播无压力）。

判定阈值（脚本结尾会直接给结论）：
    HLS ≥ 85%  → 纯 AVPlayer 够用，不引 VLCKit
    60% ~ 85%  → 引入 VLCKit 作兜底，但默认走 AVPlayer
    < 60%      → 必须 VLCKit，否则大半源在 iOS 上是黑的
"""

import argparse
import json
import re
import ssl
import sys
import urllib.parse
import urllib.request
from collections import Counter
from xml.etree import ElementTree

UA = (
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"
)

HLS = {"m3u8", "m3u"}
# AVPlayer 原生能吃；其余扩展名在 iOS 上大概率黑屏
NATIVE = {"mp4", "mov", "m4v"}
NEED_VLC = {"flv", "mkv", "ts", "mpd", "avi", "wmv", "rmvb", "m2ts", "f4v"}

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def get(url: str, timeout: int = 15) -> str:
    req = urllib.request.Request(
        url,
        headers={"User-Agent": UA, "Accept": "*/*"},
    )
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as resp:
        raw = resp.read()
        charset = resp.headers.get_content_charset() or "utf-8"
    try:
        return raw.decode(charset, errors="replace")
    except LookupError:
        return raw.decode("utf-8", errors="replace")


def parse_subscription(raw: str):
    """返回 (sites, multi_repo_urls)。sites 形如 [(name, api, type)]。"""
    text = raw.strip()
    if not text:
        return [], []

    if text.startswith("<"):
        return _parse_xml_sites(text)

    try:
        obj = json.loads(text)
    except json.JSONDecodeError:
        # 裸地址：直接当一个 CMS 源用
        return [("裸地址源", text, 1)] if "?" in text or "http" in text else [], []

    sites = _parse_json_sites(obj)
    if sites:
        return sites, []
    urls = obj.get("urls") if isinstance(obj, dict) else None
    return [], urls or []


def _parse_xml_sites(text: str):
    sites = []
    try:
        root = ElementTree.fromstring(text)
    except ElementTree.ParseError:
        return [], []
    for el in root.iter():
        tag = el.tag.lower()
        if tag in ("site", "api"):
            api = el.get("api") or (el.text or "").strip()
            name = el.get("key") or el.get("name") or api
            if api.startswith("http"):
                sites.append((name, api, _guess_type(api)))
    return sites, []


def _parse_json_sites(obj):
    sites = []
    if isinstance(obj, dict) and isinstance(obj.get("sites"), list):
        for s in obj["sites"]:
            if not isinstance(s, dict):
                continue
            api = (s.get("api") or "").strip()
            if not api.startswith("http"):
                continue
            kind = _guess_type(api)
            # 3/2/1 之外的（jar/js/python Spider）本脚本探不了，跳过
            if kind in (0, 1, 4):
                sites.append((s.get("name") or api, api, kind))
    return sites


def _guess_type(api: str) -> int:
    low = api.lower()
    if "csp_" in low or low.endswith(".jar") or low.endswith(".js"):
        return 3
    if low.endswith(".php") or "api.php" in low or "provide" in low:
        return 1
    if low.endswith(".json"):
        return 0
    return 1


def api_url(api: str, params: dict) -> str:
    """把 ac=xxx 之类的参数拼到 api 上，兼容 api 自带 ? 的情况。"""
    sep = "&" if "?" in api else "?"
    return api + sep + urllib.parse.urlencode(params, safe="*")


def sample_site(api: str, kind: int, sample: int):
    """返回该站点点播直链列表。kind 0/1 走 XML/JSON CMS，4 走 JSON。"""
    urls = []
    try:
        cat_raw = get(api_url(api, {"ac": "list"}))
    except Exception as e:
        return urls, f"分类接口失败: {type(e).__name__}"

    cat = _as_obj(cat_raw)
    if not cat:
        return urls, "分类接口非 JSON/XML"

    categories = _pick_categories(cat)
    if not categories:
        return urls, "没有可用分类"

    for tid in categories[:3]:
        try:
            page = get(api_url(api, {"ac": "detail", "t": str(tid), "pg": "1"}))
        except Exception:
            continue
        obj = _as_obj(page)
        if not obj:
            continue
        for item in _pick_list(obj):
            for u in _extract_urls(item):
                urls.append(u)
            if len(urls) >= sample:
                return urls, None
    return urls, None


def _as_obj(raw: str):
    text = raw.strip()
    if not text:
        return None
    if text.startswith("<"):
        return _xml_to_dict(text)
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return None


def _xml_to_dict(text: str):
    """CMS 的 XML 响应转成 {list: [...], class: [...]} 便于统一处理。"""
    try:
        root = ElementTree.fromstring(text)
    except ElementTree.ParseError:
        return None
    out = {"list": [], "class": []}
    for node in root.iter():
        tag = node.tag.lower()
        if tag in ("video", "vod"):
            item = {c.tag.lower(): (c.text or "") for c in node}
            out["list"].append(item)
        elif tag == "ty":
            out["class"].append({"type_id": node.get("id") or "", "type_name": (node.text or "")})
    return out or None


def _pick_categories(obj):
    cats = obj.get("class") or obj.get("categories") or []
    ids = []
    for c in cats:
        if isinstance(c, dict):
            tid = c.get("type_id") or c.get("id")
            name = str(c.get("type_name") or "")
            if tid is not None and "搜索" not in name:
                ids.append(tid)
    return ids


def _pick_list(obj):
    items = obj.get("list") or obj.get("data") or []
    if isinstance(items, dict):
        items = items.get("list") or []
    return [i for i in items if isinstance(i, dict)]


def _extract_urls(item: dict):
    raw = item.get("vod_play_url") or item.get("vod_play_from") or ""
    raw = str(raw)
    found = []
    for part in raw.split("#"):
        seg = part.split("$")[-1].strip()
        if seg.startswith("http"):
            found.append(seg)
    return found


def classify(url: str) -> str:
    path = urllib.parse.urlparse(url).path.lower()
    m = re.search(r"\.([a-z0-9]{2,5})$", path)
    ext = m.group(1) if m else "未知"
    if ext in HLS:
        return "HLS"
    if ext in NATIVE:
        return "原生MP4"
    if ext in NEED_VLC:
        return ext
    return "未知"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url", help="订阅地址（标准 TVBox JSON / 多仓 / 裸地址）")
    ap.add_argument("--sites", type=int, default=20, help="最多探测多少个站点")
    ap.add_argument("--sample", type=int, default=20, help="每个站点最多采样多少条直链")
    args = ap.parse_args()

    print(f"拉取订阅：{args.url}")
    try:
        raw = get(args.url, timeout=20)
    except Exception as e:
        print(f"订阅拉取失败：{type(e).__name__}: {e}")
        return 1

    sites, repos = parse_subscription(raw)
    if not sites and repos:
        print("\n这是多仓订阅，包含以下子仓库，请选一个重跑：")
        for u in repos:
            print(f"  - {u}")
        return 0
    if not sites:
        print("\n没解析出站点。可能订阅失效，或只有直播源（直播走 HLS，iOS 无压力）。")
        return 1

    print(f"解析出 {len(sites)} 个可用站点，探测前 {min(args.sites, len(sites))} 个\n")

    counter = Counter()
    per_site = []
    for name, api, kind in sites[: args.sites]:
        urls, err = sample_site(api, kind, args.sample)
        if err:
            per_site.append((name, 0, f"跳过（{err}）"))
            continue
        if not urls:
            per_site.append((name, 0, "无直链"))
            continue
        local = Counter(classify(u) for u in urls)
        counter.update(local)
        hls = local.get("HLS", 0)
        per_site.append((name, len(urls), f"{hls}/{len(urls)} HLS"))

    total = sum(counter.values())
    if total == 0:
        print("一个可播直链都没采到，源可能都是 Spider 类型或已失效。")
        return 1

    print(f"{'站点':<28}{'采样':>6}  构成")
    print("-" * 68)
    for name, n, note in per_site:
        print(f"{name[:26]:<28}{n:>6}  {note}")

    print("\n总体构成")
    print("-" * 68)
    for kind, n in counter.most_common():
        print(f"{kind:<12}{n:>6}  {n / total * 100:5.1f}%")

    hls_ratio = (counter.get("HLS", 0) + counter.get("原生MP4", 0)) / total
    print(f"\nAVPlayer 可直出：{hls_ratio * 100:.1f}%")
    print("-" * 68)
    if hls_ratio >= 0.85:
        print("结论：纯 AVPlayer 够用，不必引 VLCKit。")
    elif hls_ratio >= 0.60:
        print("结论：AVPlayer 打底，VLCKit 只做兜底（按需加载、不打包默认依赖）。")
    else:
        print("结论：必须接 VLCKit，否则大半源在 iOS 上播不了。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
