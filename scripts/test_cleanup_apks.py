import contextlib
import io
import os
import unittest
from unittest.mock import patch

import cleanup_apks


class CleanupTest(unittest.TestCase):
    prefix = 'app/'
    current = 'app/app-3.apk'

    def objects(self, *names):
        return [{'key': 'app/' + name, 'last_modified': f'2026-01-0{i}T00:00:00Z'}
                for i, name in enumerate(names, 1)]

    def test_default_retention_deletes_all_old_versions(self):
        objects = self.objects('app-1.apk', 'app-2.apk', 'app-3.apk', 'latest.apk')
        self.assertEqual(cleanup_apks.select_old(objects, self.prefix, self.current, 1),
                         ['app/app-2.apk', 'app/app-1.apk'])

    def test_missing_old_versions_and_optional_retention(self):
        objects = self.objects('app-1.apk', 'app-3.apk')
        self.assertEqual(cleanup_apks.select_old(objects, self.prefix, self.current, 1), ['app/app-1.apk'])
        self.assertEqual(cleanup_apks.select_old(objects, self.prefix, self.current, 2), [])

    def test_missing_current_or_invalid_keep_stops_cleanup(self):
        objects = self.objects('app-1.apk')
        with self.assertRaises(ValueError):
            cleanup_apks.select_old(objects, self.prefix, self.current, 1)
        with self.assertRaises(ValueError):
            cleanup_apks.select_old(objects, self.prefix, self.current, 0)

    def test_apply_checks_manifest_and_verifies_deletion(self):
        manifest = {'apkUrl': 'https://example.com/app/app-3.apk'}
        before = self.objects('app-1.apk', 'app-3.apk')
        after = self.objects('app-3.apk')
        argv = ['cleanup_apks.py', '--account', 'account', '--bucket', 'bucket', '--app', 'app',
                '--base-url', 'https://example.com', '--wrangler', 'wrangler', '--apply']
        with patch.dict(os.environ, {'CLOUDFLARE_API_TOKEN': 'test'}), \
             patch('sys.argv', argv), patch.object(cleanup_apks, 'read_json', return_value=manifest), \
             patch.object(cleanup_apks, 'list_objects', side_effect=[before, after]), \
             patch.object(cleanup_apks.subprocess, 'run') as run, contextlib.redirect_stdout(io.StringIO()):
            run.return_value.returncode = 0
            cleanup_apks.main()
        self.assertEqual(run.call_args.args[0], ['wrangler', 'r2', 'object', 'delete', 'bucket/app/app-1.apk', '--remote'])

    def test_manifest_change_stops_deletion(self):
        argv = ['cleanup_apks.py', '--account', 'account', '--bucket', 'bucket', '--app', 'app',
                '--base-url', 'https://example.com', '--wrangler', 'wrangler', '--apply']
        with patch.dict(os.environ, {'CLOUDFLARE_API_TOKEN': 'test'}), patch('sys.argv', argv), \
             patch.object(cleanup_apks, 'read_json', side_effect=[{'apkUrl': 'https://example.com/app/app-3.apk'},
                                                                  {'apkUrl': 'https://example.com/app/app-4.apk'}]), \
             patch.object(cleanup_apks, 'list_objects', return_value=self.objects('app-1.apk', 'app-3.apk')), \
             patch.object(cleanup_apks.subprocess, 'run') as run, contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(RuntimeError):
                cleanup_apks.main()
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
