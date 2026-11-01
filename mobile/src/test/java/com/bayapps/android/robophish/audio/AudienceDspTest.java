package com.bayapps.android.robophish.audio;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Random;

public class AudienceDspTest {
    private static short[] sine(int sr, int channels, double frequency, double amplitude, int seconds) {
        short[] x=new short[sr*channels*seconds];
        for(int i=0;i<x.length/channels;i++)for(int c=0;c<channels;c++)
            x[i*channels+c]=(short)Math.round(amplitude*32767*Math.sin(2*Math.PI*frequency*i/sr));
        return x;
    }
    private static double rms(short[] x,int from) {
        double power=0;for(int i=from;i<x.length;i++)power+=(double)x[i]*x[i];
        return Math.sqrt(power/(x.length-from));
    }
    @Test public void disabledIsBitExactAcrossAllPcmValues() {
        short[] x=new short[65536], y=new short[x.length];
        for(int i=0;i<x.length;i++)x[i]=(short)(i-32768);
        new AudienceDsp(44100,2).process(x,y,x.length,false,1,AudienceDsp.Mode.CLARITY);
        assertArrayEquals(x,y);
    }
    @Test public void offReturnsToExactOriginalAfterRamp() {
        short[] x=sine(44100,2,440,.8,2),y=new short[x.length];
        AudienceDsp dsp=new AudienceDsp(44100,2);
        dsp.process(x,y,x.length,true,1,AudienceDsp.Mode.CLARITY);
        dsp.process(x,y,x.length,false,1,AudienceDsp.Mode.CLARITY);
        assertArrayEquals(Arrays.copyOfRange(x,10000,x.length),Arrays.copyOfRange(y,10000,y.length));
    }
    @Test public void processingDoesNotDependOnDecoderBufferBoundaries() {
        short[] x=new short[44100*2],whole=new short[x.length],chunked=new short[x.length];
        Random random=new Random(42);for(int i=0;i<x.length;i++)x[i]=(short)random.nextInt(30000);
        new AudienceDsp(44100,2).process(x,whole,x.length,true,.6f,AudienceDsp.Mode.CLARITY);
        AudienceDsp dsp=new AudienceDsp(44100,2);
        for(int p=0;p<x.length;p+=882) {
            short[] chunk=Arrays.copyOfRange(x,p,Math.min(x.length,p+882));
            dsp.process(chunk,chunk,chunk.length,true,.6f,AudienceDsp.Mode.CLARITY);
            System.arraycopy(chunk,0,chunked,p,chunk.length);
        }
        assertArrayEquals(whole,chunked);
    }
    @Test public void monoSignalDoesNotAcquireStereoArtifacts() {
        short[] x=sine(48000,2,137,.7,2),y=new short[x.length];
        new AudienceDsp(48000,2).process(x,y,x.length,true,1,AudienceDsp.Mode.CLARITY);
        for(int i=0;i<y.length;i+=2)assertEquals(y[i],y[i+1]);
    }
    @Test public void stereoPolarityAndBalanceRemainMatched() {
        short[] x=sine(44100,2,440,.7,2),y=new short[x.length];
        for(int i=0;i<x.length;i+=2)x[i+1]=(short)-x[i];
        new AudienceDsp(44100,2).process(x,y,x.length,true,1,AudienceDsp.Mode.CLARITY);
        for(int i=0;i<y.length;i+=2)assertTrue(Math.abs(y[i]+y[i+1])<=1);
    }
    @Test public void subsonicEnergyIsReducedWithoutRemovingBassFundamental() {
        for(int sr:new int[]{32000,44100,48000,96000}) {
            short[] rumble=sine(sr,1,10,.1,2),bass=sine(sr,1,55,.1,2);
            short[] a=new short[rumble.length],b=new short[bass.length];
            new AudienceDsp(sr,1).process(rumble,a,a.length,true,1,AudienceDsp.Mode.RUMBLE);
            new AudienceDsp(sr,1).process(bass,b,b.length,true,1,AudienceDsp.Mode.RUMBLE);
            assertTrue(20*Math.log10(rms(a,sr)/rms(rumble,sr)) < -18);
            assertTrue(20*Math.log10(rms(b,sr)/rms(bass,sr)) > -1);
        }
    }
    @Test public void zeroAmountAndInvalidAmountAreTransparent() {
        short[] x=sine(44100,1,220,.8,1),y=new short[x.length];
        AudienceDsp dsp=new AudienceDsp(44100,1);
        dsp.process(x,y,x.length,true,Float.NaN,AudienceDsp.Mode.CLARITY);
        assertArrayEquals(x,y);
        dsp.process(x,y,x.length,true,0,AudienceDsp.Mode.CLARITY);
        assertArrayEquals(x,y);
    }
    @Test public void extremeInputsDoNotWrapOrCreateAsymmetricStereoClipping() {
        short[] x=new short[88200],y=new short[x.length];
        for(int i=0;i<x.length;i++)x[i]=(short)((i/100)%2==0?32767:-32768);
        new AudienceDsp(44100,2).process(x,y,x.length,true,1,AudienceDsp.Mode.CLARITY);
        for(int i=10000;i<y.length;i+=2) {
            assertTrue(Math.abs((int)y[i])<=31130);
            assertEquals(y[i],y[i+1]);
        }
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsPartialFrame() {
        new AudienceDsp(44100,2).process(new short[3],new short[3],3,true,1,AudienceDsp.Mode.RUMBLE);
    }
    @Test public void impulseHasOutputImmediatelyAndKeepsSampleCount() {
        short[] x=new short[100],y=new short[100];x[0]=1000;
        new AudienceDsp(44100,1).process(x,y,100,true,1,AudienceDsp.Mode.RUMBLE);
        assertTrue(y[0]!=0);assertEquals(x.length,y.length);
    }
}
