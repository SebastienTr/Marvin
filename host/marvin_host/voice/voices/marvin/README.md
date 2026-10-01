<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# Marvin's voice

`reference.wav` is the voice Marvin speaks with when the speech synthesis is Qwen3-TTS (docs/voice.md,
"Marvin's voice"): "Malicieux", the one the owner chose among a few candidates. `reference.txt` is
exactly what it says: a French sentence, then an English one, so the voice is cloned with both
languages in it.

- **Made with** Qwen3-TTS VoiceDesign (`Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign`, Apache-2.0): a voice
  described in words, synthesised once. It is not anybody's recorded voice.
- **Used by** Qwen3-TTS Base (`Qwen3-TTS-12Hz-0.6B-Base`, or 1.7B), which clones it in context: the
  reference's speech codes and text lead every sentence Marvin says
  (host/marvin_host/voice/qwen_tts.py). The encoded reference is made on this computer at the first
  use and cached in `~/.local/share/marvin/voices/` (it depends on the model and the package versions,
  so it is not kept here).
- **Format**: 24 kHz mono 16-bit WAV, 11.4 s (545 kB). To change the voice, replace both files (the
  transcript must match the recording word for word); another folder here is another voice id.
- **Licence**: CC BY 4.0, like the repository's other media.
