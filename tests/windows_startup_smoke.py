"""Start the actual .cmd launcher on a disposable Windows runner; no ADB/device/API calls."""
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from playwright.sync_api import sync_playwright, expect

if os.name != 'nt':
    raise SystemExit('This smoke test requires Windows.')
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'test-output'
OUTPUT.mkdir(exist_ok=True)
summary = {'os': platform.platform(), 'windows_version': platform.version(),
           'python': platform.python_version(), 'phone': 'not connected',
           'gemini': 'not called', 'checks': []}

def check(name, condition):
    if not condition:
        raise AssertionError(name)
    summary['checks'].append(name)

with tempfile.TemporaryDirectory(prefix='phone-startup-') as temporary:
    distribution = Path(temporary) / 'Phone Manager تست با فاصله'
    distribution.mkdir()
    for name in ['app.py', 'phone_services.py', 'Start-Windows.cmd']:
        shutil.copy2(ROOT / name, distribution / name)
    shutil.copytree(ROOT / 'static', distribution / 'static')
    env = dict(os.environ, PHONE_ARCHIVE_DATA=str(Path(temporary) / 'isolated-data'), PYTHONUNBUFFERED='1')
    logfile = OUTPUT / 'windows-launcher.log'
    process = None
    try:
        with logfile.open('wb') as log:
            command = 'cmd.exe /d /s /c ""' + str(distribution / 'Start-Windows.cmd') + '""'
            process = subprocess.Popen(command, cwd=distribution, env=env, stdout=log,
                                       stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
            address = None
            for _ in range(90):
                text = logfile.read_bytes().decode('utf-8', 'replace')
                match = re.search(r'http://127\.0\.0\.1:\d+/', text)
                if match:
                    address = match.group(0)
                    try:
                        html = urllib.request.urlopen(address, timeout=2).read().decode('utf-8')
                        break
                    except (OSError, urllib.error.URLError):
                        pass
                if process.poll() is not None:
                    raise AssertionError('Launcher exited before serving: ' + text[-1000:])
                time.sleep(1)
            else:
                raise AssertionError('Launcher did not start HTTP within 90 seconds: ' + text[-1000:])
            check('actual CMD launcher starts from a Unicode path containing spaces', bool(address))
            token = re.search(r'name="app-token" content="([^"]+)"', html).group(1)
            def request(route, data=None, authorized=True, origin=None):
                headers = {'Content-Type': 'application/json'}
                if authorized: headers['X-App-Token'] = token
                if origin: headers['Origin'] = origin
                req = urllib.request.Request(address+'api/'+route,
                    data=json.dumps(data or {}).encode(), headers=headers)
                try:
                    with urllib.request.urlopen(req, timeout=10) as response:
                        return response.status, json.load(response)
                except urllib.error.HTTPError as error:
                    return error.code, json.load(error)
            code, status = request('status')
            check('real HTTP status endpoint responds without phone', code==200 and status['connected'] is False)
            code, workspace = request('workspace')
            check('default analysis prompt loads through the real backend', code==200 and bool(workspace['default_prompt']))
            code, _ = request('delete', authorized=False)
            check('unauthorized destructive request returns 403', code==403)
            code, _ = request('delete', origin='https://untrusted.example')
            check('foreign-origin request returns 403', code==403)
            code, result = request('connect')
            check('missing ADB is a handled error, not a crash', code==400 and bool(result.get('error')))
            code, result = request('analyze', {'key':'fixture-not-real','consent':False})
            check('analysis is rejected without consent and without calling Google', code==400)
            errors = []
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(headless=True)
                page = browser.new_page(viewport={'width':1280,'height':900})
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto(address)
                expect(page.locator('#ai-prompt')).not_to_have_value('')
                page.locator('#connect').focus()
                page.keyboard.press('Enter')
                expect(page.locator('#error')).not_to_have_text('')
                page.screenshot(path=str(OUTPUT/'windows-desktop.png'),full_page=True)
                check('real interface works by keyboard and shows missing-device error', not errors)
                browser.close()
            check('test never creates ADB tools or accesses an attached phone', not (distribution/'platform-tools').exists())
    finally:
        if process and process.poll() is None:
            subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        summary['passed'] = len(summary['checks'])
        (OUTPUT/'windows-startup-result.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False,indent=2))
