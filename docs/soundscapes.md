# Bundled reading soundscapes

All playback is local. The three 45-second mono PCM loops are prepared with
`python scripts/prepare_soundscapes.py`. The script normalizes RMS levels, limits
peaks and crossfades loop boundaries. Café speech-band detail is low-pass filtered.
Preparation dependencies: numpy, scipy and imageio-ffmpeg. Playback needs none of them.

| Asset | Original author and source | Licence |
| --- | --- | --- |
| Rain | Ylmir, [Rain (loopable)](https://opengameart.org/content/rain-loopable) | CC0 1.0 |
| Café | Marble Toast, [Cafe ambiance](https://commons.wikimedia.org/wiki/File:Cafe_ambiance.ogg) | CC0 1.0 |
| Fireplace | ezwa, [Dry grass burning in open fireplace](https://commons.wikimedia.org/wiki/File:Dry_grass_burning_in_open_fireplace.ogg) | Public domain (PDSounds) |

Modifications: mono conversion, resampling, filtering, level matching, peak
limiting, excerpting and loop crossfade. Original recordings remain credited here.
CC0: https://creativecommons.org/publicdomain/zero/1.0/
