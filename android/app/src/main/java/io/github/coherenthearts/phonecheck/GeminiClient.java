package io.github.coherenthearts.phonecheck;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Key exists only in the current call; fixed HTTPS host, no redirects or tool execution. */
public final class GeminiClient {
    private GeminiClient(){}
    public static class Model {public final String name,label;public Model(String n,String l){name=n;label=l;}@Override public String toString(){return label+" — "+name;}}
    private static JSONObject request(String path,String key,JSONObject payload)throws Exception{
        if(key==null||key.trim().isEmpty()||key.length()>512||key.trim().matches(".*\\s.*"))throw new IOException("کلید API معتبر را وارد کن.");
        HttpsURLConnection connection=(HttpsURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/"+path).openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(20000);connection.setReadTimeout(120000);
        connection.setRequestProperty("x-goog-api-key",key.trim());connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
        try{
            if(payload!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);try(OutputStream out=connection.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=connection.getResponseCode();
            if(code!=200){String message=switch(code){case 400->"مدل، کلید یا قالب درخواست پذیرفته نشد.";case 401,403->"کلید، پروژه یا منطقه اجازه دسترسی ندارد.";case 404->"مدل پیدا نشد؛ فهرست را تازه کن.";case 429->"سهمیه یا محدودیت نرخ درخواست تمام شده است.";default->"سرویس درخواست را کامل نکرد؛ بعداً دستی تلاش کن.";};throw new IOException("خطای Gemini "+code+": "+message);}
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=connection.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>4000000)throw new IOException("پاسخ بیش از حد بزرگ است.");out.write(buffer,0,n);}}
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }finally{connection.disconnect();}
    }
    public static List<Model> models(String key)throws Exception{
        List<Model> result=new ArrayList<>();String token="";
        for(int page=0;page<10;page++){
            JSONObject data=request("models?pageSize=1000"+(token.isEmpty()?"":"&pageToken="+URLEncoder.encode(token,StandardCharsets.UTF_8.name())),key,null);
            JSONArray list=data.optJSONArray("models");if(list!=null)for(int i=0;i<list.length();i++){JSONObject m=list.getJSONObject(i);JSONArray methods=m.optJSONArray("supportedGenerationMethods");boolean supported=false;if(methods!=null)for(int j=0;j<methods.length();j++)if(methods.optString(j).equals("generateContent"))supported=true;
                String name=m.optString("name").replaceFirst("^models/","");if(supported&&ReportRules.validModel(name))result.add(new Model(name,m.optString("displayName",name)));}
            token=data.optString("nextPageToken");if(token.isEmpty())return result;
        }
        throw new IOException("فهرست بیش از حد طولانی بود؛ نام مدل را دستی وارد کن.");
    }
    public static JSONObject payload(String report,String prompt,String symptoms)throws Exception{
        if(report==null||report.trim().isEmpty()||report.length()>300000)throw new IOException("گزارش خالی یا بیش از حد طولانی است.");
        if(prompt==null||prompt.trim().isEmpty()||prompt.length()>12000||symptoms==null||symptoms.length()>8000)throw new IOException("پرامپت یا توضیح مشکل نامعتبر است.");
        JSONObject body=new JSONObject();body.put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",prompt))));
        String text="توضیح کاربر:\n"+symptoms+"\nBEGIN_UNTRUSTED_REPORT\n"+report+"\nEND_UNTRUSTED_REPORT";
        body.put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",text)))));
        body.put("generationConfig",new JSONObject().put("maxOutputTokens",8192));return body;
    }
    public static String analyze(String key,String model,String report,String prompt,String symptoms)throws Exception{
        model=model==null?"":model.replaceFirst("^models/","");if(!ReportRules.validModel(model))throw new IOException("نام مدل معتبر را انتخاب کن.");
        JSONObject data=request("models/"+model+":generateContent",key,payload(report,prompt,symptoms));
        JSONArray candidates=data.optJSONArray("candidates");if(candidates==null||candidates.length()==0)throw new IOException("مدل پاسخ متنی نداد؛ ممکن است درخواست مسدود شده باشد.");
        JSONObject candidate=candidates.getJSONObject(0),content=candidate.optJSONObject("content");JSONArray parts=content==null?null:content.optJSONArray("parts");StringBuilder text=new StringBuilder();
        if(parts!=null)for(int i=0;i<parts.length();i++){JSONObject p=parts.getJSONObject(i);if(!p.optBoolean("thought")&&p.has("text"))text.append(p.optString("text")).append("\n");}
        if(text.toString().trim().isEmpty())throw new IOException("پاسخ متنی سازگار دریافت نشد.");
        String reason=candidate.optString("finishReason");if(!reason.isEmpty()&&!reason.equals("STOP"))text.append("\n[پاسخ ممکن است ناقص باشد؛ علت توقف: ").append(reason).append("]");return text.toString().trim();
    }
}
