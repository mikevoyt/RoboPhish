# Offline playback fixture

`offline-tone.mp3` is an original, generated 12-second stereo sine wave, encoded as
variable-bitrate MP3 at 44.1 kHz. It contains no commercial recording or API data.
The offline suite uses it to exercise MP3 decoding, seeking and queue transitions;
the transfer suite checks byte-for-byte persistence through DownloadManager.

Reproduce with FFmpeg and libmp3lame:

```sh
ffmpeg -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=12' \
  -ac 2 -codec:a libmp3lame -q:a 9 offline-tone.mp3
```
