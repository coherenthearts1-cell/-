package io.github.coherenthearts.phonecheck;
import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    private AppState state;private ScrollView scroll;private LinearLayout column;
    private TextView status,error,reportNote,answerNote,usageStatus;
    private EditText report,key,model,prompt,symptoms,answer;
    private CheckBox includeApps,includeUsage,consent;
    private Spinner durations,models;private Button stop;
    private final List<Button> operationButtons=new ArrayList<>();
    private String renderedReport=null,renderedAnswer=null;private List<GeminiClient.Model> renderedModels=null;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);state=(AppState)getApplication();
        scroll=new ScrollView(this);scroll.setFillViewport(true);column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);column.setPadding(dp(18),dp(18),dp(18),dp(32));scroll.addView(column);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=30){scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());column.setPadding(dp(18)+bars.left,dp(12)+bars.top,dp(18)+bars.right,dp(24)+bars.bottom);return insets;});}
        heading("عیب‌یاب مستقل گوشی — نسخه ۵ آزمایشی",true);
        text("بدون کامپیوتر و روت. گزارش فقط سنجه‌های مجاز اندروید را می‌خواند؛ محدودیت‌ها در گزارش نوشته می‌شوند. این برنامه فایل یا برنامه‌ای را حذف نمی‌کند و به جای تو تنظیمات را تغییر نمی‌دهد.");
        status=text("آماده");status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);error=text("");error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        LinearLayout navigation=new LinearLayout(this);navigation.setOrientation(LinearLayout.VERTICAL);column.addView(navigation);
        TextView reportHeading=heading("۱. بررسی و گزارش",false);
        includeApps=new CheckBox(this);includeApps.setText("فهرست برنامه‌های دارای آیکون را هم در گزارش بگذار");column.addView(includeApps);
        includeUsage=new CheckBox(this);includeUsage.setText("در صورت داشتن اجازه، آمار مدت استفاده برنامه‌ها را هم اضافه کن");column.addView(includeUsage);
        usageStatus=text("");
        button("دادن یا لغو اجازه اختیاری آمار استفاده",()->confirmSettings("این اجازه مدت استفاده و نام برخی برنامه‌ها را قابل خواندن می‌کند. برای اصل گزارش لازم نیست و می‌توانی بعداً لغوش کنی.",new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)),false);
        button("ساخت گزارش جدید",()->{if(state.busy)return;hideKeyboard();state.makeReport(includeApps.isChecked(),includeUsage.isChecked());},true);
        text("پایش فقط در همین صفحه اجرا می‌شود. برای تداوم پایش، برنامه را باز نگه دار؛ قفل شدن صفحه، رفتن به برنامه دیگر یا چرخاندن صفحه ممکن است پایش را متوقف کند. این پایش، مصرف CPU برنامه‌های دیگر را اندازه نمی‌گیرد.");
        durations=spinner("مدت پایش",new String[]{"۱ دقیقه","۳ دقیقه","۵ دقیقه"});
        button("شروع پایش کوتاه‌مدت",()->{hideKeyboard();int[] seconds={60,180,300};state.monitor(seconds[durations.getSelectedItemPosition()],includeApps.isChecked(),includeUsage.isChecked());},true);
        stop=button("توقف پایش و ذخیره گزارش جزئی",()->{state.stopMonitor=true;status.setText("در حال پایان پایش و ذخیره نمونه‌ها");},false);
        report=edit("گزارش قابل خواندن و ویرایش؛ متن همین کادر برای تحلیل ارسال می‌شود",12,300000,false);reportNote=text("");
        button("کپی گزارش",()->copy("گزارش",report.getText().toString()),false);
        button("ذخیره گزارش به فایل متنی",()->export(report.getText().toString(),"Phone-Report.txt"),false);
        TextView aiHeading=heading("۲. تحلیل اختیاری با Gemini",false);
        text("بدون اینترنت هم گزارش ساخته می‌شود. فقط با تأیید تو، متن گزارش، توضیح مشکل و پرامپت به گوگل ارسال می‌شود. نام برنامه‌ها ممکن است اطلاعات شخصی باشد؛ قبل از ارسال متن را بررسی کن. هزینه، سهمیه و نگهداری داده تابع سرویس گوگل است.");
        symptoms=edit("توضیح مشکل",3,8000,false);symptoms.setText("گوشی من در کل سیستم کند شده است؛ فقط با شواهد گزارش علت‌های محتمل را بررسی کن.");
        key=edit("کلید شخصی Google AI Studio؛ برای ما نفرست",1,512,true);key.setSaveEnabled(false);key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        text("کلید در فایل یا تنظیمات ذخیره نمی‌شود. با بازسازی صفحه یا بستن برنامه ممکن است لازم باشد دوباره واردش کنی. پاک کردن کادر، درخواست در حال اجرا را لغو نمی‌کند.");
        button("پاک کردن کلید از کادر",()->{key.setText("");status.setText("کلید از کادر پاک شد؛ درخواست در حال اجرا لغو نمی‌شود.");},false);
        button("دریافت فهرست مدل‌ها از گوگل",()->{hideKeyboard();state.getModels(key.getText().toString());},true);
        models=spinner("مدل‌های دریافت‌شده",new String[]{"ابتدا فهرست مدل‌ها را دریافت کن"});
        model=edit("نام مدل؛ قابل ورود دستی",1,120,false);model.setTextDirection(View.TEXT_DIRECTION_LTR);
        models.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int position,long id){List<GeminiClient.Model> list=state.models;if(position>0&&position<=list.size())model.setText(list.get(position-1).name);}});
        prompt=edit("پرامپت پیشنهادی تحلیل؛ قابل ویرایش",8,12000,false);prompt.setText(state.prompt());
        button("بازگرداندن پرامپت پیشنهادی",()->prompt.setText(state.prompt()),false);
        button("ذخیره پرامپت به فایل",()->export(prompt.getText().toString(),"Phone-Prompt-fa.txt"),false);
        consent=new CheckBox(this);consent.setText("گزارش، توضیح و پرامپت بالا را بررسی کرده‌ام و با ارسال به Gemini گوگل موافقم");column.addView(consent);
        button("ارسال گزارش و دریافت تحلیل فارسی",()->{
            if(!consent.isChecked()){showError("قبل از ارسال، گزارش را بررسی و گزینه رضایت را علامت بزن.");return;}
            if(report.getText().toString().trim().isEmpty()||!ReportRules.validModel(model.getText().toString().trim())){showError("ابتدا گزارش بساز و یک نام مدل معتبر انتخاب کن.");return;}
            hideKeyboard();state.analyze(key.getText().toString(),model.getText().toString().trim(),report.getText().toString(),prompt.getText().toString(),symptoms.getText().toString());
        },true);
        answer=edit("پاسخ مدل؛ فقط پیشنهاد، بدون اجرای دستور",12,100000,false);answer.setKeyListener(null);answer.setTextIsSelectable(true);answerNote=text("");
        button("کپی پاسخ",()->copy("پاسخ Gemini",answer.getText().toString()),false);button("ذخیره پاسخ به فایل",()->export(answer.getText().toString(),"Gemini-Analysis.txt"),false);
        TextView settingsHeading=heading("۳. اقدام با اجازه تو",false);
        text("این دکمه‌ها با تأیید تو فقط صفحه رسمی تنظیمات اندروید را باز می‌کنند. تغییر، پاک‌کردن داده، حذف یا بازنشانی را خودت در همان صفحه بررسی و تأیید می‌کنی. برنامه دسترسی تغییر خودکار، روت یا سرویس دسترس‌پذیری نمی‌گیرد.");
        settingsButton("تنظیمات فضای ذخیره‌سازی",Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        settingsButton("تنظیمات باتری",Settings.ACTION_BATTERY_SAVER_SETTINGS);
        settingsButton("تنظیمات وای‌فای",Settings.ACTION_WIFI_SETTINGS);
        settingsButton("تنظیمات شبکه و اینترنت",Settings.ACTION_WIRELESS_SETTINGS);
        settingsButton("تنظیمات برنامه‌ها",Settings.ACTION_APPLICATION_SETTINGS);
        settingsButton("به‌روزرسانی سیستم؛ اگر سازنده پشتیبانی کند", "android.settings.SYSTEM_UPDATE_SETTINGS");
        button("تنظیمات همین برنامه؛ لغو مجوز یا مدیریت داده",()->confirmSettings("صفحه اطلاعات همین برنامه باز می‌شود. پاک کردن داده، گزارش‌ها و پاسخ ذخیره‌شده را هم پاک می‌کند.",new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))),false);
        heading("حریم خصوصی و محدودیت‌ها",false);
        text("گزارش و پاسخ آخر در حافظه خصوصی این برنامه می‌مانند؛ برای انتقال به گوشی دیگر از ذخیره فایل استفاده کن. شماره سریال، IMEI، پیام، عکس، مخاطب و موقعیت خوانده نمی‌شود. گزارش محلی بدون درخواست تو به اینترنت نمی‌رود. آمار برنامه‌ها اختیاری است. گزارش، مصرف دقیق هر برنامه، اسکن بدافزار یا سلامت کامل سخت‌افزار را تضمین نمی‌کند.");
        button("حذف گزارش و پاسخ ذخیره‌شده همین برنامه",()->new AlertDialog.Builder(this).setTitle("حذف داده تشخیصی محلی؟").setMessage("فقط گزارش و پاسخ داخل این برنامه پاک می‌شوند؛ فایل‌هایی که بیرون ذخیره کرده‌ای باقی می‌مانند.").setNegativeButton("انصراف",null).setPositiveButton("حذف",(d,w)->{if(state.busy){showError("تا پایان عملیات صبر کن.");return;}deleteFile("last_report.txt");deleteFile("last_analysis.txt");state.report="";state.analysis="";state.reportPath="";state.analysisPath="";refresh();}).show(),false);
        addNavigation(navigation,"رفتن به گزارش",reportHeading);addNavigation(navigation,"رفتن به تحلیل Gemini",aiHeading);addNavigation(navigation,"رفتن به تنظیمات",settingsHeading);
        TextWatcher invalidate=new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){consent.setChecked(false);}public void afterTextChanged(Editable e){}};
        report.addTextChangedListener(invalidate);prompt.addTextChangedListener(invalidate);symptoms.addTextChangedListener(invalidate);
        refresh();
    }
    @Override protected void onResume(){super.onResume();state.listener=this::refresh;refresh();}
    @Override protected void onPause(){state.listener=null;super.onPause();}
    @Override protected void onStop(){if(state.monitoring){state.stopMonitor=true;}super.onStop();}
    private void refresh(){
        if(status==null)return;
        if(!status.getText().toString().equals(state.status))status.setText(state.status);
        if(!error.getText().toString().equals(state.error))error.setText(state.error);
        for(Button b:operationButtons)b.setEnabled(!state.busy);
        stop.setEnabled(state.monitoring);if(state.monitoring)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(!Objects.equals(renderedReport,state.report)){renderedReport=state.report;report.setText(state.report);}
        if(!Objects.equals(renderedAnswer,state.analysis)){renderedAnswer=state.analysis;answer.setText(state.analysis);}
        reportNote.setText(state.reportPath);answerNote.setText(state.analysisPath);usageStatus.setText("اجازه آمار استفاده: "+(Diagnostics.usageAllowed(this)?"داده شده":"داده نشده؛ گزارش پایه همچنان کار می‌کند"));
        if(renderedModels!=state.models){renderedModels=state.models;List<String> choices=new ArrayList<>();choices.add("مدل را انتخاب کن");for(GeminiClient.Model m:state.models)choices.add(m.toString());models.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));}
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value){TextView t=new TextView(this);t.setText(value);t.setTextSize(16);t.setTextColor(0xff15243b);t.setPadding(0,dp(8),0,dp(8));column.addView(t);return t;}
    private TextView heading(String title,boolean main){TextView h=text(title);h.setTextSize(main?24:21);h.setTypeface(null,android.graphics.Typeface.BOLD);h.setFocusableInTouchMode(true);if(Build.VERSION.SDK_INT>=28)h.setAccessibilityHeading(true);return h;}
    private Button button(String title,Runnable action,boolean operation){Button b=new Button(this);b.setText(title);b.setMinHeight(dp(52));b.setAllCaps(false);column.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->{try{action.run();}catch(Exception e){showError("این عملیات در این گوشی انجام نشد؛ تنظیمات یا ورودی را بررسی کن.");}});if(operation)operationButtons.add(b);return b;}
    private EditText edit(String label,int lines,int max,boolean password){TextView l=text(label);EditText e=new EditText(this);e.setId(View.generateViewId());e.setSaveEnabled(false);e.setTextSize(16);e.setMinHeight(dp(52));e.setGravity(Gravity.TOP|Gravity.START);e.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});if(lines==1)e.setSingleLine(true);else{e.setMinLines(Math.min(lines,4));e.setMaxLines(lines);e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);}if(password)e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);l.setLabelFor(e.getId());column.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
    private Spinner spinner(String label,String[] values){TextView l=text(label);Spinner s=new Spinner(this);s.setId(View.generateViewId());l.setLabelFor(s.getId());s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));column.addView(s,new LinearLayout.LayoutParams(-1,dp(56)));return s;}
    private void addNavigation(LinearLayout parent,String label,TextView target){Button b=new Button(this);b.setText(label);parent.addView(b);b.setOnClickListener(v->{scroll.smoothScrollTo(0,target.getTop());target.requestFocus();target.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null);});}
    private void showError(String text){error.setText(text);}
    private void hideKeyboard(){android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(getCurrentFocus()!=null)im.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(),0);}
    private void copy(String label,String value){if(value.trim().isEmpty()){showError("متنی برای کپی وجود ندارد.");return;}((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label,value));Toast.makeText(this,"کپی شد",Toast.LENGTH_SHORT).show();}
    private void export(String text,String name){if(text.trim().isEmpty()){showError("متنی برای ذخیره وجود ندارد.");return;}state.pendingExport=text;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(i,100);}
    @Override public void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=100)return;
        if(result!=RESULT_OK||data==null||data.getData()==null){state.pendingExport="";return;}
        String text=state.pendingExport;state.pendingExport="";
        if(text.isEmpty()){showError("متن صادرشده دیگر در حافظه نیست؛ دوباره دکمه ذخیره فایل را بزن.");return;}
        Uri uri=data.getData();
        boolean started=state.start("در حال ذخیره فایل انتخابی",()->{
            try(OutputStream out=state.getContentResolver().openOutputStream(uri,"wt")){
                if(out==null)throw new java.io.IOException("ذخیره فایل انجام نشد.");
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            state.status="فایل در مقصد انتخابی ذخیره شد.";
        });
        if(!started)showError("تا پایان عملیات جاری صبر کن و دوباره ذخیره فایل را بزن.");
    }
    private void settingsButton(String title,String action){button(title,()->confirmSettings("فقط صفحه رسمی تنظیمات باز می‌شود. تغییر را خودت در اندروید بررسی و تأیید کن.",new Intent(action)),false);}
    private void confirmSettings(String message,Intent intent){new AlertDialog.Builder(this).setTitle("باز کردن تنظیمات با اجازه تو").setMessage(message).setNegativeButton("انصراف",null).setPositiveButton("باز کردن تنظیمات",(d,w)->{try{startActivity(intent);}catch(ActivityNotFoundException e){showError("این صفحه روی گوشی تو وجود ندارد؛ از تنظیمات اصلی گوشی وارد شو.");}}).show();}
}
