#!/usr/bin/env python3
"""清理 R2 中本应用的历史 APK；默认预览，--apply 才删除。"""
import argparse
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request


def read_json(url, token=None):
    headers = {'Cache-Control': 'no-cache', 'User-Agent': 'curl/8.0'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
        return json.load(response)


def list_objects(account, bucket, prefix, token):
    base = f'https://api.cloudflare.com/client/v4/accounts/{account}/r2/buckets/{bucket}/objects'
    cursor = None
    seen = set()
    objects = []
    while True:
        params = {'prefix': prefix, 'per_page': 1000}
        if cursor:
            params['cursor'] = cursor
        data = read_json(base + '?' + urllib.parse.urlencode(params), token)
        if data.get('success') is not True or not isinstance(data.get('result'), list):
            raise RuntimeError('Cloudflare 列表请求失败：' + str(data.get('errors', '响应格式错误')))
        objects.extend(data['result'])
        info = data.get('result_info') or {}
        if len(data['result']) == 1000 and 'is_truncated' not in info:
            raise RuntimeError('Cloudflare 未返回分页状态，已停止清理')
        if not info.get('is_truncated'):
            return objects
        cursor = info.get('cursor')
        if not cursor or cursor in seen:
            raise RuntimeError('Cloudflare 分页游标无效，已停止清理')
        seen.add(cursor)


def select_old(objects, prefix, current, keep):
    if keep < 2:
        raise ValueError('至少保留当前版本和一个历史版本（KEEP_APK_COUNT >= 2）')
    candidates = []
    for obj in objects:
        key = obj.get('key', '')
        if key.startswith(prefix) and '/' not in key[len(prefix):] and key.endswith('.apk') and not key.endswith('/latest.apk'):
            if not obj.get('last_modified'):
                raise ValueError(f'缺少修改时间：{key}')
            candidates.append(obj)
    if current not in {obj['key'] for obj in candidates}:
        raise ValueError('线上更新清单指向的 APK 不在对象列表中，已停止清理')
    others = sorted((o for o in candidates if o['key'] != current), key=lambda o: o['last_modified'], reverse=True)
    return [o['key'] for o in others[keep - 1:]]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--account', required=True)
    parser.add_argument('--bucket', required=True)
    parser.add_argument('--app', required=True)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--wrangler', required=True)
    parser.add_argument('--keep', type=int, default=2)
    parser.add_argument('--expected-apk', help='本次发布的文件名；与线上清单不一致时停止')
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    token = os.environ.get('CLOUDFLARE_API_TOKEN')
    if not token:
        raise ValueError('缺少 Cloudflare API Token，无法列出对象')
    prefix = args.app + '/'
    manifest_url = args.base_url.rstrip('/') + '/' + prefix + 'update.json'
    manifest = read_json(manifest_url + '?cleanup_check=' + str(__import__('time').time_ns()))
    parsed = urllib.parse.urlparse(manifest['apkUrl'])
    expected_base = urllib.parse.urlparse(args.base_url)
    current = urllib.parse.unquote(parsed.path.lstrip('/'))
    if parsed.scheme != 'https' or parsed.netloc != expected_base.netloc or not current.startswith(prefix) or '/' in current[len(prefix):] or not current.endswith('.apk'):
        raise ValueError('更新清单中的 APK 地址不属于本应用，已停止清理')
    if args.expected_apk and current != prefix + args.expected_apk:
        raise ValueError('线上更新清单与本次发布的 APK 不一致，已停止清理')
    objects = list_objects(args.account, args.bucket, prefix, token)
    old = select_old(objects, prefix, current, args.keep)
    print(f'线上当前 APK：{current}；保留总数：{args.keep}', flush=True)
    if not old:
        print('没有需要清理的旧 APK')
        return
    print(('将删除：' if args.apply else '预览，待删除：'), flush=True)
    for key in old:
        print('  ' + key, flush=True)
    if not args.apply:
        return
    # 若线上清单在列举期间发生变化，不执行删除。
    latest = read_json(manifest_url + '?cleanup_check=' + str(__import__('time').time_ns()))
    if latest.get('apkUrl') != manifest.get('apkUrl'):
        raise RuntimeError('线上更新清单已变化，已停止清理')
    failures = []
    env = dict(os.environ, WRANGLER_SEND_METRICS='false')
    for key in old:
        result = subprocess.run([args.wrangler, 'r2', 'object', 'delete', args.bucket + '/' + key, '--remote'], env=env, stdout=subprocess.DEVNULL)
        if result.returncode:
            failures.append(key)
            print('删除失败：' + key, file=sys.stderr, flush=True)
        else:
            print('已删除：' + key, flush=True)
    if failures:
        raise RuntimeError(f'{len(failures)} 个旧 APK 删除失败')
    remaining = {o['key'] for o in list_objects(args.account, args.bucket, prefix, token)}
    if current not in remaining or any(key in remaining for key in old):
        raise RuntimeError('清理后的对象校验失败，请检查 Cloudflare')
    print('清理完成，已验证线上当前 APK 保留且旧 APK 已删除')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('清理失败：' + str(exc), file=sys.stderr)
        sys.exit(1)
