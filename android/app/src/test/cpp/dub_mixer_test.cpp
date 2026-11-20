// Teste no PC da mixagem de dublagem: g++ -std=c++17 -I../../main/cpp dub_mixer_test.cpp -o /tmp/dub_mixer_test && /tmp/dub_mixer_test
#include "dub-mixer.hpp"

#include <cstdio>
#include <cstdlib>
#include <vector>

#define CHECK(cond) do { if(!(cond)) { std::printf("FALHOU %s:%d: %s\n", __FILE__, __LINE__, #cond); std::exit(1); } } while(0)

static const float SR = 48000.0f;
static const int CH = 2;

static std::vector<int16_t> game(int frames, int16_t level)
{
	return std::vector<int16_t>(frames * CH, level);
}

static void run(DubMixer &m, std::vector<int16_t> &io)
{
	m.Process(io.data(), static_cast<int32_t>(io.size() / CH), CH, SR);
}

int main()
{
	// 1) Sem dublagem, o jogo passa intacto (ganho 1).
	{
		DubMixer m;
		auto io = game(480, 1000);
		run(m, io);
		for(auto v : io) CHECK(v == 1000);
	}

	// 2) Dublagem mono é somada nos dois canais.
	{
		DubMixer m;
		std::vector<int16_t> dub(480, 2000);
		CHECK(m.dub.Write(dub.data(), dub.size()) == 480);
		auto io = game(480, 1000);
		run(m, io);
		CHECK(io[CH * 400] == 3000 && io[CH * 400 + 1] == 3000);
	}

	// 3) Ducking: ganho do jogo cai suavemente para o alvo.
	{
		DubMixer m;
		m.game_gain_target = 0.25f;
		auto first = game(48, 1000);
		run(m, first);
		CHECK(first[0] > 900);                       // ainda não caiu de uma vez (sem estalo)
		for(int i = 0; i < 20; i++) { auto b = game(480, 1000); run(m, b); }
		auto last = game(480, 1000);
		run(m, last);
		CHECK(last.back() < 270 && last.back() > 240);   // ≈ 250
	}

	// 4) Saturação não dá overflow.
	{
		DubMixer m;
		std::vector<int16_t> dub(480, 30000);
		m.dub.Write(dub.data(), dub.size());
		auto io = game(480, 30000);
		run(m, io);
		CHECK(io[CH * 100] == 32767);
	}

	// 5) Flush: fade-out em ~80 ms e o resto enfileirado é descartado (não toca depois).
	{
		DubMixer m;
		std::vector<int16_t> dub(48000, 8000);              // 1 s de voz enfileirada
		CHECK(m.dub.Write(dub.data(), dub.size()) == 48000);
		auto warm = game(480, 0);
		run(m, warm);
		CHECK(warm[CH * 400] > 7000);                       // tocando
		m.Flush();
		int silent_after = -1;
		int total = 0;
		int16_t prev = 8000;
		bool monotonic = true;
		for(int b = 0; b < 12; b++)                          // 12 x 10 ms = 120 ms
		{
			auto io = game(480, 0);
			run(m, io);
			for(int i = 0; i < 480; i++)
			{
				int16_t v = io[i * CH];
				if(v > prev + 2) monotonic = false;          // nunca sobe durante o fade
				prev = v;
				if(v == 0 && silent_after < 0) silent_after = total;
				total++;
			}
		}
		CHECK(monotonic);
		CHECK(silent_after > 0 && silent_after < static_cast<int>(0.1f * SR));   // silêncio em < 100 ms
		// Depois do corte, o que sobrou na fila foi descartado: continua mudo por bastante tempo.
		for(int b = 0; b < 30; b++)
		{
			auto io = game(480, 0);
			run(m, io);
			for(auto v : io) CHECK(v == 0);
		}
	}

	// 6) Fala nova logo depois do flush é preservada (só o que veio antes do corte é descartado).
	{
		DubMixer m;
		std::vector<int16_t> old(48000, 8000);
		m.dub.Write(old.data(), old.size());
		m.Flush();
		std::vector<int16_t> fresh(4800, 5000);
		m.dub.Write(fresh.data(), fresh.size());
		long loud = 0;
		for(int b = 0; b < 100; b++)                         // 1 s
		{
			auto io = game(480, 0);
			run(m, io);
			for(int i = 0; i < 480; i++) if(io[i * CH] > 4000) loud++;
		}
		CHECK(loud > 2000);                                  // a fala nova toca
	}

	// 7) Tap só grava quando habilitado; leitura parcial funciona.
	{
		DubMixer m;
		std::vector<int16_t> s(100, 7);
		m.OnGameAudio(s.data(), s.size());
		int16_t out[200];
		CHECK(m.tap.Read(out, 200) == 0);
		m.tap_enabled = true;
		m.OnGameAudio(s.data(), s.size());
		CHECK(m.tap.Read(out, 60) == 60);
		CHECK(m.tap.Read(out, 200) == 40);
	}

	// 8) Ring cheio recusa o excedente (o Kotlin retém o resto).
	{
		DubMixer m;
		std::vector<int16_t> big(DubMixer::DUB_RING_SAMPLES + 500, 1);
		CHECK(m.dub.Write(big.data(), big.size()) == DubMixer::DUB_RING_SAMPLES);
	}

	std::printf("OK: todos os testes da mixagem passaram\n");
	return 0;
}
