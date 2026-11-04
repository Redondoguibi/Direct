> Direct 1.5.0 / API 1.4.0: [materiais, controles e compatibilidade](materiais-1.5.0.md). As notas abaixo de versões anteriores são históricas.

> Atualização Direct 1.4.2: [cor compartilhada, fallback e medições](interop-cor-1.4.2.md). A API pública continua 1.3.0. A ponte D3D11On12/WGL permanece, com cópia de profundidade e cópia de cor apenas no fallback.
>
> Direct 1.4.1: [propriedade por seção e correção de chunks](correcao-chunks-1.4.1.md); o fluxo global de cobertura foi substituído por composição mista segura.

# Direct 1.4.0 / API 1.3.0 — contrato e integração nativa

## Responsabilidades

Direct executa geometria, materiais, sombras, vento, água e reflexos em C++/HLSL/D3D12.
Raydiance continua independente: gerencia packs, opções, presets, perfis e pós-processamento.
Lus fornece seu compositor original e padrões configuráveis. Nenhum código do Mellow é distribuído.
A ponte D3D11On12/WGL continua responsável por transferir cor/profundidade entre APIs.
Não existe compatibilidade binária com shaderpacks Iris/OptiFine.

## API de geometria, sem pointers nativos

```java
var service = DirectRenderService.getInstance();
if (service.terrainFeatures().containsAll(java.util.Set.of("shadows", "water"))) {
    service.requestTerrainEffects("example.pack",
        new DirectTerrainEffects(.35f, .4f, .18f, .25f, 1));
}
// Ao desativar/descarregar:
service.releaseTerrainEffects("example.pack");
```

Os quatro valores são sombras, água, reflexos e vento, finitos em 0..1. Qualidade 0/1/2.
Zero elimina a contribuição; sombras zero não gravam o passe nem mantêm seu mapa.
No máximo 64 clientes. IDs seguem a sintaxe dos passes. O primeiro ID em ordem lexical
vence, sem somar efeitos; terrainEffectOwner informa o selecionado. Release restaura
o próximo cliente ou NONE. Descoberta disponível não significa execução em todo alvo:
Fabulous/alvos incompatíveis e orçamento insuficiente podem usar transparência vanilla.
Nomes atuais: materials-v2, normals, shadows, wind, translucency, water, reflections.

Raydiance usa descoberta por reflexão, sem dependência compilada. A cada tick transmite
os valores efetivos dos perfis/controles gerais. Desligar Effects ou Direct DX12 integration
remove a solicitação e o passe HLSL opcional; não desliga o terreno Direct. A solicitação
é liberada ao sair do mundo e sua limpeza é tentada novamente se a chamada falhar.
A composição GL não aplica sombras de contato novamente quando a geometria nativa está ativa.
Os controles GLSL próprios contactSteps/contactDistance do Lus continuam restritos ao
caminho OpenGL; intensidade e Quality gerais controlam as sombras nativas.

## Geometria V2

20 bytes por vértice, em vez de 16 (+25% no armazenamento de vértices):

| Offset | Dados |
| --- | --- |
| 0 | uint16 xyz, origem -16, escala 1/1024 de bloco |
| 6 | dois canais de luz vanilla uint8 |
| 8 | cor/AO RGBA8 vanilla |
| 12 | UV UNORM16 x2 |
| 16 | normal xyz SNORM8 |
| 19 | material nos 7 bits baixos; bit 7 para pontas de plantas/superfície da água |

Materiais: 0 genérico, 1 pedra, 2 madeira, 3 folhas, 4 vidro/gelo, 5 água,
6 emissivo, 7 metal, 8 planta, 9 metade superior de planta dupla. Tags vanilla e identificação conservadora de blocos
fornecem a classificação; nomes de blocos modificados são heurísticos, desconhecidos
ficam genéricos. UVs dos sprites vanilla de água também identificam faces de água
em blocos alagados, com snapshot renovado no reload. Preserva atlas, AO, luz e fog.
O wrapper interno de testes aceita V1; a captura produtiva e o input layout são V2.
Esses dados alimentam a geometria interna, não um G-buffer público de materiais.

## Sombras e vegetação

Mapa de profundidade D3D12 512/1024/2048 conforme Quality, frustum de 96 blocos
(48 de cada lado da câmera), comparação filtrada, viés e fade nas bordas.
Casters sólidos/recortados de seções carregadas num raio de 80 blocos, incluindo
casos fora da câmera. Folhas recortam alfa; sombras usam a mesma animação de vento.
Sem sombra solar à noite ou em dimensão sem céu. Chuva atenua a projeção.
Não são cascatas, sombras de entidades ou sombras de distância ilimitada.
Vento executa no vertex shader; pontas de plantas movem sem deslocar sua base,
metades superiores de plantas duplas acompanham a junção. A fase é periódica em
4096 blocos para não saltar ao mudar a origem relativa da câmera.

## Transparência, água e reflexos

A camada translucent é desenhada depois das entidades. A cena GL atual é importada
com cor e profundidade; seções e quads são ordenados de trás para frente, com blending
src-alpha/inv-src-alpha e escrita de profundidade. Não desenha de novo a camada vanilla
quando a substituição foi bem-sucedida. Ordenação por seção/quad, como o agrupamento
vanilla, não resolve perfeitamente polígonos transparentes que se intersectam.
Água tem pequenas ondas geométricas, normal animada, refração protegida pela profundidade
e reflexão de Fresnel com busca limitada em tela (6/10/16 passos). Fora da tela usa cor
atmosférica, sem prometer refletir objetos ausentes da cena. Cor/profundidade usadas para
amostragem são snapshots distintos do alvo que recebe água, evitando alias de leitura/escrita.

Sem água/reflexos solicitados, mantém transparência GL para evitar a segunda travessia.
dx12Translucency=true permite solicitar vidro/transparência nativa mesmo sem esses efeitos.
Camada vazia não captura a cena. Fabulous e alvos diferentes do principal preservam a camada
vanilla. Falta de espaço no ring é diagnosticada e mantém apenas essa camada em vanilla;
falhas de dispositivo/interop encerram o backend e preservam o fallback vanilla.
O preflight verifica o orçamento antes da captura GL. Não aloca ring ilimitado para ordenar.

## Passes HLSL externos, compatíveis com API 1.2

- t0: cor anterior; t1: profundidade ORIGINAL do terreno opaco/recortado em todos os passes.
- Texture2D<float> em t1; Load(int3(pixelXY,0)); profundidade de dispositivo 0..1,
  fundo 1, origem superior esquerda. Não é profundidade linear em blocos.
- Não inclui entidades/transparência renderizadas posteriormente. Não expõe material/normais.
- s0 linear/clamp. b0: float4(elapsedSeconds,width,height,frameIndex) + float4 DirectUniforms[15].
- Até 8 passes e 60 floats por passe; preenchimento por zero; alvos separados ping-pong.
- Direct administra SRVs, barriers, memória, resize e encerramento. Os clientes não recebem pointers.
- Verificar capabilities().depthInput(). HLSL antigo que só usa t0 permanece compatível.

Lus.hlsl é apenas detalhe opcional com proteção por profundidade; não contém a implementação
nativa da água/sombras. F8 > Integration > Detalhe DX12 envia DirectUniforms[0].x sem recompilar.
Zero é identidade. As marcações // raydiance:uniforms=1 e // raydiance:depth=1 são opt-in.
HLSL: none economiza esse passe sem desligar a geometria nativa nem o compositor Lus.

## Sincronização e custos

Na Intel UHD 630 / driver 31.0.101.2140, testes com HANDLEs reais exportados por DX12
reproduziram 0x500 (GL_INVALID_ENUM) tanto para D3D12_FENCE_EXT quanto D3D12_RESOURCE_EXT,
mesmo com as extensões anunciadas e o mesmo adaptador nas duas APIs. O teste de memória usa
size=0 conforme revisão 10 da especificação. A causa interna do driver continua desconhecida.
A importação direta não foi promovida: não passou no teste funcional, portanto não permite
uma comparação de desempenho válida. Não houve substituição silenciosa do enum ou supressão
da primeira falha; a rejeição de fence é lembrada por contexto e usa GLsync real.

Fontes do contrato:
- https://registry.khronos.org/OpenGL/extensions/EXT/EXT_external_objects_win32.txt
- https://learn.microsoft.com/en-us/windows/win32/api/d3d11on12/nf-d3d11on12-id3d11on12device-createwrappedresource

Sem sombras/vento ativos, opaco/recortes selecionam PSOs especializados cujo
bytecode é idêntico ao shader 1.3.0, sem as rotinas e entradas de efeitos novos.
As variantes são criadas na inicialização, sem recompilação por ajuste.

O caminho translúcido exige captura GL, conclusão dessa captura, importação 11On12,
DX12, exportação e composição. Usa glFinish para a captura atual: a fence já sinalizada
pelo opaco não pode representar esses novos writes. Esse custo é explícito e mensurado;
não há alegação de aumento de FPS. Snapshots de água custam 8 bytes/pixel (+16,6 MB em
1080p), apenas quando necessários. Shadow map custa aproximadamente 1/4/16 MiB por qualidade.
Metadados CPU de ordenação usam centro float3 + 6 índices por quad; ring GPU é reutilizado.
As funções de renderização nativa não executam efeitos dentro do D3D11On12.

## Validação

Os relatórios desta execução estão em build/test-results/{test,graphicsTest,validateLus}
e build/native/direct/Testing. Cobertura: sombra no receptor sem mudar profundidade,
folhagem animada, água/refração, reflexão com acerto real na cena, vidro ordenado e oclusão
por entidade, efeitos zerados, qualidade, resize com recursos ativos, falta de orçamento,
continuação após fallback, origem das linhas e restauração de estado GL. Lus HLSL e GLSL
são compilados/renderizados pelos carregadores reais, além dos testes independentes do Raydiance.

Ainda depende de Minecraft aberto pelo usuário: hooks/mixins no modpack completo, F8,
aspecto do mundo real, blocos de outros mods, chuva/noite/dimensões, Fabulous, sessão longa
e FPS/p95/p99 comparáveis. Teste isolado de GPU não substitui esses resultados.
