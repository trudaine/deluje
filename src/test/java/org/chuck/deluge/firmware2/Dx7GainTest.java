package org.chuck.deluge.firmware2;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
public class Dx7GainTest {
  @Test public void gainTableHasValues() {
    assertTrue(Dx7Tables.gainTable[0] > 0, "gain[0]=" + Dx7Tables.gainTable[0]);
    assertTrue(Dx7Tables.gainTable[99] >= 0, "gain[99]=" + Dx7Tables.gainTable[99]);
    int gain = FmCore.exp2Lookup(0);
    System.out.println("exp2Lookup(0)=" + gain);
    assertTrue(gain > 0, "exp2Lookup(0) should be positive, got " + gain);
  }
  @Test public void fmCoreOneOp() {
    FmCore.FmOpParams[] p = new FmCore.FmOpParams[6];
    for (int i=0;i<6;i++) p[i]=new FmCore.FmOpParams();
    // Set all operators to simple carriers at full level
    int[] alg = FmCore.ALGORITHMS[31]; // algorithm 32: all carriers (0x04 each)
    for (int i=0;i<6;i++) {
      p[i].freq=(int)(440.0*Functions.K_MAX_SAMPLE_VALUE/44100.0);
      p[i].level_in=14<<24;
    }
    int[] buf=new int[132]; int[] fb=new int[2];
    FmCore.render(buf,128,p,31,fb,16);
    long sum=0; for(int v:buf) sum+=(long)v*v;
    System.out.println("RMS=" + Math.sqrt(sum/128.0)/2147483648.0);
    assertTrue(sum>0, "all-carrier algorithm should produce output");
  }
}
