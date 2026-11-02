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
    size=adb('shell','wm','size')
    width,height=map(int,re.findall(r'(\d+)x(\d+)',size)[-1])
    for _ in range(45):
        n=find(text)
        if n is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
            # UIAutomator may expose partially clipped buttons behind system navigation.
            # Scroll a complete button into the safe viewport before tapping.
            if y2-y1>=80 and y1>=80 and y2<=height-140 and n.get('enabled')=='true':
                print('Click',text,(x1,y1,x2,y2),flush=True)
                adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5);return
            if y1<80:
                adb('shell','input','swipe',str(width//2),str(height//3),str(width//2),str(height*2//3),'300');time.sleep(.4);continue
        adb('shell','input','swipe',str(width//2),str(height*4//5),str(width//2),str(height//4),'300');time.sleep(.4)
    raise AssertionError('Control not reachable: '+text)
def main():
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

try:
    main()
except Exception:
    print('CURRENT UI:', adb('shell','uiautomator','dump','/sdcard/failure.xml'))
    print(adb('shell','cat','/sdcard/failure.xml'))
    print('APP FILES:', adb('shell','run-as',APP,'ls','-la','files'))
    print('CRASH LOG:', adb('logcat','-d','-b','crash','-t','100'))
    raise
