import com.bayapps.android.robophish.audio.offline.OfflineRestorationDsp;
import com.bayapps.android.robophish.audio.offline.OfflineRestorationProfile;
import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Exact Android-independent engine, two complete passes over signed PCM16 LE. */
public final class OfflineRender {
  interface BlockConsumer { void accept(short[] block, int count) throws IOException; }
  static void read(File file, BlockConsumer consumer) throws IOException {
    try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
      byte[] bytes = new byte[16384]; short[] samples = new short[8192];
      int n;
      while ((n = input.readNBytes(bytes, 0, bytes.length)) > 0) {
        if ((n & 1) != 0) throw new IOException("Incomplete PCM16 sample");
        for (int i=0; i<n/2; i++) samples[i]=(short)((bytes[2*i]&255)|(bytes[2*i+1]<<8));
        consumer.accept(samples, n/2);
      }
    }
  }
  static String json(Object value) throws IllegalAccessException {
    if(value == null) return "null";
    if(value instanceof Number || value instanceof Boolean) return value.toString();
    if(value instanceof String) return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    if(value.getClass().isArray()) {
      StringBuilder out=new StringBuilder("[");
      for(int i=0;i<Array.getLength(value);i++) { if(i>0)out.append(',');out.append(json(Array.get(value,i))); }
      return out.append(']').toString();
    }
    StringBuilder out=new StringBuilder("{"); boolean first=true;
    for(Field field:value.getClass().getFields()) {
      if(Modifier.isStatic(field.getModifiers())) continue;
      if(!first) out.append(','); first=false;
      out.append(json(field.getName())).append(':').append(json(field.get(value)));
    }
    return out.append('}').toString();
  }
  public static void main(String[] args) throws Exception {
    if(args.length!=5) throw new IllegalArgumentException("input.s16 output.s16 report.json sampleRate channels");
    File input=new File(args[0]); int rate=Integer.parseInt(args[3]),channels=Integer.parseInt(args[4]);
    if(input.length()%(2*channels)!=0) throw new IOException("Incomplete PCM frame");
    long started=System.nanoTime();
    OfflineRestorationProfile.Analyzer analyzer=new OfflineRestorationProfile.Analyzer(rate,channels);
    read(input, analyzer::accept);
    OfflineRestorationProfile profile=analyzer.finish();
    long analyzed=System.nanoTime();
    OfflineRestorationDsp dsp=new OfflineRestorationDsp(rate,channels,profile);
    try(OutputStream output=new BufferedOutputStream(new FileOutputStream(args[1]))) {
      OfflineRestorationDsp.PcmSink sink=(samples,count)->{
        byte[] bytes=new byte[count*2];
        for(int i=0;i<count;i++){bytes[2*i]=(byte)samples[i];bytes[2*i+1]=(byte)(samples[i]>>8);}
        output.write(bytes);
      };
      read(input,(samples,count)->dsp.process(samples,count,sink));
      dsp.finish(sink);
    }
    long ended=System.nanoTime();
    if(new File(args[1]).length()!=input.length()) throw new IOException("Frame count changed");
    if(dsp.getClippedSampleCount()!=0) throw new IOException("New output clipping; reject render");
    String report="{\"profile\":"+json(profile)+",\"band_centers_hz\":"+json(profile.getBandCentersHz())+",\"static_gain_db\":"+json(profile.getStaticGainDb())+",\"dynamic_threshold_db\":"+json(profile.getDynamicThresholdDb())+",\"analysis_seconds\":"+(analyzed-started)/1e9+",\"render_seconds\":"+(ended-analyzed)/1e9+",\"frames\":"+input.length()/(2*channels)+",\"processed_windows\":"+dsp.getProcessedWindows()+",\"dynamic_windows\":"+dsp.getDynamicWindows()+",\"maximum_dynamic_reduction_db\":"+dsp.getLargestDynamicReductionDb()+",\"output_peak\":"+dsp.getOutputPeak()+",\"new_clipped_samples\":"+dsp.getClippedSampleCount()+"}";
    try(Writer out=new FileWriter(args[2])){out.write(report);}
    System.out.println(report);
  }
}
