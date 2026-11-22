# Direct + Raydiance + Lus contra Sodium + Iris + Mellow — 21/11/2026

## Pergunta

Na comparação do usuário (instâncias CurseForge, tela cheia com VSync), Sodium + Iris + Mellow fez 44 FPS e Direct + Raydiance + Lus "rápido" fez 28 FPS. Objetivo: o Direct ganhar sem perder qualidade e sem mudar o preset do Lus.

## Método

- **Mesmo ambiente para as duas pilhas:** cliente de desenvolvimento NeoForge 21.1.265, mesma JVM (`-Xmx3G`), janela oculta de 1920×1080, sem VSync.
- **Mesma cena:** o mundo do usuário `Sodium X Direct 1_9_1`, câmera parada, uma cópia nova por execução.
- **Mesmas opções:** as da instância "No Direct", com mistura de bioma 2 e distância 12. As nuvens ficam como cada instância as usa: desligadas no Direct, "fast" no Sodium.
- **Mods extras iguais nas duas pilhas:** The Fict e GeckoLib.
- **Medição igual para todas as pilhas:** o mod de teste `benchfps` (em `docs/bench/benchfps`, não distribuído) registra 3 janelas de 60 s; a primeira é aquecimento.
- **Scripts:** `docs/bench/run-case.ps1`.
- **Pilhas:**
  - Direct 1.9.1 + Raydiance 1.9.0 + Lus com as opções salvas do usuário (bloom, SSAO, sombras, raios, FXAA, nitidez, nuvens e brilho da água desligados; o Raydiance já pula o passe de bloom e a transparência DX12 nesse caso);
  - Sodium 0.8.13 + Iris 1.8.14 + Mellow 3.5 sem arquivo de opções, ou seja, os padrões do Mellow, que são o perfil FANCY e não o FAST.

## Resultado

FPS das janelas estáveis (2ª e 3ª). Execuções da linha de base alternadas: Sodium, Direct, Direct, Sodium.

| Configuração | FPS | ms por quadro (média) | p95 (ms) |
| --- | --- | --- | --- |
| Vanilla, sem Direct e sem shader | 243,6 / 248,0 | 4,1 / 4,0 | 8,8 / 8,7 |
| Sodium sem shader | 315,6 / 312,2 | 3,2 / 3,2 | 6,3 / 6,2 |
| Direct sem pack (Raydiance sem integração DX12) | 57,3 / 56,0 | 17,4 / 17,8 | 22,0 / 22,6 |
| Sodium + Iris + Mellow (1ª) | 58,5 / 58,4 | 17,1 / 17,1 | 37,4 / 36,2 |
| Sodium + Iris + Mellow (2ª) | 67,0 / 64,7 | 14,9 / 15,5 | 33,6 / 34,5 |
| Direct + Raydiance + Lus (1ª) | 28,3 / 29,1 | 35,3 / 34,4 | 41,3 / 40,1 |
| Direct + Raydiance + Lus (2ª) | 29,1 / 29,3 | 34,4 / 34,1 | 40,1 / 40,2 |

O Direct reproduz os 28 FPS do usuário. Sem VSync, o Sodium + Mellow faz 58–67 FPS nessa cena, cerca de 2× o Direct + Lus.

## Para onde vai o quadro do Direct

Linhas `Direct costs`, `Direct frame times` e `Direct world post` (`profileRenderer`), por quadro:

| Parte | Direct sem pack | Direct + Lus |
| --- | ---: | ---: |
| Composite do terreno | 13,4 ms | 11,4 ms |
| … espera da CPU pelo DX12 | 7,8–8,0 ms | 9,0 ms |
| … GPU do terreno DX12 (e cópia) | 4,4 ms | 6,9 ms (geometria do Lus) |
| Captura do mundo | — | 4,2 ms |
| Envio e espera do passe do Lus | — | 9,5 ms (GPU: passe 7,35 + importação 1,7) |
| Apresentação | — | 2,3 ms |
| Quadro inteiro | 17,6 ms | 34,2 ms |

- **Trocar o terreno vanilla pelo terreno DX12 custa cerca de 13,5 ms por quadro nessa cena** (4,0 → 17,6 ms). O terreno vanilla em OpenGL inteiro cabe nos 4 ms do quadro vanilla. A CPU passa 8 ms esperando o DX12, embora a GPU do terreno seja de 4,4 ms. O resto é a transferência WGL síncrona e o composite em tela cheia.
- O estágio de mundo do Lus soma cerca de 16 ms. Só levar a cena do OpenGL ao DX12 e de volta custa cerca de 8 ms (captura, importação e apresentação).

## Conclusão

- **O Direct sem shader nenhum já é mais lento que o Sodium com o Mellow em FANCY.** Nenhuma otimização do Lus resolve isso.
- **O piso estrutural não cabe no orçamento.** Para ganhar, o Direct + Lus precisaria caber em cerca de 15 ms. Mesmo com terreno de custo zero e sem espera nenhuma, ainda sobram: o quadro do Minecraft (~4 ms), ao menos duas transferências WGL por quadro (~2 ms cada, medido na 1.9.0 com a GPU ociosa) e o Lus (~9 ms de GPU com a importação). São cerca de 17 ms.
- **As saídas que acabariam com as esperas estão fechadas.** As docs proíbem usar a câmera do quadro anterior, e o driver Intel rejeita o semáforo de GPU (`0x500`) ([native-shader-contract.md](native-shader-contract.md), [espera-dx12-1.7.6.md](espera-dx12-1.7.6.md)).
- **O limite é a arquitetura híbrida OpenGL/DX12 por quadro, não um detalhe de implementação.** Otimizações incrementais (por exemplo, framebuffer principal sobre a textura compartilhada, ou shader do Lus mais barato com a mesma imagem) reduzem o quadro, mas o teto estimado fica abaixo do Sodium + Mellow nessa máquina.

## Otimizações tentadas depois da linha de base (22/11/2026)

Cada uma foi medida com execuções alternadas no mesmo harness. Ficaria só o que melhorasse o FPS sem mudar a imagem.

| Tentativa | Resultado medido | Decisão |
| --- | --- | --- |
| Manter o lock WGL depois do composite do terreno até o próximo uso pelo D3D12, em todo quadro (antes só com pack de mundo). Instrumentação nova: o unlock logo após o composite custava cerca de 3 ms de CPU | Composite de 13,3 → 8,3 ms de CPU por quadro. FPS sem pack: 57,5 → 57,7 (média de 4 janelas cada). Com Lus: 28,0 → 27,9 | Revertida: o quadro é limitado pela GPU, e a espera só mudou de lugar |
| Lus: pular termos noturnos e de chuva quando o fator do quadro é zero, e calcular o raio do céu só nos pixels de céu | Passe do Lus 7,63 → 7,57 ms de GPU (dia), 7,64 → 7,50 (noite), 7,64 → 7,56–7,82 (chuva). Até 41 canais com diferença de 1/255 | Descartada: o fator noturno quase nunca é zero de dia (depende de 1 − luz do dia) |

Ferramenta nova, `LusPassBench` (`docs/terrain-benchmark.gradle`, tarefa `lusPassBench`): roda os passes de mundo reais preparados pelo Raydiance numa cena 1080p fixa (`LUS_BENCH_SCENE=day|night|rain`). Mede o tempo de GPU e compara a imagem byte a byte entre conjuntos de fontes. Ela reproduz o jogo: 7,6 ms no passe do Lus e 1,7 ms de importação.

Distribuição medida do passe do Lus com as opções do usuário (variantes só de diagnóstico):

| Variante | GPU do passe |
| --- | ---: |
| Lus atual | 7,6 ms |
| Sem a paleta | 4,5 ms |
| Sem a atmosfera | 5,2 ms |
| Passe trivial (mesmas leituras, escrita e cópia de saída) | 1,8 ms |

Cada trecho da paleta (contraste, ombro, saturação, ajuste noturno) custa de 0,45 a 0,65 ms. Não há uma instrução isolada cara: o custo é aritmética por pixel que aparece na imagem.

Quebra do quadro do Direct sem pack, com a instrumentação nova (`Direct frame split`):

| Parte | ms por quadro |
| --- | ---: |
| `renderLevel` | 14,9 |
| … lock do composite | 2,2 |
| … unlock do composite | 3,1 |
| … espera pelo DX12 | 8,0 |
| Fora do `renderLevel` (mão, GUI, swap) | 2,5 |

A importação de 1,7 ms do estágio de mundo é o `ReleaseWrappedResources` + `Flush` do D3D11On12, tempo de GPU fixo da ponte.

## Limites

- Uma cena, uma máquina (Intel UHD 630, i3-10105), janela oculta sem VSync.
- O Mellow foi medido no padrão (FANCY), como o usuário rodou. O perfil FAST seria ainda mais rápido.
- As estimativas do piso usam medições das docs 1.9.0 e não um protótipo.
- Na sessão o tempo de GPU variou até 30% entre execuções, e numa delas tudo ficou cerca de 30% mais rápido. Por isso toda comparação precisa de execuções intercaladas, e o resultado de uma execução isolada não vale.
- No jogo, com VSync a 60 Hz, o Sodium sem shader fica limitado a cerca de 60 FPS. O harness desliga o VSync para medir a capacidade real de cada pilha.
