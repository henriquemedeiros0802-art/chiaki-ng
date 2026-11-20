// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_AUDIO_OUTPUT_H
#define CHIAKI_JNI_AUDIO_OUTPUT_H

#include <chiaki/log.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void *android_chiaki_audio_output_new(ChiakiLog *log);
void android_chiaki_audio_output_free(void *audio_output);
void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output);
void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output);

// --- Dublagem: copia do áudio do jogo (tap) e mixagem de uma segunda voz ---
void android_chiaki_audio_output_set_tap(void *audio_output, int enabled);
// Lê amostras intercaladas (canais do jogo) já decodificadas. Retorna quantas leu.
size_t android_chiaki_audio_output_tap_read(void *audio_output, int16_t *dst, size_t max_samples);
uint32_t android_chiaki_audio_output_channels(void *audio_output);
uint32_t android_chiaki_audio_output_rate(void *audio_output);
// Escreve voz mono na taxa do stream. Retorna quantas amostras coube no buffer.
size_t android_chiaki_audio_output_dub_write(void *audio_output, const int16_t *mono, size_t samples);
// Corta a dublagem com fade-out curto, descartando o que estava enfileirado até agora.
void android_chiaki_audio_output_dub_flush(void *audio_output);
// Ganhos alvo (suavizados): jogo (ducking) e dublagem.
void android_chiaki_audio_output_set_gains(void *audio_output, float game_gain, float dub_gain);

#ifdef __cplusplus
}
#endif

#endif //CHIAKI_JNI_AUDIO_OUTPUT_H
