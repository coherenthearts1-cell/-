"""Black-box emulator smoke test using ADB, not device privileges in the app itself."""
import re
from pathlib import Path
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
    size=adb('shell','wm','size')
    width,height=map(int,re.findall(r'(\d+)x(\d+)',size)[-1])
    for _ in range(45):
        n=find(text)
        if n is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
            # UIAutomator may expose partially clipped buttons behind system navigation.
            # Scroll a complete button into the safe viewport before tapping.
            if y2-y1>=80 and y1>=80 and (y1+y2)//2<height-100 and n.get('enabled')=='true':
                print('Click',text,(x1,y1,x2,y2),flush=True)
                adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5);return
            if y1<80:
                adb('shell','input','swipe',str(width//2),str(height//3),str(width//2),str(height*2//3),'300');time.sleep(.4);continue
        adb('shell','input','swipe',str(width//2),str(height*4//5),str(width//2),str(height//4),'300');time.sleep(.4)
    raise AssertionError('Control not reachable: '+text)
def capture(name):
    out=Path('android/test-output');out.mkdir(exist_ok=True)
    adb('shell','screencap','-p','/sdcard/screen.png')
    adb('pull','/sdcard/screen.png',str(out/name))
def fill_private_key(value):
    for _ in range(12):
        n=next((n for n in nodes() if n.get('class')=='android.widget.EditText' and n.get('password')=='true'),None)
        if n is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
            if y2-y1>=80 and y1>=80:
                adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
                adb('shell','input','text',value)
                adb('shell','input','keyevent','4')
                return
        adb('shell','input','swipe','500','1450','500','500','300')
    raise AssertionError('Password field not reachable')
def main():
    adb('shell','am','force-stop',APP)
    adb('shell','am','start','-W','-n',APP+'/.MainActivity')
    time.sleep(2)
    capture('home.png')
    click('شروع بررسی گوشی')
    for _ in range(15):
        text=adb('shell','run-as',APP,'cat','files/last_report.txt') if subprocess.run(['adb','shell','run-as',APP,'ls','files/last_report.txt'],capture_output=True).returncode==0 else ''
        if 'گزارش مستقل اندروید' in text:break
        time.sleep(1)
    else:raise AssertionError('No generated report')
    assert 'محدودیت‌های صریح' in text
    assert 'IMEI' in text and 'در دسترس نیستند' in text
    assert 'RAM' in text
    assert find('گزارش و پیشنهادها') is not None
    capture('results.png')
    # No setting change without native confirmation.
    click('تنظیمات فضای ذخیره‌سازی')
    assert find('باز کردن تنظیمات؟') is not None
    click('انصراف')
    click('تنظیمات')
    capture('settings.png')
    fixture='fixture-key-not-real'
    fill_private_key(fixture)
    click('ذخیره امن کلید')
    for _ in range(12):
        if subprocess.run(['adb','shell','run-as',APP,'ls','no_backup/gemini.enc'],capture_output=True).returncode==0:break
        time.sleep(.5)
    encrypted=adb('shell','run-as',APP,'cat','no_backup/gemini.enc')
    assert fixture not in encrypted and 'ciphertext' in encrypted
    adb('shell','am','force-stop',APP)
    adb('shell','am','start','-W','-n',APP+'/.MainActivity')
    time.sleep(1)
    click('تنظیمات')
    assert any('کلید شخصی ذخیره شده است' in n.get('text','') for n in nodes())
    # Check absence of fatal app crashes after actions.
    crashes=adb('logcat','-d','-b','crash','-t','100')
    assert APP not in crashes,crashes
    print('Emulator smoke passed: navigation, report, settings cancellation, encrypted-key save and restart.')

try:
    main()
except Exception:
    print('CURRENT UI:', adb('shell','uiautomator','dump','/sdcard/failure.xml'))
    print(adb('shell','cat','/sdcard/failure.xml'))
    print('APP FILES:', adb('shell','run-as',APP,'ls','-la','files'))
    print('CRASH LOG:', adb('logcat','-d','-b','crash','-t','100'))
    raise
