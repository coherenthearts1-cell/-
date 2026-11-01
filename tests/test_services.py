import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import urllib.error
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import phone_services as s

class GeminiTests(unittest.TestCase):
    def test_key_only_in_header_not_url_or_payload(self):
        seen = []
        class Opener:
            def open(self, request, timeout):
                seen.append(request)
                return io.BytesIO(b'{"candidates":[{"content":{"parts":[{"text":"answer"}]},"finishReason":"STOP"}]}')
        with patch.object(s.urllib.request, 'build_opener', return_value=Opener()):
            self.assertEqual(s.analyze_report('secret-key', 'gemini-test', 'report', 'prompt', 'slow'), 'answer')
        request = seen[0]
        self.assertNotIn('secret-key', request.full_url)
        self.assertNotIn(b'secret-key', request.data)
        self.assertEqual(request.get_header('X-goog-api-key'), 'secret-key')
        self.assertEqual(request.full_url, 'https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent')
        body = json.loads(request.data)
        self.assertNotIn('tools', body)
        self.assertEqual(body['systemInstruction']['parts'][0]['text'], 'prompt')

    def test_http_error_does_not_expose_provider_body_or_key(self):
        class Opener:
            def open(self, *args, **kwargs):
                raise urllib.error.HTTPError('https://example.com?secret-key', 429, 'secret-key', {}, io.BytesIO(b'secret-key'))
        with patch.object(s.urllib.request, 'build_opener', return_value=Opener()):
            with self.assertRaises(ValueError) as error:
                s.google_request('models', 'secret-key')
        self.assertIn('429', str(error.exception))
        self.assertNotIn('secret-key', str(error.exception))

    def test_redirects_do_not_forward_credentials(self):
        self.assertIsNone(s.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://evil.example'))

    def test_model_path_injection_rejected_before_network(self):
        with patch.object(s, 'google_request') as network:
            with self.assertRaises(ValueError):
                s.analyze_report('secret', '../../other?key=bad', 'report', 'prompt', '')
            network.assert_not_called()

    def test_empty_report_rejected_before_network(self):
        with patch.object(s, 'google_request') as network:
            with self.assertRaises(ValueError):
                s.analyze_report('secret', 'gemini-test', '', 'prompt', '')
            network.assert_not_called()

    def test_missing_text_is_not_reported_as_success(self):
        with patch.object(s, 'google_request', return_value={'promptFeedback': {'blockReason':'SAFETY'}}):
            with self.assertRaises(ValueError):
                s.analyze_report('secret', 'gemini-test', 'report', 'prompt', '')

    def test_thoughts_are_excluded_and_truncation_disclosed(self):
        response = {'candidates':[{'content':{'parts':[{'thought':True,'text':'private thought'},{'text':'actual analysis'}]},'finishReason':'MAX_TOKENS'}]}
        with patch.object(s, 'google_request', return_value=response):
            text = s.analyze_report('secret', 'gemini-test', 'report', 'prompt', '')
        self.assertIn('actual analysis', text)
        self.assertNotIn('private thought', text)
        self.assertIn('ناقص', text)

    def test_model_pagination_filters_unsupported_models(self):
        pages = [{'models':[{'name':'models/gemini-one','supportedGenerationMethods':['generateContent']}],'nextPageToken':'next token'},
                 {'models':[{'name':'models/embedding','supportedGenerationMethods':['embedContent']},{'name':'models/gemini-two','supportedGenerationMethods':['generateContent']}]}]
        with patch.object(s, 'google_request', side_effect=pages) as network:
            models = s.list_models('secret')
        self.assertEqual([m['name'] for m in models], ['gemini-one','gemini-two'])
        self.assertIn('pageToken=next+token', network.call_args_list[1].args[0])

class DiagnosticTests(unittest.TestCase):
    def test_failed_section_does_not_abort_and_serial_is_removed(self):
        def shell(command, device, timeout):
            if command == 'uptime': raise ValueError('offline')
            return 'test-serial contact@example.com technical-data'
        text = s.collect_report(shell, {'serial':'test-serial'}, 'time', lambda *a:None, lambda _:None)
        self.assertIn('UNAVAILABLE', text)
        self.assertIn('technical-data', text)
        self.assertNotIn('test-serial', text)
        self.assertNotIn('contact@example.com', text)
        self.assertEqual(text.count('===== '), len(s.DIAGNOSTICS))

    def test_collection_uses_read_only_allowlist(self):
        for _,command in s.DIAGNOSTICS:
            self.assertTrue(command.startswith(('getprop ', 'uptime', 'df ', 'cat /proc/', 'dumpsys ', 'settings get ', 'pm list ')),command)
            self.assertNotIn('logcat',command)
            self.assertNotIn('bugreport',command)

    def test_section_truncation_disclosed(self):
        text = s.collect_report(lambda *a,**k:'x'*100000, {}, 'time', lambda *a:None, lambda _:None)
        self.assertIn('TRUNCATED',text)
        self.assertLess(len(text),310000)

if __name__ == '__main__': unittest.main()
