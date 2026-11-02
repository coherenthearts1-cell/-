package io.github.coherenthearts.phonecheck;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import java.text.SimpleDateFormat;
import java.util.*;

public final class Diagnostics {
    private Diagnostics() {}
    public static class Sample {
        public long totalStorage=-1,freeStorage=-1,totalRam=-1,availableRam=-1;
        public double batteryC=Double.NaN;
        public int thermal=-1,percent=-1;
        public boolean lowMemory,connected,validated;
        public String batteryStatus="در دسترس نیست",power="در دسترس نیست",network="در دسترس نیست";
        public String warning="";
    }
    public static Sample sample(Context c) {
        Sample s=new Sample();
        try {StatFs fs=new StatFs(Environment.getDataDirectory().getPath());s.totalStorage=fs.getTotalBytes();s.freeStorage=fs.getAvailableBytes();} catch(Exception e){s.warning+="فضای ذخیره‌سازی در دسترس نبود. ";}
        try {ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();am.getMemoryInfo(m);s.totalRam=m.totalMem;s.availableRam=m.availMem;s.lowMemory=m.lowMemory;} catch(Exception e){s.warning+="حافظه در دسترس نبود. ";}
        try {
            Intent b=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(b!=null){int level=b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=b.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
                if(level>=0&&scale>0)s.percent=(int)Math.round(level*100.0/scale);
                int temp=b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE);
                if(temp!=Integer.MIN_VALUE)s.batteryC=temp/10.0;
                int status=b.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
                s.batteryStatus=switch(status){case BatteryManager.BATTERY_STATUS_CHARGING->"در حال شارژ";case BatteryManager.BATTERY_STATUS_DISCHARGING->"در حال تخلیه";case BatteryManager.BATTERY_STATUS_FULL->"پر";case BatteryManager.BATTERY_STATUS_NOT_CHARGING->"شارژ نمی‌شود";default->"نامشخص";};
            }
        }catch(Exception e){s.warning+="باتری در دسترس نبود. ";}
        try {PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE);s.power=pm.isPowerSaveMode()?"روشن":"خاموش";if(Build.VERSION.SDK_INT>=29)s.thermal=pm.getCurrentThermalStatus();}catch(Exception e){s.warning+="وضعیت حرارتی در دسترس نبود. ";}
        try {
            ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);Network n=cm.getActiveNetwork();NetworkCapabilities nc=cm.getNetworkCapabilities(n);
            if(nc!=null){s.connected=true;s.validated=nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                s.network=(nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"وای‌فای ":"")+(nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"داده همراه ":"")+(nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"اترنت ":"")+(nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?"VPN ":"");
                s.network+="؛ اینترنت تأییدشده: "+s.validated+"؛ محدودیت حجمی: "+cm.isActiveNetworkMetered();
            }else s.network="شبکه فعال دیده نشد";
        }catch(Exception e){s.warning+="وضعیت شبکه در دسترس نبود. ";}
        return s;
    }
    public static String mib(long n){return n<0?"در دسترس نیست":String.format(Locale.US,"%.1f MiB",n/1048576.0);}
    public static String temperature(double n){return Double.isFinite(n)?String.format(Locale.US,"%.1f °C",n):"در دسترس نیست";}
    public static boolean usageAllowed(Context c){
        AppOpsManager ops=(AppOpsManager)c.getSystemService(Context.APP_OPS_SERVICE);
        return ops!=null&&ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;
    }
    public static String report(Context c, boolean includeApps, boolean includeUsage){
        Sample s=sample(c);StringBuilder out=new StringBuilder();
        out.append("گزارش مستقل اندروید، نسخه ۵ آزمایشی\nزمان: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date()));
        out.append("\nاین یک نمونه لحظه‌ای است؛ نتیجه قطعی خرابی یا سلامت کامل نیست.\n\nمشخصات عمومی\nسازنده: ").append(Build.MANUFACTURER).append("\nمدل: ").append(Build.MODEL).append("\nاندروید: ").append(Build.VERSION.RELEASE).append("؛ API ").append(Build.VERSION.SDK_INT).append("\nوصله امنیتی: ").append(Build.VERSION.SECURITY_PATCH).append("\nتعداد هسته‌های منطقی قابل مشاهده: ").append(Runtime.getRuntime().availableProcessors()).append("\nزمان از روشن شدن: ").append(SystemClock.elapsedRealtime()/60000).append(" دقیقه\n");
        out.append("\nفضای پارتیشن داده\nکل: ").append(mib(s.totalStorage)).append("\nدر دسترس: ").append(mib(s.freeStorage));
        out.append("\nاین حجم، اندازه فایل‌های شخصی قابل حذف نیست؛ فضای سیستم، برنامه‌ها و سهمیه‌ها هم اثر دارند.\n\nحافظه\nکل RAM: ").append(mib(s.totalRam)).append("\nدر دسترس اندروید: ").append(mib(s.availableRam)).append("\nپرچم کمبود حافظه اندروید: ").append(s.lowMemory).append("\nRAM کم به تنهایی نشانه مشکل نیست؛ کش می‌تواند مفید باشد.\n");
        out.append("\nباتری و گرما\nشارژ: ").append(s.percent>=0?s.percent+" درصد":"در دسترس نیست").append("\nوضعیت: ").append(s.batteryStatus).append("\nدمای باتری: ").append(temperature(s.batteryC)).append("\nمحدودسازی حرارتی سیستم: ").append(ReportRules.thermalName(s.thermal)).append("\nذخیره انرژی: ").append(s.power).append("\nاین‌ها سلامت واقعی ظرفیت باتری، مصرف دقیق هر برنامه یا دمای CPU را اندازه نمی‌گیرند.\n");
        out.append("\nشبکه\n").append(s.network).append("\nاین بخش تست سرعت، قدرت آنتن، DNS یا کیفیت اینترنت همه برنامه‌ها نیست.\n");
        out.append("\nرابط و دسترس‌پذیری\nاندازه قلم سیستم: ").append(c.getResources().getConfiguration().fontScale).append("\n");
        try {android.view.accessibility.AccessibilityManager a=(android.view.accessibility.AccessibilityManager)c.getSystemService(Context.ACCESSIBILITY_SERVICE);out.append("دسترس‌پذیری فعال: ").append(a.isEnabled()).append("\nکاوش با لمس فعال: ").append(a.isTouchExplorationEnabled()).append("\nفعال بودن صفحه‌خوان به معنی علت کندی نیست؛ آن را برای آزمایش اولیه غیرفعال نکنید.\n");}catch(Exception ignored){}
        if(includeApps)appendApps(c,out);else out.append("\nفهرست برنامه‌ها: با انتخاب کاربر وارد گزارش نشد.\n");
        if(includeUsage)appendUsage(c,out);else out.append("\nآمار استفاده: با انتخاب کاربر وارد گزارش نشد.\n");
        out.append("\nهشدارهای مبتنی بر سنجه\n");for(String hint:ReportRules.hints(s.totalStorage,s.freeStorage,s.totalRam,s.availableRam,s.lowMemory,s.batteryC,s.thermal,s.connected,s.validated))out.append("• ").append(hint).append("\n");
        if(!s.warning.isEmpty())out.append("بخش‌های ناقص: ").append(s.warning).append("\n");
        out.append("\nمحدودیت‌های صریح\nبدون روت/ADB: مصرف دقیق CPU و باتری هر برنامه، logcat سراسری، علت دقیق بسته‌شدن برنامه‌های دیگر، سلامت سخت‌افزار، ظرفیت واقعی باتری، اسکن بدافزار و فایل‌های خصوصی برنامه‌ها در دسترس نیستند. هیچ پیام، مخاطب، عکس، شماره سریال، IMEI یا نشانی IP جمع‌آوری نشده است. برنامه‌های پنهان/بدون لانچر ممکن است در فهرست نباشند.\n");
        return out.toString();
    }
    private static void appendApps(Context c,StringBuilder out){
        out.append("\nبرنامه‌های دارای آیکون قابل مشاهده؛ نه فهرست کامل همه بسته‌ها\n");
        try {PackageManager pm=c.getPackageManager();List<ResolveInfo> list=pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);TreeMap<String,String> apps=new TreeMap<>();for(ResolveInfo r:list)apps.put(r.activityInfo.packageName,r.loadLabel(pm).toString());int count=0;for(Map.Entry<String,String> item:apps.entrySet()){if(count++>=200){out.append("[فهرست پس از ۲۰۰ مورد محدود شد]\n");break;}out.append(item.getValue()).append(" — ").append(item.getKey()).append("\n");}}catch(Exception e){out.append("دریافت فهرست ممکن نشد.\n");}
    }
    private static void appendUsage(Context c,StringBuilder out){
        out.append("\nمدت استفاده در پیش‌زمینه در بازه تقریبی ۲۴ ساعت؛ نه مصرف CPU یا باتری\n");
        if(!usageAllowed(c)){out.append("دسترسی اختیاری آمار استفاده داده نشده است.\n");return;}
        try {UsageStatsManager um=(UsageStatsManager)c.getSystemService(Context.USAGE_STATS_SERVICE);long end=System.currentTimeMillis();Map<String,UsageStats> map=um.queryAndAggregateUsageStats(end-86400000L,end);List<UsageStats> all=new ArrayList<>(map.values());all.sort((a,b)->Long.compare(b.getTotalTimeInForeground(),a.getTotalTimeInForeground()));int count=0;for(UsageStats u:all){if(u.getTotalTimeInForeground()<=0)continue;if(count++>=20)break;out.append(u.getPackageName()).append(": ").append(u.getTotalTimeInForeground()/60000).append(" دقیقه\n");}out.append("بازه‌های تجمیعی اندروید ممکن است از مرز دقیق ۲۴ ساعت فراتر بروند.\n");}catch(Exception e){out.append("دریافت آمار ممکن نشد.\n");}
    }
}
