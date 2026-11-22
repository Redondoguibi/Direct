# Direct 1.9.0 — desempenho com shaderpacks (Lus no preset Desempenho)

## Evidência no jogo antes da mudança

Sessão de 21/11/2026 (15:15–16:04, instância Direct), Direct 1.8.2 + Raydiance 1.8.1 + Lus no preset Desempenho, Intel UHD 630, i3-10105, 1920×1080, tela cheia, VSync ligado, distância 5. Valores por minuto do `latest.log`:

| Medida | Faixa observada |
| --- | --- |
| FPS | 26,4–31,5 (p50 31,8–36,6 ms) |
| Composição do terreno por quadro | 12,0–13,7 ms, dos quais 7,9–9,6 ms de espera pelo DX12 |
| GPU do terreno DX12 + cópia | 5,5–7,1 ms |
| Pós de mundo: captura | 5,4–6,0 ms |
| Pós de mundo: envio e espera | 6,2–10,8 ms |
| Pós de mundo: apresentação | 1,8–2,2 ms |

Cerca de 30 dos ~35 ms de cada quadro eram o render thread esperando: a GPU e a CPU se revezavam em vez de trabalhar em paralelo.

## Medição isolada

`WorldPostBenchmark` reproduz um quadro inteiro sem o Minecraft: céu OpenGL, terreno DX12 real com o compositor, desenhos OpenGL de "entidades" intercalados com trabalho de CPU, o pós de mundo com as fontes **reais** do Lus preparadas pelo Raydiance (`PackSourceDumpTest`) e uma cauda de CPU (mão, GUI, tick). Janela GL oculta, 1920×1080, GPU livre (o jogo fechado), fases alternadas, 30 quadros de aquecimento e 120 amostras. Cada execução também verifica os pixels apresentados contra uma referência sincronizada pela CPU. A carga de GPU do sintético é maior que a do jogo (6 camadas de terreno sobrepostas), então os tempos absolutos não são os do jogo.

### O que custa o quê

| Sondagem (sintético) | Resultado |
| --- | --- |
| `wglDXLockObjectsNV` depois de enviar DX12, sem espera explícita | bloqueia a CPU até o DX12 terminar (`pendingAfterWglLock=0`); a espera explícita não é o problema |
| Lock de imagem **adquirida** pelo D3D11On12, GPU ociosa | 1,9–2,0 ms por chamada, para cor, profundidade ou ambas |
| Lock da mesma imagem **liberada** ao D3D12 | 0,03 ms — mas o OpenGL passa a ler conteúdo antigo (verificação por pixel falhou): o lock é a transferência entre as APIs e não pode ser pulado |
| Lock de entrada da captura do mundo | 3,6–3,9 ms |
| Importação da profundidade (cópia D3D11) | ~3,0 ms de GPU |
| Composite do Lus (Desempenho) | 8,5 ms de GPU; passe trivial com os mesmos bindings: 1,4 ms |
| … sem a paleta / sem a atmosfera / sem a vinheta | 4,5 / 5,4 / 7,9 ms |
| … sem o vertex shader de tela cheia | 10,7 ms |
| … com `min16float` na paleta / constantes por cbuffer em vez de interpolantes | sem ganho |
| Terreno sem geometria de pack / com a geometria do Lus | 5,5 / 10,3 ms de GPU |

O shader do Lus é limitado por ALU nesta GPU; não há ganho grande sem alterar a imagem, então o pack não foi modificado. Os ganhos vieram da estrutura do Direct.

## Mudanças

1. **Lock mantido do composite à captura.** Quando um pack de mundo rodou no quadro anterior, o composite do terreno trava cor e profundidade em modo leitura/escrita e só as libera na captura do mundo (ou da translucidez DX12). O D3D12 não usa essas imagens nesse intervalo. É um lock WGL — uma transferência síncrona — a menos por quadro. Todo envio D3D12, resize, falha e desligamento libera um lock pendente antes.
2. **Ponte de profundidade D3D12.** Uma textura R32F do D3D12, registrada no WGL ao lado da cor compartilhada, substitui as cópias D3D11 de profundidade: a exportação do terreno e a importação da translucidez viram cópias D3D12, e a etapa de mundo amostra a profundidade do OpenGL direto, sem importação. A posse continua passando pelo D3D11On12, como na cor. Se o driver recusar, volta sozinho à profundidade copiada. **Avançado > Compartilhamento nativo**: Cor e profundidade (padrão) / Cor / Desligado; o preset Compatibilidade usa Desligado.
3. **Programas de terreno enxutos para packs.** Os programas de geometria do pack também são compilados sem os efeitos internos do Direct (ondas, vento, sombras, materiais, água). Essa variante só é escolhida, quadro a quadro, enquanto todos esses efeitos estão inativos, então a imagem é a mesma. A compilação roda em paralelo.
4. **Tempos de GPU do pós de mundo** no log: `Direct world post: ..., gpuImportMs, gpuPassesMs, heldCompositeLocks, depthBridge`.
5. **Grafo de mundo (API 1.7)**: buffers do pack, saídas múltiplas, histórico, compute, profundidade só do terreno e até 16 passes. Ver [grafo-de-mundo-1.9.md](grafo-de-mundo-1.9.md).

## Resultado no benchmark isolado

DLL e fluxo da 1.8.2 contra DLL e fluxo da 1.9.0, processos alternados antigo/novo/novo/antigo, p50 por fase:

| Quadro sintético | 1.8.2 | 1.9.0 |
| --- | ---: | ---: |
| Com o pós do Lus (Desempenho) | 49,7 ms | 41,0 ms (−17%) |
| Sem pós de mundo, com a geometria do Lus | 26,2 ms | 23,7 ms (−9%) |
| Espera do terreno | 10,8 ms | 8,8 ms |
| Composite do terreno | 4,2 ms | 2,4 ms |
| Captura do mundo | 10,5 ms | 7,2 ms |
| Envio e espera do mundo | 11,6 ms | 10,2 ms |
| GPU do terreno | 9,9 ms | 7,7 ms |

Os pixels e a profundidade apresentados são idênticos entre as duas versões em todos os quadros verificados. Testes: 60 unitários, 53 gráficos (incluindo as variantes com a ponte de profundidade e o grafo de mundo) e o smoke nativo passam.

## Alternativas rejeitadas

- Pular a sincronização do D3D11On12 deixando as imagens com o D3D12: rápido (40 → 23 ms), mas o OpenGL lê conteúdo antigo.
- `min16float` na paleta do Lus: sem ganho e com diferenças de 1/255.
- Mover as constantes por quadro do Lus para o cbuffer: sem ganho.

## Limites

São medidas sintéticas. O ganho em jogo depende de quanto do quadro é sincronização (a maior parte, pelo log acima) e não é uma estimativa de FPS. A espera pelo terreno DX12 e o custo de ALU do Lus continuam.

## Como validar no jogo

Mesma cena, câmera e opções, 60 s estáveis por modo, comparando as linhas `Direct frame times` e `Direct world post` (ou `logs/direct-performance.csv`):

1. Direct 1.9.0 + Raydiance 1.9.0 + Lus Desempenho com **Compartilhamento nativo: Cor e profundidade**, e depois **Cor**. Confira `depthBridge=true` e `heldCompositeLocks` próximo de `frames`.
2. A mesma cena com o Direct 1.8.2, para o antes.
3. Visual: água, entidades na frente e atrás do terreno, translucidez, troca de dimensão, redimensionar a janela e alternar tela cheia.

Reprodução sem o Minecraft aberto (exporte antes as fontes do Lus com `RAYDIANCE_DUMP_DIR` no `PackSourceDumpTest` do Raydiance):

```powershell
$env:BENCH_BRIDGE="1"
.\gradlew.bat --init-script docs/terrain-benchmark.gradle worldPostBenchmark '-PbenchmarkLibrary=C:\Criando Mods\mod\Direct\build\generated\nativeResources\META-INF\native\windows-x86_64\direct_renderer.dll' '-PbenchmarkPack=<pasta exportada>' -PbenchmarkModes=off,merged
```

`current` reproduz o fluxo Java da 1.8.2 e `merged` o da 1.9.0; sem `BENCH_BRIDGE` a profundidade usa a cópia. `-PbenchmarkLibrary` aceita a DLL antiga para o antes.
