"""Regenerate the frame-rate charts used by README.md and docs/MODRINTH.md.

The original docs/fps-chart.png had no generator kept alongside it, so the
numbers on the store page could not be re-plotted without redrawing the
whole thing by hand. This script is that generator.

Run:  python docs/tools/make_fps_chart.py
Writes: docs/fps-chart.png          the multi-distance comparison
        docs/fps-chart-rd64.png     the single headline comparison

THE NUMBERS. One source of truth, below, taken from the table published
in README.md. They are one machine, one world, one camera, at 1920x1080
on an AMD Radeon RX 9070 XT, one run with the mod and one without.

A caveat worth keeping in view: docs/PERFORMANCE.md records a DIFFERENT
sweep (the 1.0.0 release sweep, 413 fps against 99 at render distance 64,
a 4.15x speedup) and the numbers below are not written down anywhere in
that document. They are more favourable than the recorded sweep. If they
are ever questioned, the honest answer is to re-run the bench and write
the result into PERFORMANCE.md rather than to defend a figure whose
provenance is a marketing table.
"""

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter
from pathlib import Path

# render distance -> (Meshelium fps, Minecraft fps)
DATA = {
    12: (2437, 1621),
    16: (2126, 1115),
    24: (1709, 641),
    32: (1206, 393),
    48: (761, 205),
    64: (607, 114),
}

FOOTNOTE = ("Measured at 1920x1080 on an AMD Radeon RX 9070 XT, same world and same view, "
            "at default settings. Minecraft's own render distance slider stops at 32.")

# The narrow chart cannot fit the long footnote: at 9 inches it ran off
# both edges. Shorter line, same facts.
FOOTNOTE_NARROW = ("1920x1080, AMD Radeon RX 9070 XT, same world and view, default settings. "
                   "Minecraft's own slider stops at 32.")

BG = "#0b0e14"          # the page behind the plot
FG = "#ffffff"          # titles and value labels
MUTED = "#9aa4b2"       # axis text and the footnote
MESH = "#4d9bff"        # Meshelium
VANILLA = "#3d4757"     # Minecraft on its own
GRID = "#222836"


def _style(ax, fig):
    fig.patch.set_facecolor(BG)
    ax.set_facecolor(BG)
    for side in ("top", "right", "left", "bottom"):
        ax.spines[side].set_visible(False)
    ax.tick_params(colors=MUTED, length=0, labelsize=13)
    ax.yaxis.grid(True, color=GRID, linewidth=1)
    ax.set_axisbelow(True)


def paired_chart(path: Path):
    """Both renderers side by side, so the gap is the picture."""
    rds = list(DATA)
    mesh = [DATA[r][0] for r in rds]
    van = [DATA[r][1] for r in rds]
    x = range(len(rds))
    w = 0.38

    fig, ax = plt.subplots(figsize=(12.8, 6.4), dpi=120)
    _style(ax, fig)

    b1 = ax.bar([i - w / 2 for i in x], mesh, w, label="Meshelium on", color=MESH)
    b2 = ax.bar([i + w / 2 for i in x], van, w, label="Meshelium off", color=VANILLA)

    for rect, v in list(zip(b1, mesh)) + list(zip(b2, van)):
        ax.text(rect.get_x() + rect.get_width() / 2, rect.get_height() + 40,
                f"{v:,}", ha="center", va="bottom", color=FG,
                fontsize=12, fontweight="bold")

    # The multiplier is the story, so put it above each pair.
    for i, r in enumerate(rds):
        m, v = DATA[r]
        ax.text(i, max(m, v) + 235, f"{m / v:.1f}x", ha="center", va="bottom",
                color=MESH, fontsize=15, fontweight="bold")

    ax.set_xticks(list(x))
    ax.set_xticklabels([str(r) for r in rds])
    ax.set_xlabel("Render distance (chunks)", color=MUTED, fontsize=14, labelpad=12)
    ax.set_ylabel("Frames per second", color=MUTED, fontsize=13, labelpad=10)
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{int(v):,}"))
    ax.set_ylim(0, max(mesh) * 1.20)

    ax.set_title("Frames per second, with Meshelium and without",
                 color=FG, fontsize=20, fontweight="bold", pad=26)

    leg = ax.legend(loc="upper right", frameon=False, fontsize=13)
    for t in leg.get_texts():
        t.set_color(MUTED)

    fig.text(0.5, 0.028, FOOTNOTE, ha="center", color=MUTED, fontsize=11)
    fig.subplots_adjust(top=0.86, bottom=0.17, left=0.075, right=0.975)
    fig.savefig(path, facecolor=BG)
    plt.close(fig)
    print(f"wrote {path}")


def rd64_chart(path: Path):
    """Just the headline: the furthest distance, on against off."""
    mesh, van = DATA[64]

    fig, ax = plt.subplots(figsize=(9.0, 6.0), dpi=120)
    _style(ax, fig)

    bars = ax.bar(["Meshelium on", "Meshelium off"], [mesh, van],
                  width=0.5, color=[MESH, VANILLA])
    for rect, v in zip(bars, (mesh, van)):
        ax.text(rect.get_x() + rect.get_width() / 2, rect.get_height() + 12,
                f"{v:,} FPS", ha="center", va="bottom", color=FG,
                fontsize=19, fontweight="bold")

    ax.set_ylim(0, mesh * 1.28)
    ax.set_ylabel("Frames per second", color=MUTED, fontsize=13, labelpad=10)
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{int(v):,}"))
    ax.set_title(f"Render distance 64: {mesh / van:.1f}x the frames",
                 color=FG, fontsize=21, fontweight="bold", pad=26)

    fig.text(0.5, 0.030, FOOTNOTE_NARROW, ha="center", color=MUTED, fontsize=10.5)
    fig.subplots_adjust(top=0.85, bottom=0.16, left=0.115, right=0.965)
    fig.savefig(path, facecolor=BG)
    plt.close(fig)
    print(f"wrote {path}")


if __name__ == "__main__":
    docs = Path(__file__).resolve().parents[1]
    paired_chart(docs / "fps-chart.png")
    rd64_chart(docs / "fps-chart-rd64.png")
