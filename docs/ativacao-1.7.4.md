# Direct 1.7.4 — ativação sem travamento

## Problema

Ao ligar **Ativar terreno DX12** (ou a cada **Salvar e aplicar**, que reinicia o backend), o jogo congelava. No primeiro frame, a render thread fazia de uma vez:

1. `LevelRenderer.allChanged()`: recriava a ViewArea. O mundo inteiro sumia e todas as seções eram recompiladas e recapturadas ao mesmo tempo.
2. A leitura síncrona do atlas de blocos com todos os mips (`glGetTexImage`), seguida da codificação BC7 do atlas inteiro esperando na render thread. Em atlas de modpack (4096² ou maior), isso leva segundos.
3. Oito `D3DCompile` com otimização O3 em série e a criação dos PSOs. A pipeline library existia só em memória, então tudo era recompilado a cada ativação.

## Mudanças

**Adoção gradual das seções.** `allChanged()` foi removido. As seções visíveis compiladas antes da ativação continuam desenhadas pelo vanilla. A cada frame, até `maxSectionAdoptionsPerFrame` delas (padrão 32, perfil Menor memória 16) recebem `setDirty(false)`, um rebuild assíncrono comum, da mais próxima para a mais distante. O vanilla mantém o mesh antigo até o novo commit, então o mundo não esvazia. Cada seção é pedida no máximo uma vez por geração (`TerrainMeshStore.requestAdoption`), e seções já sujas ficam com o vanilla. O controle está em **Avançado > Adoções por frame**.

**Atlas assíncrono.** `AtlasFullUpload` lê os mips para um PBO protegido por fence e codifica BC7 na thread `Direct atlas full encoder`, reutilizando o `encodeAtlasRegion` existente. O resultado entra em `updateTerrainAtlas(..., preEncoded=true)`, e o nativo apenas copia os blocos. Enquanto a transferência roda, o frame mantém o vanilla com o status **Vanilla • preparando texturas de blocos**. O mesmo caminho atende resource reloads. Sem compressão, o RGBA também vem do PBO, sem stall.

**Shaders e PSOs em cache.** As oito permutações de terreno são compiladas em paralelo (`std::async`). O bytecode fica em `.minecraft/cache/direct/shaders/<hash>.cso`. A chave cobre o fonte HLSL, o entry point, o target, os defines, as flags e a versão do D3DCompiler. Cada arquivo tem um cabeçalho com tamanho e hash do conteúdo, e um arquivo corrompido é recompilado. A `ID3D12PipelineLibrary` é serializada em `pipelines-<hash do shader>.bin` e recarregada na ativação seguinte. Quando o driver muda, o D3D12 rejeita a biblioteca e ela é reconstruída. Falhas de I/O nunca impedem a inicialização.

**Medição.** O `latest.log` passa a registrar:

- `Direct native renderer initialized in X ms (shaders: N cached, M compiled; PSO library ...)`
- `Direct block atlas transferred in X ms (Y ms on the render thread)`
- `Direct activation: first DX12 frame after X ms (...)`

## Validação

- `build`: Java e DLL compilados.
- `nativeSmokeTest`: passou.
- `test` e `graphicsTest`: 69 testes, 0 falhas. Isso inclui `NativeShaderCacheTest` (oito compilações na primeira inicialização, oito leituras do cache na segunda, PSO library recarregada e entrada corrompida recompilada), o upload de atlas pré-codificado em `NativeTerrainInteropTest` e a adoção única por geração em `TerrainMeshStoreTest`.
- Três testes gráficos usavam 60 ou 120 floats de uniforms, valores anteriores ao `MAX_UNIFORM_FLOATS = 252`. Eles já falhavam no commit anterior e foram alinhados à constante.
- O `javadoc` não rodou porque o Google Drive mantinha `build/docs/javadoc` bloqueado. Os resultados de teste foram gravados fora de `build/` pelo mesmo motivo.

O comportamento no jogo ainda precisa ser confirmado. O esperado ao ativar é o mundo continuar visível e o HUD passar por *preparando texturas de blocos* → *preparando terreno* / *misto* → *ativo*. A segunda ativação deve registrar `8 cached, 0 compiled`. Nenhum ganho de FPS em regime é declarado.
