package io.github.coherenthearts.phonecheck;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
public class ReportRulesTest {
 @Test public void missingValuesNeverClaimHealth(){List<String> h=ReportRules.hints(-1,-1,-1,-1,false,Double.NaN,-1,true,true);assertTrue(h.get(0).contains("به معنی سالم بودن کامل"));assertFalse(h.toString().contains("باتری در این نمونه بالا"));}
 @Test public void lowStorageNeedsBackup(){assertTrue(ReportRules.hints(1000,50,1000,500,false,30,-1,true,true).toString().contains("نسخه پشتیبان"));}
 @Test public void lowRamIsNotFaultDiagnosis(){assertTrue(ReportRules.hints(1000,500,1000,50,false,30,0,true,true).toString().contains("به تنهایی"));}
 @Test public void actualLowMemorySignalReported(){assertTrue(ReportRules.hints(1000,500,1000,500,true,30,0,true,true).toString().contains("اندروید در زمان این نمونه"));}
 @Test public void heatAndThermalReportedSeparately(){String text=ReportRules.hints(1000,500,1000,500,false,43,3,true,true).toString();assertTrue(text.contains("دمای پردازنده نیست"));assertTrue(text.contains("محدودسازی حرارتی"));}
 @Test public void captiveNetworkIsNotHardwareDiagnosis(){assertTrue(ReportRules.hints(1000,500,1000,500,false,30,0,true,false).toString().contains("تأیید نشده"));}
 @Test public void validatesModelsWithoutPaths(){assertTrue(ReportRules.validModel("gemini-3.8-flash"));assertFalse(ReportRules.validModel("../other?key=secret"));assertFalse(ReportRules.validModel(""));assertFalse(ReportRules.validModel(null));}
 @Test public void unknownThermalStaysUnknown(){assertEquals("در دسترس نیست",ReportRules.thermalName(-1));assertEquals("در دسترس نیست",ReportRules.thermalName(99));assertEquals("متوسط",ReportRules.thermalName(2));}
}
