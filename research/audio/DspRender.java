import com.bayapps.android.robophish.audio.AudienceDsp;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Streams the exact Android DSP engine for reproducible corpus measurements. */
public final class DspRender {
    public static void main(String[] args) throws Exception {
        AudienceDsp dsp=new AudienceDsp(44100,2);
        byte[] bytes=new byte[16384];short[] samples=new short[8192];
        long nanos=0,frames=0,max=0;
        AudienceDsp.Mode mode=AudienceDsp.Mode.valueOf(args[0]);
        float amount=Float.parseFloat(args[1]);
        try(InputStream in=new BufferedInputStream(System.in);OutputStream out=new BufferedOutputStream(System.out)) {
            int count;
            while((count=in.readNBytes(bytes,0,bytes.length))>0) {
                if(count%4!=0)throw new IllegalArgumentException("Incomplete stereo frame");
                ByteBuffer b=ByteBuffer.wrap(bytes,0,count).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<count/2;i++)samples[i]=b.getShort();
                long start=System.nanoTime();
                dsp.process(samples,samples,count/2,true,amount,mode);
                long elapsed=System.nanoTime()-start;nanos+=elapsed;max=Math.max(max,elapsed);frames+=count/4;
                b.rewind();for(int i=0;i<count/2;i++)b.putShort(samples[i]);out.write(bytes,0,count);
            }
        }
        System.err.println("{\"mode\":\""+mode+"\",\"amount\":"+amount+",\"frames\":"+frames+
            ",\"processing_seconds\":"+(nanos/1e9)+",\"audio_seconds\":"+(frames/44100.0)+
            ",\"max_buffer_ms\":"+(max/1e6)+"}");
    }
}
