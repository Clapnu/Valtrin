"""Renders Valtrin's jackpot sound (alternating x8 dings) to assets/valtrin/sounds/jackpot.ogg.
Same recipe as the preview page: a metallic bell (5 partials, each doubled with a slightly detuned copy) struck 8 times,
55 ms apart, alternating 2349 Hz and 3136 Hz, plus a short noise click on every strike."""
import numpy as np, wave, subprocess, sys, os
SR = 44100
rng = np.random.default_rng(7)
out = np.zeros(int(SR * 1.4))

def ring_bell(t, f, vol, dur):
    parts = [(1, 1, 1), (2.32, .55, .55), (3.18, .38, .4), (4.17, .22, .28), (5.43, .12, .18)]
    for ratio, amp, dscale in parts:
        for det, k in ((1.0, 1.0), (1.006, 0.6)):
            v = vol * amp * k
            d = dur * dscale
            n = int(SR * (d + 0.05))
            tt = np.arange(n) / SR
            attack = 0.003
            env = np.where(tt < attack, 1e-4 * (v / 1e-4) ** (tt / attack), v * (1e-4 / v) ** ((tt - attack) / max(d - attack, 1e-3)))
            env = np.where(tt > d, 0, env)
            sig = np.sin(2 * np.pi * f * ratio * det * tt) * env
            i = int(t * SR)
            out[i:i + n] += sig[: len(out) - i]

def click(t, vol):
    n = int(SR * 0.02)
    sig = (rng.random(n) * 2 - 1) * (1 - np.arange(n) / n) ** 3 * vol
    i = int(t * SR)
    out[i:i + n] += sig

freqs = (2349, 3136)
for i in range(8):
    last = i == 7
    vol = 0.58 + (0.12 if last else 0)
    t = 0.02 + i * 0.055
    ring_bell(t, freqs[i % 2], vol, 0.4 if last else 0.18)
    click(t, vol * 0.5)

end = int(SR * 1.05)
sig = out[:end]
fade = int(SR * 0.05)
sig[-fade:] *= np.linspace(1, 0, fade)
sig = sig / np.max(np.abs(sig)) * 0.9
here = os.path.dirname(os.path.abspath(__file__))
dest = os.path.join(here, "..", "src", "main", "resources", "assets", "valtrin", "sounds")
os.makedirs(dest, exist_ok=True)
wav = os.path.join(dest, "jackpot.wav")
with wave.open(wav, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((sig * 32767).astype(np.int16).tobytes())
subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "5", os.path.join(dest, "jackpot.ogg")], check=True)
os.remove(wav)
print("ok", len(sig) / SR, "s")
