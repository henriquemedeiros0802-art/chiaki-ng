import numpy as np

from dubserver.audio import EnergyVAD, estimate_f0, gender_from_f0, voiced_samples
from dubserver.speakers import Registry, SpectralEmbedder

from synth import SR, silence, voice_a, voice_b


def test_vad_finds_speech_between_silences():
    x = np.concatenate([silence(0.5), voice_a(seconds=0.6), silence(0.5)])
    mask = EnergyVAD().process(x)
    t = np.flatnonzero(mask) * 0.02
    assert 0.4 < t.min() < 0.6
    assert 1.0 < t.max() < 1.25


def test_voiced_samples_drops_silence():
    x = np.concatenate([silence(1.0), voice_a(seconds=0.8)])
    v = voiced_samples(x)
    assert 0.7 * SR < len(v) < 1.0 * SR


def test_f0_and_gender():
    assert abs(estimate_f0(voice_a()) - 110) < 8
    assert abs(estimate_f0(voice_b()) - 230) < 15
    assert gender_from_f0(110) == "male"
    assert gender_from_f0(230) == "female"
    assert gender_from_f0(0) == "neutral"


def test_registry_separates_and_reuses_speakers():
    reg = Registry(SpectralEmbedder(), None)
    a1 = reg.identify(voice_a(seed=1))
    b1 = reg.identify(voice_b(seed=2))
    a2 = reg.identify(voice_a(seed=3))
    b2 = reg.identify(voice_b(seed=4))
    assert a1 is not b1
    assert a1 is a2
    assert b1 is b2
    assert a1.gender == "male" and b1.gender == "female"
    assert len(reg.profiles) == 2


def test_too_short_audio_is_not_identified():
    reg = Registry(SpectralEmbedder(), None)
    assert reg.identify(voice_a(seconds=0.15)) is None


def test_registry_persists(tmp_path):
    reg = Registry(SpectralEmbedder(), tmp_path / "g")
    a = reg.identify(voice_a(seed=1))
    a.voice_id = "v1"
    a.add_samples(voice_a(seed=1), 60)
    reg.save()
    reg2 = Registry(SpectralEmbedder(), tmp_path / "g")
    assert len(reg2.profiles) == 1
    assert reg2.profiles[0].voice_id == "v1"
    assert reg2.profiles[0].seconds > 1.0
    assert reg2.identify(voice_a(seed=9)) is reg2.profiles[0]
