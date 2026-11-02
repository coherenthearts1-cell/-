package io.github.coherenthearts.phonecheck;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Per-install AES-GCM key in Android Keystore. Never bundled with the APK or backed up. */
public final class KeyVault {
 private static final String ALIAS="phonecheck-gemini-v1";
 private KeyVault(){}
 private static File file(Context c){return new File(c.getNoBackupFilesDir(),"gemini.enc");}
 private static SecretKey encryptionKey()throws Exception{
  KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
  if(!store.containsAlias(ALIAS)){
   KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
   generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
   return generator.generateKey();
  }
  return (SecretKey)store.getKey(ALIAS,null);
 }
 public static synchronized boolean exists(Context c){return file(c).isFile();}
 public static synchronized void save(Context c,String key)throws Exception{
  key=key==null?"":key.trim();if(key.isEmpty()||key.length()>512||key.matches(".*\\s.*"))throw new IOException("کلید API معتبر را وارد کن.");
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,encryptionKey());
  JSONObject record=new JSONObject().put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
   .put("ciphertext",Base64.encodeToString(cipher.doFinal(key.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));
  File target=file(c),temp=new File(c.getNoBackupFilesDir(),"gemini.enc.tmp");
  try(FileOutputStream out=new FileOutputStream(temp)){out.write(record.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
  if(!temp.renameTo(target))throw new IOException("ذخیره کلید رمز‌شده انجام نشد.");
 }
 public static synchronized String load(Context c)throws Exception{
  File target=file(c);if(!target.isFile())return "";
  byte[] bytes=new byte[(int)Math.min(target.length(),10000)];
  if(target.length()>10000)throw new IOException("کلید ذخیره‌شده قابل خواندن نیست.");
  try(FileInputStream in=new FileInputStream(target)){int count=0,n;while(count<bytes.length&&(n=in.read(bytes,count,bytes.length-count))>0)count+=n;if(count!=bytes.length)throw new IOException("کلید ذخیره‌شده ناقص است.");}
  JSONObject record=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,encryptionKey(),new GCMParameterSpec(128,Base64.decode(record.getString("iv"),Base64.NO_WRAP)));
  return new String(cipher.doFinal(Base64.decode(record.getString("ciphertext"),Base64.NO_WRAP)),StandardCharsets.UTF_8);
 }
 public static synchronized void delete(Context c)throws Exception{
  if(file(c).exists()&&!file(c).delete())throw new IOException("حذف کلید انجام نشد.");
  File temp=new File(c.getNoBackupFilesDir(),"gemini.enc.tmp");if(temp.exists())temp.delete();
  KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(ALIAS))store.deleteEntry(ALIAS);
 }
}
