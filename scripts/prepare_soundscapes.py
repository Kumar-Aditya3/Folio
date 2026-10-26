"""Reproducible, offline-at-runtime sound assets. Source licences: docs/soundscapes.md."""
from pathlib import Path
import urllib.request, zipfile, wave
import numpy as np
import scipy.signal as signal

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.build-out' / 'sound-sources'
CACHE.mkdir(parents=True, exist_ok=True)
def download(url, name):
    path = CACHE / name
    if not path.exists():
        request = urllib.request.Request(url, headers={'User-Agent': 'Folio/1.0 (open source reading app; asset preparation)'})
        with urllib.request.urlopen(request, timeout=120) as response:
            path.write_bytes(response.read())
    return path

rain_zip = download('https://opengameart.org/sites/default/files/Rain%20MP3.zip', 'rain.zip')
with zipfile.ZipFile(rain_zip) as archive:
    entries = [n for n in archive.namelist() if n.lower().endswith('.mp3')]
    rain = CACHE / 'rain.mp3'
    rain.write_bytes(archive.read(entries[0]))
cafe = download('https://upload.wikimedia.org/wikipedia/commons/5/54/Cafe_ambiance.ogg', 'cafe.ogg')
fire = download('https://upload.wikimedia.org/wikipedia/commons/d/d8/Dry_grass_burning_in_open_fireplace.ogg', 'fireplace.ogg')

import imageio_ffmpeg, subprocess
def decode(path):
    command = [imageio_ffmpeg.get_ffmpeg_exe(), '-v', 'error', '-i', str(path), '-t', '65', '-f', 'f32le', '-ac', '1', '-ar', '44100', '-']
    return np.frombuffer(subprocess.check_output(command), dtype='<f4').copy()

for name, source in [('rain', rain), ('cafe', cafe), ('fireplace', fire)]:
    x = decode(source)
    # Diffuse the café's speech-band detail; retain room murmur and clinking texture.
    if name == 'cafe':
        x = signal.sosfilt(signal.butter(6, 550, fs=44100, output='sos'), x)
    if name == 'fireplace':
        x = signal.sosfilt(signal.butter(3, 4500, fs=44100, output='sos'), x)
    n = 45 * 44100
    # Short recordings are made periodic before repeating, including the fire's
    # internal join; the final 45-second boundary is handled separately below.
    if len(x) < n + 44100:
        fade = np.linspace(0, 1, 44100, endpoint=False)
        periodic = x[:-44100].copy()
        periodic[:44100] = x[-44100:] * (1-fade) + x[:44100] * fade
        x = periodic
    x = np.tile(x, 1 + (n + 44100) // len(x))[:n + 44100]
    # One second of overlap, linear weights preserve the noise bed's amplitude.
    ramp = np.linspace(0, 1, 44100, endpoint=False)
    x[:44100] = x[n:n+44100] * (1-ramp) + x[:44100] * ramp
    x = x[:n]
    x -= np.mean(x)
    rms = np.sqrt(np.mean(x*x))
    x *= .1 / max(rms, 1e-8)
    x = .65 * np.tanh(x / .65)
    data = (x * 32767).astype('<i2').tobytes()
    for folder in [ROOT/'shared/src/androidMain/assets/soundscapes', ROOT/'shared/src/desktopMain/resources/soundscapes']:
        folder.mkdir(parents=True, exist_ok=True)
        with wave.open(str(folder / (name + '.wav')), 'wb') as output:
            output.setnchannels(1); output.setsampwidth(2); output.setframerate(44100); output.writeframes(data)
    print(name, len(data), 'bytes')
