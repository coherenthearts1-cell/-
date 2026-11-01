"""Browser smoke test with fake ADB/Gemini responses; no phone or real API key."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
with tempfile.TemporaryDirectory() as folder:
    os.environ['PHONE_ARCHIVE_DATA'] = folder
    spec = importlib.util.spec_from_file_location('browser_app', root / 'app.py')
    app = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(app)
    server = app.ThreadingHTTPServer(('127.0.0.1', 0), app.Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    workspace = dict(app.EXTRA, default_prompt='پرامپت پیشنهادی آزمایشی')
    sent = []
    def intercept(route):
        path = route.request.url.rsplit('/', 1)[-1]
        body = route.request.post_data_json or {}
        sent.append((path, body))
        if path == 'status':
            result = dict(running=False, message='آماده', done=0,total=0,errors=[],error_count=0,tools=True,connected=True,model='A54 test',default_destination=folder)
        elif path == 'workspace': result = workspace
        elif path == 'files': result = {'files': []}
        elif path == 'diagnose':
            workspace.update(report='گزارش آزمایشی گوشی',report_id='r1',report_path='report.txt')
            result = {'ok':True}
        elif path == 'models':
            workspace.update(models=[{'name':'gemini-test','label':'Test model'}],models_id='m1')
            result = {'ok':True}
        elif path == 'analyze':
            assert body['consent'] is True
            assert body['report'] == 'گزارش آزمایشی گوشی'
            assert body['key'] == 'test-key-not-real'
            workspace.update(analysis='تحلیل آزمایشی؛ فقط پیشنهاد',analysis_id='a1',analysis_path='analysis.txt')
            result = {'ok':True}
        elif path == 'apps': result = {'apps':['com.example.fixture']}
        else: result = {'ok':True}
        route.fulfill(status=200,content_type='application/json',body=json.dumps(result,ensure_ascii=False))
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            page = browser.new_page()
            errors = []
            page.on('pageerror',lambda e:errors.append(str(e)))
            page.route('**/api/**',intercept)
            page.goto(f'http://127.0.0.1:{server.server_port}/')
            page.wait_for_function("document.getElementById('ai-prompt').value.length > 0")
            page.get_by_role('button',name='ساخت گزارش عملکرد گوشی',exact=True).focus()
            page.keyboard.press('Enter')
            page.wait_for_function("document.getElementById('report-text').value.length > 0")
            page.locator('#gemini-key').fill('test-key-not-real')
            page.locator('#get-models').click()
            page.wait_for_selector('#model-choice option[value="gemini-test"]',state='attached')
            page.locator('#model-choice').select_option('gemini-test')
            page.locator('#ai-consent').check()
            page.locator('#analyze').click()
            page.wait_for_function("document.getElementById('analysis-text').value.length > 0")
            assert page.locator('#analysis-text').input_value() == 'تحلیل آزمایشی؛ فقط پیشنهاد'
            assert not any(path in ('push','manage-app','delete') for path,_ in sent)
            assert 'test-key-not-real' not in page.evaluate('JSON.stringify(localStorage)')
            page.locator('#get-apps').click()
            page.wait_for_selector('#app-choice option[value="com.example.fixture"]',state='attached')
            page.locator('#uninstall').click()
            assert page.locator('#manage-dialog').is_visible()
            assert 'UNINSTALL com.example.fixture' in page.locator('#manage-description').inner_text()
            page.keyboard.press('Escape')
            assert not page.locator('#manage-dialog').is_visible()
            assert not errors, errors
            browser.close()
            print('Browser flow passed: keyboard report, model selection, consent, analysis, and uninstall cancellation.')
    finally:
        server.shutdown()
        server.server_close()
