// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
//
// Mixagem da dublagem sobre o áudio do jogo, sem dependências de Android (testável no PC).

#ifndef CHIAKI_JNI_DUB_MIXER_HPP
#define CHIAKI_JNI_DUB_MIXER_HPP

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

/**
 * Fila SPSC (um produtor, um consumidor) de int16, sem travas, que entrega amostras parciais
 * imediatamente (ao contrário do CircularBuffer, que só entrega blocos completos).
 */
class SampleRing
{
private:
	std::vector<int16_t> data;
	size_t mask;
	std::atomic<uint64_t> written{0};
	std::atomic<uint64_t> consumed{0};

public:
	explicit SampleRing(size_t capacity_pow2) : data(capacity_pow2), mask(capacity_pow2 - 1) {}

	size_t Write(const int16_t *src, size_t n)
	{
		uint64_t w = written.load(std::memory_order_relaxed);
		uint64_t r = consumed.load(std::memory_order_acquire);
		size_t space = data.size() - static_cast<size_t>(w - r);
		if(n > space)
			n = space;
		for(size_t i = 0; i < n; i++)
			data[(w + i) & mask] = src[i];
		written.store(w + n, std::memory_order_release);
		return n;
	}

	size_t Read(int16_t *dst, size_t n)
	{
		uint64_t r = consumed.load(std::memory_order_relaxed);
		uint64_t w = written.load(std::memory_order_acquire);
		size_t avail = static_cast<size_t>(w - r);
		if(n > avail)
			n = avail;
		for(size_t i = 0; i < n; i++)
			dst[i] = data[(r + i) & mask];
		consumed.store(r + n, std::memory_order_release);
		return n;
	}

	uint64_t TotalWritten() const { return written.load(std::memory_order_acquire); }
	uint64_t TotalConsumed() const { return consumed.load(std::memory_order_acquire); }

	// Só o consumidor pode chamar.
	size_t Discard(size_t n)
	{
		uint64_t r = consumed.load(std::memory_order_relaxed);
		uint64_t w = written.load(std::memory_order_acquire);
		size_t avail = static_cast<size_t>(w - r);
		if(n > avail)
			n = avail;
		consumed.store(r + n, std::memory_order_release);
		return n;
	}
};

class DubMixer
{
public:
	static constexpr size_t DUB_RING_SAMPLES = 1u << 17;   // ~2,7 s mono a 48 kHz
	static constexpr size_t TAP_RING_SAMPLES = 1u << 17;
	static constexpr float FADE_OUT_SECONDS = 0.08f;
	static constexpr float FADE_IN_SECONDS = 0.01f;
	static constexpr float GAIN_SMOOTH_SECONDS = 0.025f;
	static constexpr int32_t CHUNK_FRAMES = 1024;

	std::atomic<bool> tap_enabled{false};
	SampleRing tap{TAP_RING_SAMPLES};
	SampleRing dub{DUB_RING_SAMPLES};
	std::atomic<uint64_t> flush_until{0};
	std::atomic<float> game_gain_target{1.0f};
	std::atomic<float> dub_gain_target{1.0f};

	// Chamado na thread do decoder com o áudio original (intercalado).
	void OnGameAudio(const int16_t *samples, size_t count)
	{
		if(tap_enabled.load(std::memory_order_relaxed))
			tap.Write(samples, count);
	}

	void Flush() { flush_until.store(dub.TotalWritten(), std::memory_order_release); }

	/**
	 * Chamado no callback de áudio. `io` já contém o áudio do jogo (intercalado, `channels` canais);
	 * mistura a dublagem por cima e aplica o ganho (ducking) ao jogo.
	 */
	void Process(int16_t *io, int32_t frames, int32_t channels, float sample_rate)
	{
		const float smooth = 1.0f - std::exp(-1.0f / (sample_rate * GAIN_SMOOTH_SECONDS));
		const float fade_down = 1.0f / (sample_rate * FADE_OUT_SECONDS);
		const float fade_up = 1.0f / (sample_rate * FADE_IN_SECONDS);
		const float game_t = game_gain_target.load(std::memory_order_relaxed);
		const float dub_t = dub_gain_target.load(std::memory_order_relaxed);

		int32_t done = 0;
		while(done < frames)
		{
			int32_t n = frames - done;
			if(n > CHUNK_FRAMES)
				n = CHUNK_FRAMES;

			size_t got = dub.Read(scratch, static_cast<size_t>(n));
			if(got < static_cast<size_t>(n))
				memset(scratch + got, 0, (static_cast<size_t>(n) - got) * sizeof(int16_t));

			// Corte pedido ainda não concluído? Então o fade-out é aplicado amostra a amostra.
			const uint64_t flush_target = flush_until.load(std::memory_order_acquire);
			const bool cutting = dub.TotalConsumed() < flush_target;

			for(int32_t i = 0; i < n; i++)
			{
				game_gain_cur += (game_t - game_gain_cur) * smooth;
				dub_gain_cur += (dub_t - dub_gain_cur) * smooth;
				if(cutting)
					fade_cur = fade_cur > fade_down ? fade_cur - fade_down : 0.0f;
				else
					fade_cur = fade_cur + fade_up < 1.0f ? fade_cur + fade_up : 1.0f;

				float v = static_cast<float>(scratch[i]) * dub_gain_cur * fade_cur;
				int16_t *frame = io + static_cast<size_t>(done + i) * channels;
				for(int32_t c = 0; c < channels; c++)
					frame[c] = Clip(static_cast<float>(frame[c]) * game_gain_cur + v);
			}

			// Fade chegou a zero: joga fora tudo que foi enfileirado até o pedido de corte.
			if(cutting && fade_cur <= 0.0f)
			{
				uint64_t consumed = dub.TotalConsumed();
				if(consumed < flush_target)
					dub.Discard(static_cast<size_t>(flush_target - consumed));
			}

			done += n;
		}
	}

private:
	// Estado só do callback de áudio:
	float game_gain_cur = 1.0f;
	float dub_gain_cur = 1.0f;
	float fade_cur = 1.0f;
	int16_t scratch[CHUNK_FRAMES];

	static int16_t Clip(float v)
	{
		if(v > 32767.0f) return 32767;
		if(v < -32768.0f) return -32768;
		return static_cast<int16_t>(v);
	}
};

#endif // CHIAKI_JNI_DUB_MIXER_HPP
