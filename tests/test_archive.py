import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.request
import urllib.error
from unittest.mock import patch

TMP = tempfile.TemporaryDirectory()
os.environ['PHONE_ARCHIVE_DATA'] = str(Path(TMP.name) / 'data')
spec = importlib.util.spec_from_file_location('archive_app', Path(__file__).resolve().parents[1] / 'app.py')
app = importlib.util.module_from_spec(spec)
spec.loader.exec_module(app)


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.device = {'id': 'device-test', 'serial': 'test-serial', 'model': 'test'}
        self.remote = '/sdcard/DCIM/photo.jpg'
        self.content = b'original phone contents'
        self.digest = hashlib.sha256(self.content).hexdigest()
        with app.db() as c:
            c.execute('DELETE FROM files')
            c.execute('DELETE FROM events')
            c.execute('INSERT INTO files(device,remote) VALUES(?,?)', (self.device['id'], self.remote))
        app.JOB.update(running=False, errors=[])

    def row(self):
        return app.rows_for(self.device, [self.remote])[0]

    def mark_verified(self, local):
        with app.db() as c:
            c.execute('UPDATE files SET local=?,digest=?,verified_at=?', (str(local), self.digest, app.now()))

    def test_copy_verifies_bytes_and_records_exact_destination(self):
        root = Path(tempfile.mkdtemp(dir=TMP.name))
        def pull(args, *a, **kw):
            Path(args[2]).write_bytes(self.content)
        with patch.object(app, 'remote_digest', return_value=self.digest), patch.object(app, 'adb', side_effect=pull):
            app.copy_files(self.device, [self.row()], str(root))
        row = self.row()
        self.assertEqual(Path(row['local']).read_bytes(), self.content)
        self.assertEqual(row['digest'], self.digest)
        self.assertTrue(row['verified_at'])
        self.assertFalse(app.JOB['errors'])

    def test_corrupt_copy_is_not_recorded_and_partial_is_removed(self):
        root = Path(tempfile.mkdtemp(dir=TMP.name))
        def pull(args, *a, **kw):
            Path(args[2]).write_bytes(b'corrupted')
        with patch.object(app, 'remote_digest', return_value=self.digest), patch.object(app, 'adb', side_effect=pull):
            app.copy_files(self.device, [self.row()], str(root))
        self.assertIsNone(self.row()['verified_at'])
        self.assertFalse(list(root.rglob('*.partial-*')))
        self.assertTrue(app.JOB['errors'])

    def test_phone_change_during_copy_rejects_verification(self):
        root = Path(tempfile.mkdtemp(dir=TMP.name))
        def pull(args, *a, **kw):
            Path(args[2]).write_bytes(self.content)
        with patch.object(app, 'remote_digest', side_effect=[self.digest, 'a' * 64]), patch.object(app, 'adb', side_effect=pull):
            app.copy_files(self.device, [self.row()], str(root))
        self.assertIsNone(self.row()['verified_at'])

    def test_missing_pc_copy_blocks_deletion(self):
        self.mark_verified(Path(TMP.name) / 'not-present')
        with patch.object(app, 'shell') as shell:
            app.delete_files(self.device, [self.row()])
            shell.assert_not_called()
        self.assertIsNone(self.row()['deleted_at'])

    def test_changed_pc_copy_blocks_deletion(self):
        local = Path(TMP.name) / 'changed.jpg'
        local.write_bytes(b'changed')
        self.mark_verified(local)
        with patch.object(app, 'shell') as shell:
            app.delete_files(self.device, [self.row()])
            shell.assert_not_called()
        self.assertIsNone(self.row()['deleted_at'])

    def test_changed_phone_copy_blocks_deletion(self):
        local = Path(TMP.name) / 'same.jpg'
        local.write_bytes(self.content)
        self.mark_verified(local)
        with patch.object(app, 'remote_digest', return_value='b' * 64), patch.object(app, 'shell') as shell:
            app.delete_files(self.device, [self.row()])
            shell.assert_not_called()
        self.assertIsNone(self.row()['deleted_at'])

    def test_valid_delete_is_per_file_and_preserves_pc_copy(self):
        local = Path(TMP.name) / 'valid.jpg'
        local.write_bytes(self.content)
        self.mark_verified(local)
        with patch.object(app, 'remote_digest', return_value=self.digest), patch.object(app, 'shell') as shell:
            app.delete_files(self.device, [self.row()])
            self.assertEqual(shell.call_args.args[0], 'rm -- /sdcard/DCIM/photo.jpg')
        self.assertTrue(self.row()['deleted_at'])
        self.assertEqual(local.read_bytes(), self.content)

    def test_failed_delete_is_not_marked_deleted(self):
        local = Path(TMP.name) / 'delete-failed.jpg'
        local.write_bytes(self.content)
        self.mark_verified(local)
        with patch.object(app, 'remote_digest', return_value=self.digest), patch.object(app, 'shell', side_effect=ValueError('offline')):
            app.delete_files(self.device, [self.row()])
        self.assertIsNone(self.row()['deleted_at'])

    def test_existing_explorer_copy_recognized_by_contents_despite_rename(self):
        root = Path(tempfile.mkdtemp(dir=TMP.name))
        local = root / 'renamed-by-windows.jpg'
        local.write_bytes(self.content)
        with patch.object(app, 'remote_digest', return_value=self.digest), patch.object(app, 'shell', return_value=str(len(self.content))):
            app.recognize_existing(self.device, [self.row()], str(root))
        self.assertEqual(self.row()['local'], str(local))
        self.assertTrue(self.row()['verified_at'])

    def test_same_name_and_size_but_wrong_contents_is_not_recognized(self):
        root = Path(tempfile.mkdtemp(dir=TMP.name))
        (root / 'photo.jpg').write_bytes(b'x' * len(self.content))
        with patch.object(app, 'remote_digest', return_value=self.digest), patch.object(app, 'shell', return_value=str(len(self.content))):
            app.recognize_existing(self.device, [self.row()], str(root))
        self.assertIsNone(self.row()['verified_at'])

    def test_deep_phone_folders_use_unique_short_destination(self):
        remote = '/sdcard/' + '/'.join(['very-long-directory-' * 3] * 8) + '/photo.jpg'
        path = app.destination(Path(TMP.name), self.device, remote)
        self.assertIn('_long_paths', path.parts)
        self.assertLess(len(str(path)), 230)
        other = app.destination(Path(TMP.name), self.device, remote.replace('directory', 'different', 1))
        self.assertNotEqual(path, other)

    def test_shared_storage_restriction(self):
        for path in ['/data/data/private', '/sdcard/../data', '/sdcard-other/file']:
            with self.assertRaises(ValueError):
                app.remote_path(path)

    def test_case_only_windows_names_map_to_different_files(self):
        a = app.destination(Path(TMP.name), self.device, '/sdcard/Camera/File.JPG')
        b = app.destination(Path(TMP.name), self.device, '/sdcard/camera/file.jpg')
        self.assertNotEqual(str(a).lower(), str(b).lower())

    def test_case_only_extension_maps_to_different_files(self):
        a = app.destination(Path(TMP.name), self.device, '/sdcard/photo.JPG')
        b = app.destination(Path(TMP.name), self.device, '/sdcard/photo.jpg')
        self.assertNotEqual(str(a).lower(), str(b).lower())

    def test_rescan_preserves_copy_history_and_marks_unseen(self):
        with patch.object(app, 'shell', return_value=b'/sdcard/Download/new.txt\0'):
            app.scan(self.device, '/sdcard')
        self.assertEqual(self.row()['present'], 0)
        rows = app.rows_for(self.device, ['/sdcard/Download/new.txt'])
        self.assertEqual(rows[0]['present'], 1)


class HTTPTests(unittest.TestCase):
    def request(self, path, token=None, origin=None, method='POST'):
        from io import BytesIO
        from email.message import Message
        from types import SimpleNamespace
        handler = object.__new__(app.Handler)
        handler.server = SimpleNamespace(server_port=8765)
        handler.headers = Message()
        handler.headers['Host'] = '127.0.0.1:8765'
        handler.headers['Content-Length'] = '2'
        if token is not None:
            handler.headers['X-App-Token'] = token
        if origin:
            handler.headers['Origin'] = origin
        handler.path = path
        handler.rfile = BytesIO(b'{}')
        handler.wfile = BytesIO()
        result = {}
        handler.send_response = lambda code: result.update(code=code)
        handler.send_header = lambda *args: None
        handler.end_headers = lambda: None
        getattr(handler, 'do_' + method)()
        return result['code'], handler.wfile.getvalue()

    def test_local_page_has_token_and_no_external_scripts(self):
        code, html = self.request('/', method='GET')
        self.assertEqual(code, 200)
        self.assertIn(app.TOKEN.encode(), html)
        self.assertNotIn(b'src="https://', html)

    def test_unauthorized_mutation_is_rejected(self):
        code, _ = self.request('/api/delete')
        self.assertEqual(code, 403)

    def test_other_origin_is_rejected_even_with_token(self):
        code, _ = self.request('/api/status', app.TOKEN, 'https://example.com')
        self.assertEqual(code, 403)

    def test_authenticated_status_works(self):
        code, body = self.request('/api/status', app.TOKEN)
        self.assertEqual(code, 200)
        data = json.loads(body)
        self.assertIn('message', data)
        self.assertNotIn('serial', data)


if __name__ == '__main__':
    unittest.main()
