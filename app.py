"""Local, keyboard-accessible Android archive. Python 3.10+, standard library only."""
import hashlib
import json
import os
import re
import secrets
import shlex
import shutil
import sqlite3
import subprocess
import threading
import time
import urllib.request
import webbrowser
import zipfile
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path, PurePosixPath
from urllib.parse import urlparse

BASE = Path(__file__).resolve().parent
DATA = Path(os.environ.get('PHONE_ARCHIVE_DATA', str(BASE / 'data')))
DATA.mkdir(exist_ok=True)
DB = DATA / 'history.sqlite3'
TOKEN = secrets.token_urlsafe(32)
ADB = BASE / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
DEVICE = None
JOB = {'running': False, 'message': 'آماده', 'done': 0, 'total': 0, 'errors': []}
LOCK = threading.Lock()


@contextmanager
def db():
    conn = sqlite3.connect(DB, timeout=30)
    conn.row_factory = sqlite3.Row
    try:
        with conn:
            yield conn
    finally:
        conn.close()


with db() as c:
    c.executescript('''CREATE TABLE IF NOT EXISTS files (
        device TEXT NOT NULL, remote TEXT NOT NULL, local TEXT,
        digest TEXT, size INTEGER, verified_at TEXT, deleted_at TEXT, present INTEGER NOT NULL DEFAULT 1,
        PRIMARY KEY(device,remote));
        CREATE TABLE IF NOT EXISTS events (
        id INTEGER PRIMARY KEY, time TEXT, device TEXT, remote TEXT,
        action TEXT, local TEXT, detail TEXT);''')
    if 'present' not in [r['name'] for r in c.execute('PRAGMA table_info(files)')]:
        c.execute('ALTER TABLE files ADD COLUMN present INTEGER NOT NULL DEFAULT 1')


def now():
    return time.strftime('%Y-%m-%d %H:%M:%S')


def event(device, remote, action, local='', detail=''):
    with db() as c:
        c.execute('INSERT INTO events(time,device,remote,action,local,detail) VALUES(?,?,?,?,?,?)',
                  (now(), device, remote, action, local, detail))


def remote_path(value):
    if not isinstance(value, str) or '\x00' in value or any(x in value for x in ['\r', '\n']):
        raise ValueError('مسیر گوشی نامعتبر است.')
    p = PurePosixPath(value)
    if '..' in p.parts or not (value == '/sdcard' or value.startswith('/sdcard/')):
        raise ValueError('فقط مسیرهای داخل /sdcard مجازند.')
    return str(p)


def adb(args, device=None, timeout=60, binary=False):
    if not ADB.is_file():
        raise ValueError('ابتدا ابزار گوگل را دریافت کنید.')
    cmd = [str(ADB)] + (['-s', device['serial']] if device else ['-d']) + list(args)
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout,
                           creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    except subprocess.TimeoutExpired:
        raise ValueError('زمان عملیات تمام شد؛ اتصال گوشی را بررسی کنید.') from None
    if p.returncode:
        # ADB errors can include the serial; do not display it.
        msg = p.stderr.decode('utf-8', 'replace').strip()
        if device:
            msg = msg.replace(device['serial'], '[device]')
        raise ValueError('خطای ADB: ' + msg[:700])
    return p.stdout if binary else p.stdout.decode('utf-8', 'replace').strip()


def shell(command, device, timeout=60, binary=False):
    return adb(['shell', command], device, timeout, binary)


def connect():
    global DEVICE
    if adb(['get-state']) != 'device':
        raise ValueError('یک گوشی را با USB متصل و پیام اشکال‌زدایی را تأیید کنید.')
    serial = adb(['get-serialno'])
    if not serial or serial == 'unknown':
        raise ValueError('شناسه اتصال دریافت نشد.')
    device = {'serial': serial, 'id': hashlib.sha256(serial.encode()).hexdigest()}
    device['model'] = shell('getprop ro.product.model', device)
    DEVICE = device
    return {'model': device['model'], 'device': device['id'][:12]}


def require_device():
    if not DEVICE:
        raise ValueError('ابتدا اتصال گوشی را بررسی کنید.')
    return DEVICE.copy()


def digest_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def remote_digest(path, device):
    path = remote_path(path)
    quoted = shlex.quote(path)
    # Reject symlinks: only actual regular files in shared storage may be removed.
    output = shell(f'if [ -f {quoted} ] && [ ! -L {quoted} ]; then toybox sha256sum {quoted}; else exit 2; fi', device, 900)
    match = re.match(r'^([a-fA-F0-9]{64})\s', output)
    if not match:
        raise ValueError('بررسی SHA-256 این فایل ممکن نشد؛ حذف مجاز نیست.')
    return match.group(1).lower()


def safe_component(name):
    clean = re.sub(r'[<>:"/\\|?*\x00-\x1f]', '_', name).rstrip(' .') or 'file'
    # Hash every component to distinguish case-only names and Windows reserved names.
    return clean[:65] + '__' + hashlib.sha256(name.encode()).hexdigest()[:12]


def destination(root, device, remote):
    parts = PurePosixPath(remote).relative_to('/sdcard').parts
    folders = [safe_component(x) for x in parts[:-1]]
    filename = parts[-1]
    suffix = PurePosixPath(filename).suffix
    if len(suffix) > 12 or re.search(r'[^.a-zA-Z0-9]', suffix):
        suffix = ''
    stem = filename[:-len(suffix)] if suffix else filename
    # Prefix also avoids CON, AUX, NUL, etc.
    leaf = 'f_' + safe_component(stem).rsplit('__', 1)[0] + '__' + hashlib.sha256(filename.encode()).hexdigest()[:12] + suffix
    target = root / ('phone_' + device['id'][:12]) / Path(*folders) / leaf
    if len(str(target)) > 230:
        leaf = 'f_' + safe_component(stem).rsplit('__', 1)[0][:38] + '__' + hashlib.sha256(remote.encode()).hexdigest()[:16] + suffix
        target = root / ('phone_' + device['id'][:12]) / '_long_paths' / leaf
    return target


def progress(message, done=None, total=None):
    with LOCK:
        JOB['message'] = message
        if done is not None:
            JOB['done'] = done
        if total is not None:
            JOB['total'] = total


def start_job(function, *args):
    with LOCK:
        if JOB['running']:
            raise ValueError('عملیات دیگری در حال اجراست؛ تا پایان آن صبر کنید.')
        JOB.update(running=True, message='شروع عملیات', done=0, total=0, errors=[])
    def worker():
        try:
            function(*args)
        except Exception as exc:
            with LOCK:
                JOB['errors'].append(str(exc))
            progress('عملیات متوقف شد؛ پیام خطا را بررسی کنید.')
        finally:
            with LOCK:
                JOB['running'] = False
    threading.Thread(target=worker, daemon=True).start()


def scan(device, folder):
    folder = remote_path(folder)
    progress('در حال یافتن فایل‌ها؛ پوشه‌های بزرگ ممکن است زمان ببرند.')
    # Trailing slash allows find to traverse /sdcard itself, which is an Android symlink.
    command = 'if [ -d ' + shlex.quote(folder) + ' ]; then find ' + shlex.quote(folder + '/') + ' -type f -print0 2>/dev/null || true; else exit 2; fi'
    output = shell(command, device, 300, True)
    paths = sorted(set(remote_path(x.decode('utf-8', 'strict')) for x in output.split(b'\0') if x))
    with db() as c:
        old = c.execute('SELECT remote FROM files WHERE device=?', (device['id'],)).fetchall()
        for row in old:
            if row['remote'].startswith(folder.rstrip('/') + '/'):
                c.execute('UPDATE files SET present=0 WHERE device=? AND remote=?', (device['id'], row['remote']))
        for path in paths:
            c.execute('INSERT OR IGNORE INTO files(device,remote) VALUES(?,?)', (device['id'], path))
            c.execute('UPDATE files SET present=1 WHERE device=? AND remote=?', (device['id'], path))
            # If a deleted name has reappeared, it must be verified anew.
            c.execute('UPDATE files SET deleted_at=NULL, verified_at=NULL WHERE device=? AND remote=? AND deleted_at IS NOT NULL', (device['id'], path))
    progress(f'پویش تمام شد: {len(paths)} فایل پیدا شد. سابقه قبلی حفظ شده است.', len(paths), len(paths))


def rows_for(device, paths):
    if not isinstance(paths, list) or not paths or len(paths) > 5000:
        raise ValueError('بین یک تا ۵۰۰۰ فایل انتخاب کنید.')
    rows = []
    with db() as c:
        for path in dict.fromkeys(paths):
            path = remote_path(path)
            row = c.execute('SELECT * FROM files WHERE device=? AND remote=?', (device['id'], path)).fetchone()
            if not row:
                raise ValueError('فایل در فهرست پویش‌شده نیست.')
            rows.append(dict(row))
    return rows


def add_error(path, exc):
    with LOCK:
        JOB['errors'].append(path + ': ' + str(exc))


def copy_files(device, rows, root):
    root = Path(root).expanduser()
    if not root.is_absolute():
        raise ValueError('مسیر مقصد کامپیوتر باید کامل باشد.')
    root.mkdir(parents=True, exist_ok=True)
    for i, row in enumerate(rows):
        remote = row['remote']
        progress('کپی و بررسی: ' + remote, i, len(rows))
        temp = None
        try:
            before = remote_digest(remote, device)
            if row['local'] and Path(row['local']).is_file() and digest_file(row['local']) == before:
                target = Path(row['local'])
            else:
                target = destination(root, device, remote)
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.exists():
                    target = target.with_name(target.stem + '__' + secrets.token_hex(5) + target.suffix)
                temp = target.with_name(target.name + '.partial-' + secrets.token_hex(5))
                adb(['pull', remote, str(temp)], device, 1800)
                if digest_file(temp) != before:
                    raise ValueError('محتوای فایل کپی‌شده یکسان نیست؛ دوباره تلاش کنید.')
            after = remote_digest(remote, device)
            if before != after:
                raise ValueError('فایل گوشی حین انتقال تغییر کرده؛ تأیید نشد.')
            if temp:
                temp.replace(target)
                temp = None
            with db() as c:
                c.execute('UPDATE files SET local=?,digest=?,size=?,verified_at=?,deleted_at=NULL WHERE device=? AND remote=?',
                          (str(target), after, target.stat().st_size, now(), device['id'], remote))
            event(device['id'], remote, 'verified_copy', str(target))
        except Exception as exc:
            add_error(remote, exc)
            event(device['id'], remote, 'copy_error', detail=str(exc))
        finally:
            if temp and temp.exists():
                temp.unlink()
    progress('انتقال تمام شد. موارد خطادار را بررسی کنید؛ هیچ فایلی از گوشی حذف نشد.', len(rows), len(rows))


def recognize_existing(device, rows, root):
    root = Path(root).expanduser()
    if not root.is_absolute() or not root.is_dir():
        raise ValueError('یک پوشه موجود در کامپیوتر انتخاب کنید.')
    progress('در حال فهرست کردن فایل‌های کامپیوتر برای شناسایی کپی‌های قبلی.')
    sizes = {}
    for directory, folders, names in os.walk(root, followlinks=False):
        folders[:] = [name for name in folders if not (Path(directory) / name).is_symlink()]
        for name in names:
            local = Path(directory) / name
            try:
                if not local.is_symlink() and local.is_file() and '.partial-' not in name:
                    sizes.setdefault(local.stat().st_size, []).append(local)
            except OSError:
                continue
    found = 0
    cache = {}
    for i, row in enumerate(rows):
        remote = row['remote']
        progress('شناسایی نسخه موجود در کامپیوتر: ' + remote, i, len(rows))
        try:
            size = int(shell('stat -c %s -- ' + shlex.quote(remote), device))
            candidates = sizes.get(size, [])
            if not candidates:
                continue
            before = remote_digest(remote, device)
            match = None
            for local in candidates:
                try:
                    st = local.stat()
                    key = (str(local), st.st_size, st.st_mtime_ns)
                    if key not in cache:
                        cache[key] = digest_file(local)
                    if cache[key] == before:
                        match = local
                        break
                except OSError:
                    continue
            if match is None:
                continue
            if remote_digest(remote, device) != before:
                raise ValueError('فایل گوشی حین بررسی تغییر کرد؛ تأیید نشد.')
            with db() as c:
                c.execute('UPDATE files SET local=?,digest=?,size=?,verified_at=?,deleted_at=NULL WHERE device=? AND remote=?',
                          (str(match), before, size, now(), device['id'], remote))
            event(device['id'], remote, 'recognized_existing_copy', str(match))
            found += 1
        except Exception as exc:
            add_error(remote, exc)
    progress(f'بررسی کپی‌های قبلی تمام شد؛ {found} نسخه یکسان شناسایی شد. چیزی حذف یا کپی نشد.', len(rows), len(rows))


def delete_files(device, rows):
    for i, row in enumerate(rows):
        remote = row['remote']
        progress('بررسی دوباره پیش از حذف: ' + remote, i, len(rows))
        try:
            if not row['verified_at'] or not row['local'] or not row['digest'] or row['deleted_at']:
                raise ValueError('نسخه تأییدشده برای حذف وجود ندارد.')
            if not Path(row['local']).is_file() or digest_file(row['local']) != row['digest']:
                raise ValueError('نسخه کامپیوتر حذف یا تغییر کرده است؛ حذف گوشی انجام نشد.')
            if remote_digest(remote, device) != row['digest']:
                raise ValueError('فایل گوشی تغییر کرده است؛ ابتدا دوباره کپی کنید.')
            # Explicit per-file deletion only. No directory removal, wildcard, or recursion.
            shell('rm -- ' + shlex.quote(remote), device)
            with db() as c:
                c.execute('UPDATE files SET deleted_at=? WHERE device=? AND remote=?', (now(), device['id'], remote))
            event(device['id'], remote, 'deleted_from_phone', row['local'])
        except Exception as exc:
            add_error(remote, exc)
            event(device['id'], remote, 'delete_error', detail=str(exc))
    progress('حذف انتخابی تمام شد؛ فایل‌های کامپیوتر حفظ شدند. موارد خطادار را بررسی کنید.', len(rows), len(rows))


def install_tools():
    if os.name != 'nt':
        raise ValueError('دریافت خودکار ابزار فقط برای ویندوز آماده شده است.')
    progress('در حال دریافت ابزار رسمی گوگل؛ ممکن است چند دقیقه طول بکشد.')
    archive = DATA / 'platform-tools.zip'
    try:
        with urllib.request.urlopen('https://dl.google.com/android/repository/platform-tools-latest-windows.zip', timeout=60) as source, open(archive, 'wb') as target:
            shutil.copyfileobj(source, target)
        with zipfile.ZipFile(archive) as z:
            for member in z.infolist():
                path = (BASE / member.filename).resolve()
                if not path.is_relative_to((BASE / 'platform-tools').resolve()):
                    raise ValueError('مسیر نامعتبر در بسته ابزار گوگل.')
            z.extractall(BASE)
        progress('ابزار گوگل آماده است؛ اکنون اتصال را بررسی کنید.')
    finally:
        archive.unlink(missing_ok=True)


def export_history():
    path = DATA / 'transfer-history.csv'
    import csv
    with db() as c, open(path, 'w', encoding='utf-8-sig', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['time', 'device_hash', 'phone_path', 'action', 'computer_path', 'detail'])
        for row in c.execute('SELECT time,device,remote,action,local,detail FROM events ORDER BY id'):
            # Avoid formula execution if opened in a spreadsheet.
            writer.writerow(["'" + v if isinstance(v, str) and v.startswith(('=', '+', '-', '@')) else v for v in row])
    return path


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def valid_origin(self):
        expected = f'127.0.0.1:{self.server.server_port}'
        return self.headers.get('Host') == expected and self.headers.get('Origin', 'http://' + expected) == 'http://' + expected

    def respond(self, value, code=200):
        body = json.dumps(value, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self.valid_origin():
            return self.respond({'error': 'درخواست مجاز نیست.'}, 403)
        path = urlparse(self.path).path
        if path in ['/', '/app.js', '/style.css']:
            name = {'/': 'index.html', '/app.js': 'app.js', '/style.css': 'style.css'}[path]
            body = (BASE / 'static' / name).read_bytes()
            if path == '/':
                body = body.replace(b'__TOKEN__', TOKEN.encode())
            self.send_response(200)
            self.send_header('Content-Type', {'/': 'text/html; charset=utf-8', '/app.js': 'text/javascript; charset=utf-8', '/style.css': 'text/css; charset=utf-8'}[path])
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            self.end_headers()
            self.wfile.write(body)
        else:
            self.respond({'error': 'یافت نشد.'}, 404)

    def do_POST(self):
        if not self.valid_origin() or self.headers.get('X-App-Token') != TOKEN:
            return self.respond({'error': 'درخواست مجاز نیست.'}, 403)
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if length > 2_000_000:
                raise ValueError('درخواست بیش از حد بزرگ است.')
            data = json.loads(self.rfile.read(length) or b'{}')
            route = urlparse(self.path).path
            if route == '/api/status':
                with LOCK:
                    result = dict(JOB, errors=JOB['errors'][:100], error_count=len(JOB['errors']))
                result.update(tools=ADB.is_file(), connected=bool(DEVICE), model=DEVICE['model'] if DEVICE else '', default_destination=str(Path.home() / 'PhoneArchive'))
                return self.respond(result)
            if route == '/api/files':
                device = require_device()
                folder = remote_path(data.get('folder', '/sdcard'))
                with db() as c:
                    rows = [dict(r) for r in c.execute('SELECT remote,local,size,verified_at,deleted_at,present FROM files WHERE device=? ORDER BY remote', (device['id'],))
                            if r['remote'].startswith(folder.rstrip('/') + '/')]
                return self.respond({'files': rows})
            with LOCK:
                if JOB['running']:
                    raise ValueError('تا پایان عملیات فعلی صبر کنید.')
            if route == '/api/connect':
                return self.respond(connect())
            if route == '/api/tools':
                start_job(install_tools)
            elif route == '/api/scan':
                start_job(scan, require_device(), remote_path(data.get('folder', '/sdcard')))
            elif route in ['/api/copy', '/api/delete', '/api/recognize']:
                device = require_device()
                rows = rows_for(device, data.get('paths'))
                if route == '/api/copy':
                    start_job(copy_files, device, rows, data.get('destination', ''))
                elif route == '/api/recognize':
                    start_job(recognize_existing, device, rows, data.get('destination', ''))
                else:
                    if data.get('confirmation') != 'DELETE':
                        raise ValueError('برای تأیید حذف باید DELETE وارد کنید.')
                    start_job(delete_files, device, rows)
            elif route == '/api/export':
                return self.respond({'path': str(export_history())})
            elif route == '/api/open-destination':
                dest = Path(data.get('destination', '')).expanduser()
                if os.name != 'nt' or not dest.is_absolute() or not dest.is_dir():
                    raise ValueError('پوشه مقصد معتبر ویندوز انتخاب کنید.')
                os.startfile(str(dest))
            elif route == '/api/pick-destination':
                # Separate process keeps Tk on its own main thread.
                code = 'import tkinter as t;from tkinter import filedialog;r=t.Tk();r.withdraw();r.attributes("-topmost",True);print(filedialog.askdirectory(title="Select archive folder"));r.destroy()'
                import sys
                p = subprocess.run([sys.executable, '-c', code], capture_output=True, text=True, encoding='utf-8', env=dict(os.environ, PYTHONIOENCODING='utf-8'), timeout=180)
                if p.returncode:
                    raise ValueError('انتخاب‌گر پوشه باز نشد؛ مسیر مقصد را دستی وارد کنید.')
                return self.respond({'path': p.stdout.strip()})
            else:
                raise ValueError('عملیات نامعتبر است.')
            self.respond({'ok': True})
        except Exception as exc:
            self.respond({'error': str(exc)}, 400)


def main():
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    address = f'http://127.0.0.1:{server.server_port}/'
    print('Phone archive is running locally:', address, flush=True)
    print('Keep this window open while copying. Ctrl+C closes the program.', flush=True)
    webbrowser.open(address)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
