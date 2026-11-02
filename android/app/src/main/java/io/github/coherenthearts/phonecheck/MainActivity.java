package io.github.coherenthearts.phonecheck;
import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Three short screens, explicit actions, native TalkBack controls. */
public class MainActivity extends Activity {
 private static final int HOME=0,RESULTS=1,SETTINGS=2,EXPORT=100,IMPORT_KEY=101;
 private static final int INK=0xff15243b,BLUE=0xff174aa3,MUTED=0xff455772,BG=0xfff3f6fb;
 private AppState state;private LinearLayout root,column;private ScrollView scroll;private TextView status,error,screenTitle,reportPreview,analysisView;
 private boolean observedKeyExists;
 private interface UiAction {void run()throws Exception;}
 private final List<Button> operations=new ArrayList<>();private final Button[] tabs=new Button[3];
 private Button stop;private Spinner modelSpinner;private EditText keyInput,modelInput;private int page=HOME;
 private String observedReport="",observedAnalysis="",draftReport="",draftSymptoms="گوشی من در کل سیستم کند شده است.",customPrompt="",notice="";
 private List<GeminiClient.Model> observedModels;private boolean awaitingReport=false;
 private android.content.SharedPreferences prefs;
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);state=(AppState)getApplication();prefs=getSharedPreferences("preferences",MODE_PRIVATE);if(saved!=null)page=saved.getInt("page",HOME);
  observedKeyExists=KeyVault.exists(this);draftReport=state.report;observedReport=state.report;observedAnalysis=state.analysis;customPrompt=state.prompt();
  root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setBackgroundColor(BG);setContentView(root);
  TextView brand=new TextView(this);brand.setText("عیب‌یاب گوشی");brand.setTextSize(23);brand.setTypeface(null,Typeface.BOLD);brand.setTextColor(INK);brand.setPadding(dp(20),dp(14),dp(20),dp(8));root.addView(brand);headingFlag(brand);
  status=new TextView(this);status.setTextSize(14);status.setTextColor(MUTED);status.setPadding(dp(20),0,dp(20),dp(8));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(status);
  error=new TextView(this);error.setTextColor(0xff8e2020);error.setPadding(dp(20),0,dp(20),dp(6));error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);root.addView(error);error.setVisibility(View.GONE);
  scroll=new ScrollView(this);scroll.setFillViewport(true);column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(20),dp(8),dp(20),dp(24));scroll.addView(column);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout navigation=new LinearLayout(this);navigation.setBackgroundColor(Color.WHITE);root.addView(navigation);
  String[] names={"بررسی گوشی","گزارش و پیشنهادها","تنظیمات"};for(int i=0;i<3;i++){final int target=i;Button b=new Button(this);b.setText(names[i]);b.setAllCaps(false);b.setTextSize(14);b.setMinHeight(dp(64));navigation.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->navigate(target));tabs[i]=b;}
  if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());root.setPadding(b.left,b.top,b.right,b.bottom);return insets;});
  if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
  showPage();
 }
 @Override protected void onResume(){super.onResume();state.listener=this::refresh;refresh();}
 @Override protected void onPause(){state.listener=null;super.onPause();}
 @Override protected void onStop(){if(state.monitoring)state.stopMonitor=true;super.onStop();}
 @Override public void onSaveInstanceState(Bundle out){out.putInt("page",page);super.onSaveInstanceState(out);}
 // Legacy Android dispatches back through this method; API 33+ uses the registered dispatcher.
 @android.annotation.SuppressLint("GestureBackNavigation")
 @Override public void onBackPressed(){if(Build.VERSION.SDK_INT<33)handleBack();else super.onBackPressed();}
 private void handleBack(){if(page!=HOME)navigate(HOME);else finish();}
 private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
 private void headingFlag(TextView t){if(Build.VERSION.SDK_INT>=28)t.setAccessibilityHeading(true);}
 private GradientDrawable background(int fill,int border){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(14));if(border!=0)d.setStroke(dp(1),border);return d;}
 private void navigate(int next){page=next;notice="";hideKeyboard();showPage();scroll.scrollTo(0,0);screenTitle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null);}
 private void showPage(){
  column.removeAllViews();operations.clear();stop=null;reportPreview=null;analysisView=null;keyInput=null;modelInput=null;modelSpinner=null;observedModels=null;
  for(int i=0;i<3;i++){tabs[i].setSelected(i==page);tabs[i].setTextColor(i==page?Color.WHITE:INK);tabs[i].setBackground(background(i==page?BLUE:Color.WHITE,0));}
  screenTitle=heading(page==HOME?"از اینجا شروع کن":page==RESULTS?"گزارش و پیشنهادها":"تنظیمات برنامه");
  if(page==HOME)home();else if(page==RESULTS)results();else settings();refresh();
 }
 private TextView text(String value){TextView t=new TextView(this);t.setText(value);t.setTextColor(INK);t.setTextSize(16);t.setLineSpacing(dp(3),1);t.setPadding(0,dp(7),0,dp(7));column.addView(t);return t;}
 private TextView heading(String value){TextView t=text(value);t.setTextSize(22);t.setTypeface(null,Typeface.BOLD);headingFlag(t);return t;}
 private void subheading(String value){TextView t=text(value);t.setTextSize(18);t.setTypeface(null,Typeface.BOLD);headingFlag(t);}
 private Button button(String title,UiAction action,boolean primary,boolean operation){
  Button b=new Button(this);b.setText(title);b.setTextSize(16);b.setAllCaps(false);b.setMinHeight(dp(56));b.setTextColor(primary?Color.WHITE:BLUE);b.setBackground(background(primary?BLUE:Color.WHITE,primary?0:0xffb8c8e1));
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(7),0,dp(7));column.addView(b,lp);
  b.setOnClickListener(v->{notice="";try{action.run();}catch(Exception e){showError("این کار انجام نشد؛ ورودی یا دسترسی را بررسی کن.");}});if(operation)operations.add(b);return b;
 }
 private String gib(long n){return n<0?"در دسترس نیست":String.format(Locale.forLanguageTag("fa"),"%.1f گیگابایت",n/1073741824.0);}
 private void metric(String title,String value){TextView t=text(title+"\n"+value);t.setPadding(dp(16),dp(14),dp(16),dp(14));t.setBackground(background(Color.WHITE,0xffdbe3ef));LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)t.getLayoutParams();lp.setMargins(0,dp(5),0,dp(5));}
 private void home(){
  text("با یک بررسی کوتاه شروع کن. چیزی حذف نمی‌شود و تنظیمات تغییر نمی‌کنند.");
  button("شروع بررسی گوشی",()->{if(state.busy)return;awaitingReport=true;hideKeyboard();state.makeReport(prefs.getBoolean("apps",false),prefs.getBoolean("usage",false));},true,true);
  Diagnostics.Sample s=Diagnostics.sample(this);
  metric("فضای در دسترس",gib(s.freeStorage));metric("حافظه در دسترس",gib(s.availableRam));
  metric("باتری و دما",(s.percent>=0?s.percent+" درصد":"شارژ نامشخص")+" · "+Diagnostics.temperature(s.batteryC));
  metric("وضعیت حرارتی",ReportRules.thermalName(s.thermal));
  button("دیدن آخرین گزارش",()->navigate(RESULTS),false,false);
  button("پایش چنددقیقه‌ای",this::monitorDialog,false,true);
  stop=button("توقف پایش و ذخیره نتیجه",()->{state.stopMonitor=true;state.status="پایش در حال پایان است";refresh();},false,false);
  text("عددها مربوط به همین لحظه‌اند. بررسی کامل‌تر و محدودیت‌های اندازه‌گیری داخل گزارش می‌آیند.");
 }
 private void results(){
  if(draftReport.isEmpty()&&state.report.isEmpty()){text("هنوز گزارشی نداری. ابتدا یک بررسی انجام بده.");button("شروع بررسی گوشی",()->{awaitingReport=true;state.makeReport(prefs.getBoolean("apps",false),prefs.getBoolean("usage",false));},true,true);return;}
  subheading("خلاصه بررسی");
  reportPreview=text(localSummary(draftReport));reportPreview.setTextIsSelectable(true);
  button("خواندن یا ویرایش گزارش کامل",this::reportDialog,false,false);
  button("ذخیره گزارش به فایل",()->export(draftReport,"Phone-Report.txt"),false,false);
  subheading("تحلیل دقیق‌تر با هوش مصنوعی");
  text(KeyVault.exists(this)?"کلید شخصی روی این گوشی ذخیره شده است. گزارش فقط با تأیید تو به گوگل می‌رود.":"برای تحلیل، کلید شخصی را یک بار در تنظیمات اضافه کن. گزارش محلی بدون کلید هم کار می‌کند.");
  button("تحلیل گزارش با Gemini",this::analysisDialog,true,true);
  if(!state.analysis.isEmpty()){subheading("پیشنهادهای Gemini");analysisView=text(state.analysis);analysisView.setTextIsSelectable(true);button("کپی پاسخ",()->copy("تحلیل",state.analysis),false,false);button("ذخیره پاسخ",()->export(state.analysis,"Gemini-Analysis.txt"),false,false);}
  subheading("اقدام با انتخاب تو");text("فقط صفحه تنظیمات باز می‌شود؛ تصمیم و تأیید نهایی با توست.");
  settingsButton("تنظیمات فضای ذخیره‌سازی",Settings.ACTION_INTERNAL_STORAGE_SETTINGS);settingsButton("تنظیمات باتری",Settings.ACTION_BATTERY_SAVER_SETTINGS);settingsButton("تنظیمات شبکه",Settings.ACTION_WIRELESS_SETTINGS);settingsButton("تنظیمات برنامه‌ها",Settings.ACTION_APPLICATION_SETTINGS);
 }
 private String localSummary(String report){int at=report.indexOf("هشدارهای مبتنی بر سنجه");if(at>=0){String result=report.substring(at);int end=result.indexOf("محدودیت‌های صریح");if(end>=0)result=result.substring(0,end);return result.trim();}return "گزارش آماده است. برای خواندن جزئیات، گزارش کامل را باز کن.";}
 private EditText field(LinearLayout parent,String label,String value,int lines,boolean secret){
  TextView l=new TextView(this);l.setText(label);l.setTextColor(INK);l.setTextSize(16);l.setPadding(0,dp(10),0,dp(5));parent.addView(l);
  EditText e=new EditText(this);e.setId(View.generateViewId());e.setSaveEnabled(false);e.setTextSize(16);e.setMinHeight(dp(52));e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(background(Color.WHITE,0xff91a6c9));e.setTextColor(INK);e.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
  if(lines==1)e.setSingleLine(true);else{e.setMinLines(Math.min(4,lines));e.setMaxLines(lines);e.setGravity(Gravity.TOP|Gravity.START);e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);}
  if(secret){e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);}
  e.setText(value);l.setLabelFor(e.getId());parent.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;
 }
 private void settings(){
  subheading("اتصال به هوش مصنوعی");
  text(KeyVault.exists(this)?"کلید شخصی ذخیره شده است؛ در کادر نمایش داده نمی‌شود.":"هنوز کلیدی ذخیره نشده است.");
  button("ورود کلید شخصی از فایل",()->{if(state.busy)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");startActivityForResult(i,IMPORT_KEY);},true,true);
  keyInput=field(column,"یا کلید جدید را وارد کن","",1,true);keyInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});
  button("ذخیره امن کلید",()->{String value=keyInput.getText().toString();if(value.trim().isEmpty()){showError("ابتدا کلید را وارد کن یا از فایل بیاور.");return;}state.start("در حال ذخیره رمز‌شده کلید",()->{KeyVault.save(state,value);state.status="کلید رمز‌شده روی همین گوشی ذخیره شد.";});keyInput.setText("");},false,true);
  button("بررسی اتصال و دریافت مدل‌ها",()->{String value=KeyVault.load(this);if(value.isEmpty()){showError("اول کلید شخصی را ذخیره کن.");return;}state.getModels(value);},false,true);
  TextView label=text("مدل‌های قابل انتخاب");modelSpinner=new Spinner(this);modelSpinner.setId(View.generateViewId());label.setLabelFor(modelSpinner.getId());column.addView(modelSpinner,new LinearLayout.LayoutParams(-1,dp(56)));
  modelInput=field(column,"نام مدل انتخابی",prefs.getString("model",""),1,false);modelInput.setTextDirection(View.TEXT_DIRECTION_LTR);
  modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int n,long id){if(n>0&&n<=state.models.size()){String name=state.models.get(n-1).name;modelInput.setText(name);prefs.edit().putString("model",name).apply();}}});
  button("ذخیره مدل",()->{String name=modelInput.getText().toString().trim().replaceFirst("^models/","");if(!ReportRules.validModel(name)){showError("یک نام مدل معتبر انتخاب کن.");return;}prefs.edit().putString("model",name).apply();notice="مدل انتخابی ذخیره شد.";refresh();},false,false);
  button("حذف کلید ذخیره‌شده",()->new AlertDialog.Builder(this).setTitle("کلید از این گوشی حذف شود؟").setMessage("گزارش‌ها باقی می‌مانند. درخواست در حال اجرا لغو نمی‌شود.").setNegativeButton("انصراف",null).setPositiveButton("حذف کلید",(d,w)->{try{KeyVault.delete(this);notice="کلید ذخیره‌شده حذف شد.";showPage();}catch(Exception e){showError("حذف کلید انجام نشد.");}}).show(),false,false);
  subheading("جزئیات گزارش");
  CheckBox apps=new CheckBox(this);apps.setText("نام برنامه‌های دارای آیکون را هم بررسی کن");apps.setChecked(prefs.getBoolean("apps",false));apps.setOnCheckedChangeListener((b,on)->prefs.edit().putBoolean("apps",on).apply());column.addView(apps);
  CheckBox usage=new CheckBox(this);usage.setText("آمار مدت استفاده را با اجازه جداگانه اضافه کن");usage.setChecked(prefs.getBoolean("usage",false));usage.setOnCheckedChangeListener((b,on)->prefs.edit().putBoolean("usage",on).apply());column.addView(usage);
  text("اجازه آمار استفاده: "+(Diagnostics.usageAllowed(this)?"داده شده":"داده نشده؛ بررسی پایه کار می‌کند"));
  button("مدیریت اجازه آمار استفاده",()->confirmSettings("این اجازه نام و مدت استفاده بعضی برنامه‌ها را در دسترس می‌گذارد. برای بررسی پایه لازم نیست.",new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)),false,false);
  button("پرامپت پیشرفته تحلیل",this::promptDialog,false,false);
  button("راهنما و حریم خصوصی",()->new AlertDialog.Builder(this).setTitle("درباره این نسخه").setMessage("نسخه ۵.۱ آزمایشی. سنجه‌های مجاز اندروید بررسی می‌شوند؛ مصرف دقیق CPU هر برنامه، سلامت کامل باتری و اسکن بدافزار در دسترس نیستند. پیام، عکس، مخاطب و شناسه سخت‌افزاری خوانده نمی‌شود. کلید با Android Keystore رمز می‌شود و فقط روی همین نصب قابل خواندن است. گزارش و پاسخ آخر خصوصی روی گوشی می‌مانند. فقط با تأیید، متن گزارش و پرامپت به Gemini می‌رود. پیشنهادها اجرا نمی‌شوند.").setPositiveButton("بستن",null).show(),false,false);
 }
 private void reportDialog(){LinearLayout box=dialogBox();EditText editor=field(box,"گزارش کامل؛ قابل ویرایش",draftReport,14,false);editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300000)});new AlertDialog.Builder(this).setTitle("گزارش کامل").setView(wrap(box)).setNegativeButton("انصراف",null).setNeutralButton("کپی",(d,w)->copy("گزارش",editor.getText().toString())).setPositiveButton("نگهداری ویرایش",(d,w)->{draftReport=editor.getText().toString();notice="ویرایش برای تحلیل بعدی نگه داشته شد.";showPage();}).show();}
 private void promptDialog(){LinearLayout box=dialogBox();EditText editor=field(box,"دستور تحلیل",customPrompt,12,false);editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12000)});new AlertDialog.Builder(this).setTitle("پرامپت پیشرفته").setView(wrap(box)).setNegativeButton("انصراف",null).setNeutralButton("پیش‌فرض",(d,w)->{customPrompt=state.prompt();notice="پرامپت پیش‌فرض بازگردانی شد.";refresh();}).setPositiveButton("نگهداری",(d,w)->{customPrompt=editor.getText().toString();notice="پرامپت این نشست تغییر کرد.";refresh();}).show();}
 private void analysisDialog()throws Exception{
  if(draftReport.trim().isEmpty()){showError("اول بررسی گوشی را انجام بده.");return;}
  String savedKey=KeyVault.load(this);String selected=prefs.getString("model","");
  if(savedKey.isEmpty()||!ReportRules.validModel(selected)){navigate(SETTINGS);notice="یک بار کلید شخصی و مدل را در این بخش تنظیم کن.";refresh();return;}
  LinearLayout box=dialogBox();TextView explain=new TextView(this);explain.setText("متن گزارش فعلی، توضیح تو و پرامپت به Gemini گوگل ارسال می‌شود. ممکن است هزینه داشته باشد. پاسخ فقط پیشنهاد است و اقدامی اجرا نمی‌کند.");explain.setTextSize(16);box.addView(explain);EditText problem=field(box,"چه مشکلی احساس می‌کنی؟",draftSymptoms,4,false);problem.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8000)});
  new AlertDialog.Builder(this).setTitle("گزارش به گوگل ارسال شود؟").setView(wrap(box)).setNegativeButton("انصراف",null).setPositiveButton("موافقم؛ ارسال و تحلیل",(d,w)->{draftSymptoms=problem.getText().toString();hideKeyboard();state.analyze(savedKey,selected,draftReport,customPrompt,draftSymptoms);}).show();
 }
 private void monitorDialog(){
  LinearLayout box=dialogBox();TextView help=new TextView(this);help.setText("برنامه را باز نگه دار. با خروج از آن، پایش متوقف و نتیجه جزئی ذخیره می‌شود.");help.setTextSize(16);box.addView(help);
  RadioGroup group=new RadioGroup(this);String[] labels={"۱ دقیقه","۳ دقیقه","۵ دقیقه"};for(int i=0;i<3;i++){RadioButton b=new RadioButton(this);b.setId(1000+i);b.setText(labels[i]);b.setMinHeight(dp(52));group.addView(b);}group.check(1000);box.addView(group);
  new AlertDialog.Builder(this).setTitle("پایش در همین صفحه").setView(wrap(box)).setNegativeButton("انصراف",null).setPositiveButton("شروع پایش",(d,w)->{int chosen=group.getCheckedRadioButtonId()-1000;awaitingReport=true;state.monitor(new int[]{60,180,300}[chosen],prefs.getBoolean("apps",false),prefs.getBoolean("usage",false));}).show();
 }
 private LinearLayout dialogBox(){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);b.setPadding(dp(20),dp(4),dp(20),dp(12));return b;}
 private ScrollView wrap(LinearLayout b){ScrollView s=new ScrollView(this);s.addView(b);return s;}
 private void settingsButton(String label,String action){button(label,()->confirmSettings("فقط صفحه رسمی تنظیمات باز می‌شود؛ هر تغییر را خودت بررسی و تأیید می‌کنی.",new Intent(action)),false,false);}
 private void confirmSettings(String message,Intent intent){new AlertDialog.Builder(this).setTitle("باز کردن تنظیمات؟").setMessage(message).setNegativeButton("انصراف",null).setPositiveButton("باز کردن",(d,w)->{try{startActivity(intent);}catch(ActivityNotFoundException e){showError("این صفحه در گوشی تو وجود ندارد؛ از تنظیمات اصلی وارد شو.");}}).show();}
 private void refresh(){
  if(status==null)return;
  boolean hasKey=KeyVault.exists(this);if(hasKey!=observedKeyExists){observedKeyExists=hasKey;if(page==SETTINGS&&!state.busy){showPage();return;}}
  boolean changedReport=!Objects.equals(observedReport,state.report),changedAnswer=!Objects.equals(observedAnalysis,state.analysis);
  if(changedReport){observedReport=state.report;draftReport=state.report;}
  if(changedAnswer)observedAnalysis=state.analysis;
  if(!state.busy&&awaitingReport){awaitingReport=false;if(changedReport&&!state.report.isEmpty()){page=RESULTS;showPage();scroll.scrollTo(0,0);column.post(()->screenTitle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null));return;}}
  if(changedAnswer&&page==RESULTS&&!state.busy){showPage();return;}
  String message=notice.isEmpty()?state.status:notice;if(!status.getText().toString().equals(message))status.setText(message);
  if(!error.getText().toString().equals(state.error)){error.setText(state.error);error.setVisibility(state.error.isEmpty()?View.GONE:View.VISIBLE);}
  for(Button b:operations)b.setEnabled(!state.busy);if(stop!=null){stop.setVisibility(state.monitoring?View.VISIBLE:View.GONE);stop.setEnabled(state.monitoring);}
  if(state.monitoring)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  if(modelSpinner!=null&&observedModels!=state.models){observedModels=state.models;List<String> options=new ArrayList<>();options.add(state.models.isEmpty()?"هنوز مدلی دریافت نشده":"مدل را انتخاب کن");for(GeminiClient.Model m:state.models)options.add(m.toString());modelSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));}
 }
 private void showError(String message){state.error=message;refresh();}
 private void hideKeyboard(){android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(getCurrentFocus()!=null)im.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(),0);}
 private void copy(String label,String text){if(text.trim().isEmpty()){showError("متنی برای کپی نیست.");return;}((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label,text));Toast.makeText(this,"کپی شد",Toast.LENGTH_SHORT).show();}
 private void export(String text,String name){if(text.trim().isEmpty()){showError("گزارشی برای ذخیره نیست.");return;}state.pendingExport=text;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,name),EXPORT);}
 @Override public void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null){if(request==EXPORT)state.pendingExport="";return;}Uri uri=data.getData();
  if(request==IMPORT_KEY){state.start("در حال ورود امن کلید از فایل",()->{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=state.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>10000)throw new IOException();bytes.write(b,0,n);}}JSONObject config=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()).replaceFirst("^\\uFEFF",""));KeyVault.save(state,config.getString("api_key"));state.status="کلید شخصی وارد و رمز‌شده ذخیره شد؛ حالا اتصال و مدل‌ها را بررسی کن.";});}
  else if(request==EXPORT){String text=state.pendingExport;state.pendingExport="";if(text.isEmpty()){showError("متن در حافظه نیست؛ دوباره ذخیره را بزن.");return;}boolean started=state.start("در حال ذخیره فایل",()->{try(OutputStream out=state.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();out.write(text.getBytes(StandardCharsets.UTF_8));}state.status="فایل ذخیره شد.";});if(!started)showError("پس از پایان عملیات دوباره ذخیره را بزن.");}
 }
}
