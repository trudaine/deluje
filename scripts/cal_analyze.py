#!/usr/bin/env python3
"""CAL_SONG DSP calibration: pair each file's own 28-segment onset grid and
compare the 4s note region per segment. Handles differing segment cadences
(our offline render ~8s/seg, hardware ~6s/seg) by detecting onsets per file.

Usage: cal_analyze.py OURS.wav HARDWARE.wav
"""
import sys, wave, numpy as np

NAMES=["01 SINE","02 TRIANGLE","03 SAW","04 SQUARE50","05 SQUARE_PW","06 SAW_PW",
 "07 PWM_LFO","08 PW_ENV","09 SAW_SYNC","10 SQ_SYNC_PW","11 NOISE","12 UNISON4",
 "13 LPF_RESO","14 LPF_DRIVE","15 HPF_RESO","16 FM_LOW","17 FM_HIGH","18 FM_CHAIN",
 "19 FM_MUTE","20 FM_FEEDBACK","21 DELAY_SYNC","22 DELAY_PINGPONG","23 REVERB_SEND",
 "24 CHORUS","25 FLANGER","26 PHASER","27 ARP_GATE","28 PLUCK"]
SR=44100; NOTE=4.0

def read(path):
    w=wave.open(path,'rb');n=w.getnframes();ch=w.getnchannels();sw=w.getsampwidth()
    raw=w.readframes(n);w.close()
    if sw==3:
        a=np.frombuffer(raw,dtype=np.uint8).reshape(-1,3).astype(np.int32)
        v=(a[:,0]|(a[:,1]<<8)|(a[:,2]<<16));v=np.where(v&0x800000,v-0x1000000,v);d=v.astype(np.float64)/(1<<23)
    elif sw==2: d=np.frombuffer(raw,dtype='<i2').astype(np.float64)/(1<<15)
    else: d=np.frombuffer(raw,dtype='<i4').astype(np.float64)/(1<<31)
    d=d.reshape(-1,ch); return d, d.mean(axis=1)

def env(m,ms=20):
    w=int(ms/1000*SR); return np.sqrt(np.convolve(m**2,np.ones(w)/w,'same'))

def onsets(m,n_expect=28,refractory=5.0):
    e=env(m); pk=e.max(); on=e>pk*0.04
    edges=np.where((~on[:-1])&(on[1:]))[0]; out=[]
    for x in edges:
        if not out or x-out[-1]>SR*refractory: out.append(x)
    return out

def logmag(x):
    x=x[:int(NOTE*SR)]
    if len(x)<4096: x=np.pad(x,(0,4096-len(x)))
    X=np.abs(np.fft.rfft(x*np.hanning(len(x)))); return np.log1p(X)

def cosine(a,b):
    n=min(len(a),len(b));a,b=a[:n],b[:n];da,db=np.linalg.norm(a),np.linalg.norm(b)
    return float(np.dot(a,b)/(da*db)) if da and db else 0.0

def purity(x):  # sine detector: energy in fundamental±2 bins / total
    x=x[int(0.1*SR):int(3.5*SR)]
    X=np.abs(np.fft.rfft(x*np.hanning(len(x))))**2
    if X.sum()==0: return 0.0
    k=np.argmax(X); return float(X[max(0,k-2):k+3].sum()/X.sum())

def duty(x):
    x=x[:int(NOTE*SR)]; e=env(x,5); return float((e>e.max()*0.15).mean())

def first_echo_ms(m,onset):
    gap_a=onset+int(NOTE*SR); gap=m[gap_a:gap_a+int(1.8*SR)]
    if len(gap)<200: return None
    e=env(gap,3); thr=max(e.max()*0.25,e.mean()*3)
    i=np.argmax(e>thr)
    return i/SR*1000 if e[i]>thr else None

def note_win(m,onset):  # skip 60ms attack, take 3.4s sustain
    a=onset+int(0.06*SR); return m[a:a+int(3.4*SR)]

def main():
    od,om=read(sys.argv[1]); hd,hm=read(sys.argv[2])
    oo=onsets(om); ho=onsets(hm)
    print(f"OURS {sys.argv[1]}: {len(om)/SR:.1f}s  onsets={len(oo)}  first={oo[0]/SR:.2f}s")
    print(f"  spacing≈{np.median(np.diff(oo))/SR:.2f}s")
    print(f"HW   {sys.argv[2]}: {len(hm)/SR:.1f}s  onsets={len(ho)}  first={ho[0]/SR:.2f}s")
    print(f"  spacing≈{np.median(np.diff(ho))/SR:.2f}s")
    N=min(len(oo),len(ho),28)
    if len(oo)!=28 or len(ho)!=28:
        print(f"  !! expected 28/28 onsets, got ours={len(oo)} hw={len(ho)}; pairing first {N}")
    print("\n# seg               cosine  ours_pk  hw_pk   marker")
    cs=[]
    for i in range(N):
        ow=note_win(om,oo[i]); hw=note_win(hm,ho[i])
        c=cosine(logmag(ow),logmag(hw)); cs.append(c)
        opk=np.abs(ow).max(); hpk=np.abs(hw).max(); mk=""
        if i==0: mk=f"ours_purity={purity(ow):.2f} hw_purity={purity(hw):.2f}"
        if i==2: mk=f"ours saw/sine lvl={opk/max(np.abs(note_win(om,oo[0])).max(),1e-9):.2f}"
        if i==18:
            mk=f"MUTE: ours_purity={purity(ow):.3f}  HW_purity={purity(hw):.3f} (both~1.0 ⇒ gate OK)"
        if i==20:
            eo=first_echo_ms(om,oo[i]); eh=first_echo_ms(hm,ho[i])
            mk=f"echo ours={eo:.0f}ms hw={eh:.0f}ms" if (eo and eh) else f"echo ours={eo} hw={eh}"
        if i==26: mk=f"gate duty ours={duty(ow):.2f} hw={duty(hw):.2f}"
        flag="" if c>=0.80 else (" <<<" if c<0.60 else " <")
        print(f"{NAMES[i]:16s}  {c:5.3f}  {opk:6.3f}  {hpk:6.3f}   {mk}{flag}")
    cs=np.array(cs)
    print(f"\nSUMMARY  n={len(cs)}  median={np.median(cs):.3f}  mean={np.mean(cs):.3f}  "
          f">=0.80: {(cs>=0.8).sum()}  0.6-0.8: {((cs>=0.6)&(cs<0.8)).sum()}  <0.60: {(cs<0.6).sum()}")
    order=np.argsort(cs)
    print("  weakest:", ", ".join(f"{NAMES[j].split()[0]}={cs[j]:.2f}" for j in order[:6]))

if __name__=="__main__": main()
