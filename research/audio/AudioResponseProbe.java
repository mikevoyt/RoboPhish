import com.bayapps.android.robophish.audio.AudienceDsp;
public class AudioResponseProbe {
 public static void main(String[] args) {
  System.out.println("mode,blend,frequency_hz,gain_db");
  int sr=44100;
  for(AudienceDsp.Mode mode:AudienceDsp.Mode.values())for(float blend:new float[]{.25f,.6f,1f})for(int f:new int[]{10,20,30,40,55,80,100,280,1000,2200,6000,12000}) {
   short[] x=new short[sr*2],y=new short[x.length];
   for(int i=0;i<x.length;i++)x[i]=(short)Math.round(.2*32767*Math.sin(2*Math.PI*f*i/sr));
   new AudienceDsp(sr,1).process(x,y,x.length,true,blend,mode);
   double in=0,out=0;for(int i=sr;i<x.length;i++){in+=(double)x[i]*x[i];out+=(double)y[i]*y[i];}
   System.out.println(mode+","+blend+","+f+","+10*Math.log10(out/in));
  }
 }
}
