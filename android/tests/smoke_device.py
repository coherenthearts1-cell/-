"""Black-box emulator smoke test using ADB, not device privileges in the app itself."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
APP='io.github.coherenthearts.phonecheck'
def adb(*args):
    return subprocess.check_output(['adb',*args],text=True,timeout=45)
def nodes():
    adb('shell','uiautomator','dump','/sdcard/ui.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/ui.xml')).iter('node')
def find(text):
    return next((n for n in nodes() if n.get('text')==text),None)
def click(text):
    for _ in range(20):
        n=find(text)
        if n is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
            if x2>x1 and y2>y1:
                adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
        adb('shell','input','swipe','500','1500','500','450','300');time.sleep(.3)
    raise AssertionError('Control not reachable: '+text)
adb('shell','am','force-stop',APP)
adb('shell','am','start','-W','-n',APP+'/.MainActivity')
time.sleep(2)
click('ساخت گزارش جدید')
for _ in range(15):
    text=adb('shell','run-as',APP,'cat','files/last_report.txt') if subprocess.run(['adb','shell','run-as',APP,'ls','files/last_report.txt'],capture_output=True).returncode==0 else ''
    if 'گزارش مستقل اندروید' in text:break
    time.sleep(1)
else:raise AssertionError('No generated report')
assert 'محدودیت‌های صریح' in text
assert 'IMEI' in text and 'در دسترس نیستند' in text
assert 'RAM' in text
# No setting change without native confirmation.
click('تنظیمات فضای ذخیره‌سازی')
assert find('باز کردن تنظیمات با اجازه تو') is not None
click('انصراف')
# Check absence of fatal app crashes after actions.
crashes=adb('logcat','-d','-b','crash','-t','100')
assert APP not in crashes,crashes
print('Emulator smoke passed: launch, report generation, and settings cancellation.')
