# Desempenho de Direct e Raydiance

## Configurações comparadas

| Ajuste | Direct na fase 3 e nos novos casos do harness | Sodium no harness da fase 3 | Instância Direct no CurseForge |
| --- | --- | --- | --- |
| API e pack | DX12 total experimental, Raydiance e Lus | OpenGL, Iris e Mellow | Modo híbrido no último log, Raydiance com Solaris selecionado |
| Janela e VSync | Janela oculta, VSync desligado | Janela oculta, VSync desligado | Tela cheia, VSync ligado |
| Gráficos do jogo | Bonitos | Bonitos | Rápidos |
| Distância de renderização e simulação | 12 e 5 | 12 e 5 | 12 e 5 |
| Nuvens vanilla | Desligadas | Rápidas | Desligadas |
| Mistura de biomas e mipmaps | 2 e 4 | 2 e 4 | 2 e 4 |
| Flush OpenGL entre estágios | Desligado | — | Ligado |

O harness pede 1920×1080 e limita o FPS a 260 por configuração do jogo. Os casos Direct partem de cópias do mesmo mundo com a câmera em `(-26,2754, 65, -35,3533)`. Usam NeoForge 21.1.265, Java 21.0.12.1, 3 GiB de heap máximo e Intel UHD 630 com driver 31.0.101.2140.

Entre o caso histórico `p3c-dx12-A` e o primeiro caso atual de controle, os arquivos do Direct, de otimizações, do Raydiance, do FML e das opções do Lus têm hashes idênticos. Os arquivos do pack Lus também coincidem. O conteúdo de `options.txt` é igual após normalizar as quebras de linha. O JAR do Direct é de outra revisão. Os JARs do Raydiance, GeckoLib, The Fict e benchfps coincidem.

## Alterações

- **Direct.** O DX12 total espera pela fence do slot que reutiliza e aproveita a ordenação das filas na GPU. A espera pela última submissão é reservada para troca de descritores compartilhados, reconfiguração ou redimensionamento. O modo híbrido conserva as verificações de consumo da cena pelo OpenGL.
- **Direct.** Reutiliza atributos de vértice por programa e formato. Evita reaplicar parâmetros de textura idênticos. O detalhamento por upload pode ser ativado com `-Ddirect.phase1.profileUploads=true`. A medição agregada das listas permanece disponível.
- **Raydiance.** Reutiliza matrizes inversas já calculadas, reduz trabalho do seletor de perfis e restringe a captura de estado OpenGL às unidades de textura que o passe toca. A seleção de seções próximas é reaproveitada nas camadas do passe de sombras.

Os shaders, as opções dos packs, a distância e a resolução permanecem os mesmos.

O trabalho em listas de seções e no reuso de recursos por passe tomou como referência a organização de visibilidade do Sodium e o gerenciamento dos alvos finais pelo Iris. [Sodium](https://github.com/CaffeineMC/sodium/blob/dev/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager.java) · [Iris](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/pipeline/FinalPassRenderer.java)

## Pausas observadas no DX12 total

O log de perfil separou a queda observada nestes testes do trabalho de renderizar o nível. Com a revisão anterior, a janela A2 teve em média 9,5 ms dentro da renderização do nível e 61,3 ms fora dela. A revisão otimizada reduziu a espera no início do terreno, mas executada com a mesma política de sincronização OpenGL ainda apresentou pausas longas.

O mesmo JAR da fase 3 usado no resultado histórico fez 65,4 FPS no controle atual. A revisão posterior havia passado a sincronizar OpenGL apenas quando ele tocava as texturas compartilhadas. Ainda há uploads OpenGL do jogo no modo só DX12. Foi restaurada a chamada que drena esse trabalho antes de o backend enviar a cena ao pack, conservando a espera por slot no terreno. A tentativa mantém as mesmas texturas e passes.

## Medição

Casos descartáveis do `run-case.ps1`, em `%TEMP%\direct-vs-sodium\fps-opt-3dd73168-*`. Cada execução usou duas janelas de 60 s. Os números abaixo vêm da segunda. As opções do jogo, do Direct, do Raydiance e do Lus foram conferidas em todos os casos. Os casos B e C usam o mesmo JAR do Raydiance. Nos JARs do Direct, a diferença entre B e C é a drenagem da fila OpenGL antes dos estágios de pack.

| Caso | Versão | FPS médio | p50 (ms) | p95 (ms) |
| --- | --- | ---: | ---: | ---: |
| A1 | Direct e Raydiance de antes da tarefa | 8,08 | 26,95 | 958,71 |
| B1 | Otimizações com drenagem condicional | 10,59 | 28,14 | 448,37 |
| A0 | JAR exato da fase 3, controle | 65,40 | 14,24 | 20,35 |
| A2 | Repetição de A1 | 14,15 | 18,82 | 287,93 |
| B2 | Repetição de B1 | 14,79 | 18,87 | 244,10 |
| C1 | Otimizações com drenagem restaurada | 68,41 | 12,58 | 28,19 |
| B3 | Repetição de B1 entre C1 e C2 | 12,28 | 20,37 | 294,70 |
| C2 | Repetição de C1 | 72,09 | 12,16 | 26,84 |

Os dois casos corrigidos tiveram média de **70,25 FPS**, cerca de 7,4% acima do controle atual feito com o JAR histórico. A comparação tem um controle histórico reexecutado e dois casos corrigidos. O p95 do controle continua melhor. A principal diferença reproduzida no ensaio C1 → B3 → C2 foi a recuperação das pausas severas ao drenar OpenGL. O perfil registrou que parte da espera do terreno deslocou para a apresentação DXGI. O FPS do Solaris e uma nova comparação intercalada contra Sodium + Iris + Mellow continuam pendentes.

## Verificação

- Direct compilado com `build`. Passaram 61 testes de `test`, 56 de `graphicsTest` e o `nativeSmokeTest`.
- Raydiance compilado com 67 testes unitários e 13 gráficos aprovados. A exportação opcional de fontes ficou desativada.
- A prova em jogo com Solaris e a camada de depuração do D3D12 completou 15 cenas no DX12 total. O log terminou com `PASS` e `unsupported={}`. Essa prova valida a execução do renderizador. O FPS comparativo foi medido com o Lus.
