"""Plot measured preservation and applied corrections; never invent quality ratings."""
import argparse
import json
import os
import tempfile
from pathlib import Path
import numpy as np
os.environ.setdefault("MPLCONFIGDIR",str(Path(tempfile.gettempdir())/"robophish-research-matplotlib"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--results",type=Path,nargs="+",required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    rows=[]
    for path in args.results:
        rows.extend(json.loads(path.read_text())["tracks"])
    args.output.mkdir(parents=True,exist_ok=True)
    plt.rcParams.update({"font.size":10,"axes.spines.top":False,"axes.spines.right":False,"figure.facecolor":"#fcfbf7","axes.facecolor":"#fcfbf7"})
    figure,axes=plt.subplots(1,3,figsize=(15,11),gridspec_kw={"width_ratios":[1.7,1,1]})
    positions=np.arange(len(rows))
    labels=[r["metadata"]["date"]+"  "+r["metadata"]["title"][:25] for r in rows]
    colors=["#267c69" if r["metadata"]["split"]=="holdout" else "#71818a" for r in rows]
    applied=[0 if r["engine"]["profile"]["passthrough"] else r["engine"]["profile"]["maximumCorrectionDb"] for r in rows]
    dynamic=[r["engine"]["maximum_dynamic_reduction_db"] for r in rows]
    axes[0].barh(positions-.16,applied,height=.30,color=colors,label="Static correction")
    axes[0].barh(positions+.16,dynamic,height=.30,color=colors,alpha=.4,label="Largest dynamic correction")
    axes[0].set_yticks(positions,labels)
    axes[0].set_xlabel("Maximum applied attenuation (dB)")
    axes[0].set_xlim(0,3.2)
    axes[0].set_title("No corrections qualified" if not any(applied) else "Applied corrections",loc="left",fontweight="bold")
    axes[0].legend(loc="lower right",frameon=False,fontsize=9)
    for i,row in enumerate(rows):
        if row["engine"]["profile"]["passthrough"]:
            axes[0].text(.06,i,"Unchanged",va="center",fontsize=8,color="#6b7677")
    for axis in axes:
        axis.invert_yaxis()
        axis.grid(axis="x",alpha=.15)
        axis.set_axisbelow(True)
    axes[1].scatter([r["comparison"]["onset_flux_correlation"] for r in rows],positions,c=colors,s=38,zorder=3)
    axes[1].axvline(.95,color="#ac6456",linestyle="--",linewidth=1)
    axes[1].set_xlim(.94,1.002)
    axes[1].set_yticks(positions,[])
    axes[1].set_xlabel("Original / output onset-flux correlation")
    axes[1].set_title("Transient preservation",loc="left",fontweight="bold")
    axes[2].scatter([r["comparison"]["side_mid_change_db"] for r in rows],positions,c=colors,s=38,zorder=3)
    axes[2].axvline(-.5,color="#ac6456",linestyle="--",linewidth=1)
    axes[2].axvline(.5,color="#ac6456",linestyle="--",linewidth=1)
    axes[2].set_xlim(-.6,.6)
    axes[2].set_yticks(positions,[])
    axes[2].set_xlabel("Side / mid change (dB)")
    axes[2].set_title("Stereo preservation",loc="left",fontweight="bold")
    abstain=sum(r["engine"]["profile"]["passthrough"] for r in rows)
    hours=sum(r["original"]["duration_seconds"] for r in rows)/3600
    figure.suptitle(f"Offline audio: {len(rows)} complete recordings · {hours:.2f} hours",x=.03,ha="left",fontsize=22,fontweight="bold")
    figure.text(.03,.925,f"{abstain} unchanged copies. Grey: development. Green: untouched holdout. No subjective quality ratings collected.",fontsize=11,color="#3f5653")
    figure.text(.03,.018,"Dashed lines are provisional preservation bounds, not quality scores. Near-mono width deltas are treated as inapplicable when both side/mid ratios are below −60 dB.\nNo soundboard-equivalence claim. These measurements do not verify semantic crowd preservation or listener preference.",fontsize=9,color="#465450")
    figure.tight_layout(rect=(0,.06,1,.90))
    figure.savefig(args.output/"corpus-preservation.png",dpi=170)
    figure.savefig(args.output/"corpus-preservation.svg")
    plt.close(figure)
    centers=rows[0]["engine"]["band_centers_hz"]
    matrix=np.asarray([[0]*len(centers) if r["engine"]["profile"]["passthrough"] else [-v for v in r["engine"]["static_gain_db"]] for r in rows])
    figure,axis=plt.subplots(figsize=(12,9))
    plotted=axis.imshow(matrix,vmin=0,vmax=3,cmap="YlGnBu",aspect="auto",interpolation="nearest")
    axis.set_yticks(positions,labels)
    axis.set_xticks(np.arange(len(centers))[::2],[f"{x:g}" for x in centers[::2]])
    axis.set_xlabel("Analysis band center (Hz)")
    axis.set_title("No frequency corrections qualified; all original samples retained" if not matrix.any() else "Applied static correction by full-track profile\nBlank rows preserve original samples",loc="left",fontsize=16,pad=18)
    figure.colorbar(plotted,ax=axis,label="Attenuation (dB)",shrink=.65)
    figure.tight_layout()
    figure.savefig(args.output/"corpus-spectral-corrections.png",dpi=170)
    plt.close(figure)


if __name__=="__main__":main()
