package io.github.coherenthearts.phonecheck;
import java.util.ArrayList;
import java.util.List;
/** Conservative evidence-based hints; missing measurements never become healthy results. */
public final class ReportRules {
    private ReportRules() {}
    public static List<String> hints(long totalStorage, long freeStorage, long totalRam, long availableRam,
                                     boolean lowMemory, double batteryC, int thermal, boolean connected, boolean validated) {
        List<String> hints=new ArrayList<>();
        if(totalStorage>0 && freeStorage>=0 && ((double)freeStorage/totalStorage<0.10))
            hints.add("فضای آزاد کمتر از ۱۰ درصد است؛ فایل‌های بزرگ را فقط بعد از تهیه و بررسی نسخه پشتیبان مدیریت کن.");
        if(lowMemory) hints.add("اندروید در زمان این نمونه وضعیت کمبود حافظه را گزارش کرده است. یک نمونه برای تشخیص علت کافی نیست.");
        else if(totalRam>0 && availableRam>=0 && (double)availableRam/totalRam<0.10)
            hints.add("حافظه در دسترس کم است؛ این به تنهایی خرابی یا نیاز به برنامه پاک‌کننده RAM را ثابت نمی‌کند.");
        if(Double.isFinite(batteryC)&&batteryC>=40)
            hints.add("دمای باتری در این نمونه بالا است؛ بار سنگین را متوقف کن، گوشی را از گرما دور نگه دار و پس از خنک شدن دوباره اندازه بگیر. این دما، دمای پردازنده نیست.");
        if(thermal>=2) hints.add("اندروید محدودسازی حرارتی را گزارش کرده؛ داغی می‌تواند روی سرعت اثر بگذارد.");
        if(!connected) hints.add("شبکه فعال برای این برنامه دیده نشد. این موضوع به تنهایی مشکل سخت‌افزاری اینترنت نیست.");
        else if(!validated) hints.add("شبکه متصل است اما دسترسی اینترنت توسط اندروید تأیید نشده؛ ورود به شبکه یا وضعیت اتصال را بررسی کن.");
        if(hints.isEmpty()) hints.add("در سنجه‌های قابل دسترسی این نمونه، هشدار آستانه‌ای دیده نشد؛ این به معنی سالم بودن کامل گوشی نیست.");
        return hints;
    }
    public static boolean validModel(String model) { return model!=null && model.matches("[A-Za-z0-9._-]{1,120}"); }
    public static String thermalName(int code) {
        String[] names={"بدون محدودسازی گزارش‌شده","خفیف","متوسط","شدید","بحرانی","اضطراری","خاموشی"};
        return code>=0&&code<names.length?names[code]:"در دسترس نیست";
    }
}
