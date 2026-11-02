package io.github.coherenthearts.phonecheck;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class AppState extends Application {
    public volatile boolean busy=false,monitoring=false,stopMonitor=false;
    public volatile String status="آماده",error="",report="",analysis="",reportPath="",analysisPath="";
    public volatile List<GeminiClient.Model> models=Collections.emptyList();
    public volatile Runnable listener;
    public volatile String pendingExport="";
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override public void onCreate(){super.onCreate();report=load("last_report.txt");analysis=load("last_analysis.txt");}
    private String load(String name){try(InputStream in=openFileInput(name)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1500000)return "";out.write(b,0,n);}return out.toString(StandardCharsets.UTF_8.name());}catch(Exception e){return "";}}
    public String prompt(){try(InputStream in=getAssets().open("prompt.fa.txt")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}catch(Exception e){return "گزارش مستقل اندروید را فارسی و فقط براساس شواهد تحلیل کن؛ هیچ اقدامی اجرا نکن.";}}
    private synchronized void save(String name,String text)throws IOException{
        File temp=new File(getFilesDir(),name+".tmp"),target=new File(getFilesDir(),name);
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!temp.renameTo(target))throw new IOException("ذخیره گزارش روی گوشی انجام نشد.");
    }
    public void notifyUi(){main.post(()->{Runnable callback=listener;if(callback!=null)callback.run();});}
    public interface Task {void run()throws Exception;}
    public synchronized boolean start(String message,Task task){
        if(busy)return false;busy=true;status=message;error="";notifyUi();
        executor.execute(()->{try{task.run();}catch(Exception e){error="عملیات کامل نشد؛ اینترنت، دسترسی و ورودی‌ها را بررسی کن. "+safeMessage(e);status="عملیات کامل نشد";}finally{busy=false;monitoring=false;notifyUi();}});return true;
    }
    private String safeMessage(Exception e){
        // Never include raw network exceptions, URLs, headers, keys, or report text.
        String text=e.getMessage();if(text!=null&&(text.startsWith("خطای Gemini")||text.startsWith("نام مدل")||text.startsWith("گزارش ")||text.startsWith("پرامپت ")||text.startsWith("مدل پاسخ")||text.startsWith("پاسخ ")||text.startsWith("ذخیره ")||text.startsWith("کلید API")))return text;
        return "ممکن است ارتباط یا دسترسی موقتاً برقرار نباشد. تلاش مجدد خودکار انجام نمی‌شود.";
    }
    public void makeReport(boolean apps,boolean usage){start("در حال جمع‌آوری سنجه‌های مجاز گوشی",()->{
        String text=Diagnostics.report(this,apps,usage);save("last_report.txt",text);report=text;reportPath="گزارش در حافظه خصوصی برنامه ذخیره شد؛ برای نگهداری بیرون برنامه آن را صادر کن.";analysis="";save("last_analysis.txt","");status="گزارش آماده شد؛ برای خواندن به بخش گزارش برو.";
    });}
    public void getModels(String key){start("در حال دریافت مدل‌ها از گوگل؛ گزارشی ارسال نمی‌شود",()->{models=GeminiClient.models(key);status="فهرست مدل‌ها دریافت شد؛ انتخاب کن.";});}
    public void analyze(String key,String model,String text,String prompt,String symptoms){start("گزارش با تأیید تو به Gemini فرستاده می‌شود؛ در حال انتظار پاسخ",()->{
        analysis="";save("last_analysis.txt","");String answer=GeminiClient.analyze(key,model,text,prompt,symptoms);save("last_analysis.txt",answer);analysis=answer;analysisPath="پاسخ در حافظه خصوصی ذخیره شد؛ قابل کپی یا صدور است.";status="پاسخ آماده شد؛ پیشنهادها خودکار اجرا نمی‌شوند.";
    });}
    public synchronized void monitor(int seconds,boolean apps,boolean usage){
        if(busy)return;monitoring=true;stopMonitor=false;
        start("پایش پیش‌زمینه شروع شد؛ این برنامه را باز نگه دار",()->{
            StringBuilder samples=new StringBuilder("\nپایش در پیش‌زمینه؛ نمونه‌های همین گوشی، نه مصرف دقیق برنامه‌ها\n");long start=SystemClock.elapsedRealtime();int count=0;
            while(!stopMonitor&&SystemClock.elapsedRealtime()-start<=seconds*1000L){
                Diagnostics.Sample s=Diagnostics.sample(this);long elapsed=(SystemClock.elapsedRealtime()-start)/1000;
                samples.append(elapsed).append(" ثانیه؛ RAM در دسترس: ").append(Diagnostics.mib(s.availableRam)).append("؛ باتری: ").append(s.percent>=0?s.percent+"%":"نامشخص").append("؛ دمای باتری: ").append(Diagnostics.temperature(s.batteryC)).append("؛ حرارت: ").append(ReportRules.thermalName(s.thermal)).append("\n");
                count++;status="پایش: "+elapsed+" ثانیه؛ "+count+" نمونه. برای پایان زودتر دکمه توقف را بزن.";notifyUi();
                for(int i=0;i<5&&!stopMonitor;i++)Thread.sleep(1000);
            }
            samples.append("\nوضعیت پایان: ").append(stopMonitor?"متوقف‌شده به درخواست کاربر یا خروج از صفحه":"مدت انتخابی تمام شد").append("\nافت شارژ کوتاه‌مدت برای تشخیص سلامت ظرفیت باتری معتبر نیست.\n");
            String text=Diagnostics.report(this,apps,usage)+samples;save("last_report.txt",text);report=text;reportPath="گزارش پایش ذخیره شد.";analysis="";save("last_analysis.txt","");status="پایش تمام و گزارش ذخیره شد.";
        });
    }
}
