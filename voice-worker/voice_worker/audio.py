"""Audio helpers: PCM frame packing, silence detection and WAV writing."""
import audioop
import wave

from livekit import rtc


def pcm_to_frame(pcm_s16le: bytes, sample_rate: int = 48000, channels: int = 1) -> rtc.AudioFrame:
    count = len(pcm_s16le) // (2 * channels)
    return rtc.AudioFrame(data=pcm_s16le, sample_rate=sample_rate,
                          num_channels=channels, samples_per_channel=count)


def wav_bytes(pcm_s16le: bytes, sample_rate: int, channels: int = 1) -> bytes:
    import io
    out = io.BytesIO()
    with wave.open(out, 'wb') as wav:
        wav.setnchannels(channels)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(pcm_s16le)
    return out.getvalue()


def rms(pcm_s16le: bytes) -> float:
    if not pcm_s16le:
        return 0.0
    return audioop.rms(pcm_s16le, 2)


def load_wav_16k(path: str) -> bytes:
    """Read a WAV file as 16 kHz mono PCM16 for ASR fixtures."""
    with wave.open(path, 'rb') as wav:
        pcm = wav.readframes(wav.getnframes())
        rate, channels, width = wav.getframerate(), wav.getnchannels(), wav.getsampwidth()
    if width != 2:
        pcm = audioop.lin2lin(pcm, width, 2)
    if channels != 1:
        pcm = audioop.tomono(pcm, 2, 0.5, 0.5)
    if rate != 16000:
        pcm, _ = audioop.ratecv(pcm, 2, 1, rate, 16000, None)
    return pcm
