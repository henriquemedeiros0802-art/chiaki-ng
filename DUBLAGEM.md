# Dublagem de legendas no chiaki-ng

O app lê a legenda (já em português) na tela do PS5, descobre **quem está falando** pelo áudio do jogo e
fala a legenda em português com a voz daquele personagem (clonada a partir do próprio áudio do jogo).
Quando a legenda some, a voz é cortada com um fade-out de 80 ms.

```
PS5 ──Remote Play──► app (cliente Chiaki próprio)
                      ├─ vídeo ► recorte da legenda ► OCR (ML Kit, no aparelho) ► "legenda apareceu / sumiu"
                      ├─ áudio ► PCM 16 kHz mono ──────────────┐ (contínuo)
                      └─ voz dublada ◄── PCM em streaming ◄────┤
                                                               ▼
                                        servidor (FastAPI): VAD + identificação de falante
                                        + clonagem (ElevenLabs) + TTS em streaming
```

Como o app é o próprio cliente do Remote Play, ele recebe o vídeo e o áudio **já decodificados**, então não
há captura de tela nem de áudio do sistema (que o app oficial bloqueia).

## 0. Teste grátis (sem servidor, sem conta)

Em **Ajustes → Dublagem de legendas → Voz da dublagem** deixe "Voz do Android" (é o padrão). O app lê a legenda
e fala com a voz em português do próprio Android, e corta a fala quando a legenda some. Não identifica o
personagem: é a mesma voz para todos. Precisa da voz em português instalada
(Ajustes do Android → Idioma → Saída de texto para fala). Só o item 2 (App Android) abaixo é necessário.

## 1. Servidor (voz clonada do personagem)

```bash
cd dubbing-server
cp .env.example .env            # preencha ELEVENLABS_API_KEY e DUB_TOKEN
pip install -r requirements.txt
set -a; . ./.env; set +a
python -m dubserver.app         # escuta em 0.0.0.0:8765
```

- Teste a chave e a latência real: `python selftest.py "Olá, tudo bem?"` (gera `selftest.wav`).
- Sem chave, para testar o app: `DUB_TTS=fake python -m dubserver.app` (responde com bipes).
- Identificação de falante muito melhor (recomendado): `pip install resemblyzer` (usa PyTorch).
  Sem ele, o servidor usa um método simples que separa bem vozes bem diferentes (grave/aguda), mas erra mais.
- Docker: `docker build -t dublagem dubbing-server && docker run -p 8765:8765 --env-file dubbing-server/.env -v dublagem-data:/data dublagem`
- Fora da sua rede local use HTTPS/WSS (proxy reverso como Caddy ou nginx) e um `DUB_TOKEN` longo.

Testes: `cd dubbing-server && pip install -r requirements-dev.txt && pytest`.

## 2. App Android

1. Compile como o Chiaki normal (Android Studio, abrir a pasta `android/`). Exige Android 8.0+ para a dublagem.
2. Registre o PS5 e conecte como de costume.
3. **Ajustes → Dublagem de legendas**: endereço do servidor (ex.: `192.168.0.10:8765`), token e nome do jogo.
4. No stream, ligue o botão **Dublagem** (aparece com a barra de controles, puxando a borda da tela).
5. **Calibre a área da legenda**: em Ajustes ligue "Mostrar a área da legenda" e ajuste esquerda/topo/largura/altura
   até a moldura azul cobrir só a legenda. O texto no canto superior esquerdo mostra o estado, o falante
   (S1, S2…), se a voz já foi clonada e a latência do servidor.

### Como a voz do personagem é obtida
Enquanto a legenda de um falante está na tela, o servidor guarda só os trechos com fala daquele falante.
Com ~12 s acumulados ele cria a voz clonada (ElevenLabs) e passa a usá-la; com ~40 s ele recria com mais material.
Até lá, usa uma voz padrão masculina/feminina escolhida pelo tom. As vozes ficam salvas por jogo
(`DUB_DATA`), então nas próximas sessões já começam clonadas.

### Reduzindo o atraso
- Ajustes → "Leituras para confirmar a legenda": **1** começa antes (risco de falar texto pela metade se o jogo
  "digita" a legenda); 2 é o padrão.
- "Intervalo de leitura": 60–100 ms.
- Servidor perto de você (mesma rede ou região próxima) e o modelo `eleven_flash_v2_5` (padrão).
- Em `DUB_SPEED` (1.0–1.2) dá para falar mais rápido quando as frases em português saem mais longas que a fala original.

## 3. Limitações conhecidas
- **iOS: não implementado.** O código atual é Android. O núcleo do Chiaki (`lib/`) e o servidor servem aos dois,
  mas o iOS precisa de um app novo (decodificação com VideoToolbox, OCR com Vision) e, por ser GPL, não
  poderia ir para a App Store.
- Música alta e efeitos sobre a voz pioram a identificação do falante e a qualidade da clonagem.
- A clonagem de voz depende de plano que permita "Instant Voice Cloning" no ElevenLabs.
- Legenda que não é branca: mude "Cor da legenda" para "Qualquer cor" (usa mais bateria).
- O Remote Play oficial e este cliente não podem estar conectados ao mesmo PS5 ao mesmo tempo.
- Usar um cliente não oficial vai contra os termos de uso da Sony; uso pessoal, por sua conta e risco.
