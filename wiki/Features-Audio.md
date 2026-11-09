# Audio

> Public reference: DSP tests do not verify the physical microphone/speaker path. Presence, successful replies and physical effects are
> separate claims. Check [revision-specific evidence](https://github.com/1vcian/Brucia/blob/main/TESTS.md)
> and [Tutorials](Tutorials.md) before relying on a workflow.

Every tool built on the onboard microphone and I2S speaker. Driven by two firmware
streams: a continuous raw-PCM mic stream (`mic.pcm`, 16 kHz mono) and streamed PCM speaker
playback (`spkr_pcm_begin` / `spkr_pcm_data` / `spkr_pcm_stop`). All the DSP runs on the
phone; the device just captures and plays. Gated on `caps.mic` (input) and `caps.speaker`
(output).

## Audio spectrum
Live FFT frequency bars computed from the device mic stream.

## Waterfall
A scrolling time x frequency spectrogram, drawn from the audio over time.

## SSTV decode and transmit
Decodes received audio into an image (Robot36, Robot72, Martin M1/M2, Scottie S1/S2) and
transmits a chosen image out the speaker (Robot36 and more).

## Tone / DTMF tool
Detects the dominant frequency from the live mic, generates tones through the device speaker,
and has a full DTMF keypad and decoder:

- **DTMF keypad** - tap a key and the device synthesises the dual tone straight to its I2S
  speaker (full-scale, the gain carries the volume, exactly like the RTTTL song path), so it is
  as loud and audible as the songs. The sound comes out of the **ESP**, not the phone.
- **DTMF decoder** - press **Start listening** to put the ESP mic in listening mode; the PCM is
  streamed to the phone, which runs a Goertzel decoder and shows the keys it hears live on screen,
  with a mic-level meter. Press **Stop** to end. Mic in on the device, decode and display on the
  phone; a short silence gap separates repeated presses of the same key. Hold an **external** DTMF
  source (another phone, a call) near the device mic - the device cannot decode its own keypad,
  because the mic and speaker are half-duplex (the mic only runs while listening, so the speaker
  tools are free otherwise).

## Oscilloscope
A live time-domain waveform with an edge trigger, adjustable time base and gain, and Vpp
/ RMS / frequency readouts.

## Song player
Plays meme RTTTL melodies natively on the device, now running in the background so
**Stop** actually interrupts them. Speaker volume is controllable from the phone.

## Composer
Build your own melody note-by-note in the Tone tool: pick a note length and octave, tap
notes (and rests), set the tempo, then play the tune on the device speaker. It is assembled
on the phone into an RTTTL string and sent to the same background player - no new firmware.
Composed songs can be named and saved; they survive app restarts and appear under "My songs"
for one-tap replay.

## Recording
Per-tool recording captures a WAV plus spectrum/waterfall/oscilloscope PNGs for later
review, and the Tools page's microphone recorder (`mic_record`) saves a WAV straight to
the SD card.

---
Back to [Features](Features.md).
