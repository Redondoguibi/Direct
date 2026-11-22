# Atlas animado pela CPU — menos dependência do OpenGL

## O que dependia do OpenGL

O Direct desenha o terreno em DX12, mas o atlas de blocos vive no OpenGL do Minecraft. Para manter o atlas DX12 em dia com os sprites animados (água, lava, fogo, portais, blocos de mods), o Direct relia do OpenGL, a cada `animatedAtlasRefreshMillis` (250 ms por padrão), as regiões alteradas em todos os níveis de mip: framebuffer por nível, `glReadPixels` para um PBO, fence `GLsync` e mapeamento do buffer. Enquanto houvesse animação na tela, isso não parava. Além disso, cada envio de sprite consultava o OpenGL (`glGetInteger(GL_TEXTURE_BINDING_2D)`) para saber se o alvo era o atlas de blocos.

## Mudança

O vanilla envia cada quadro de sprite com `SpriteContents.upload`, a partir de `NativeImage` que já estão na CPU, com `glTexSubImage2D`. O Direct passa a usar esses mesmos bytes:

1. Cada região alterada é ampliada, por nível de mip, para blocos BC7 inteiros (um *patch*), como antes.
2. Na primeira vez em que um patch aparece, ele é lido do OpenGL uma única vez, como base. Isso preserva exatamente os vizinhos e os texels entre sprites, cujo conteúdo o OpenGL não define.
3. Depois disso, cada novo quadro é copiado da memória do `NativeImage` para os patches em cache, com o mesmo deslocamento de quadro, a mesma largura de linha e o mesmo critério de parada por nível do vanilla. A cópia vale também para patches vizinhos que cobrem os mesmos texels nos mips menores.
4. A compressão BC7 e o envio ao DX12 continuam como antes, fora do render thread.

Com a animação em regime, o atlas DX12 não faz mais nenhuma leitura do OpenGL. Uma nova leitura só acontece quando surge um patch novo.

A consulta de textura vinculada por envio de sprite foi trocada pelo escopo de `TextureAtlas.cycleAnimationFrames`. Envios feitos fora desse escopo (por algum mod) continuam usando a consulta antiga.

### Fallback

- Se um nível não puder ser copiado (imagem fechada, formato diferente de RGBA8, retângulo fora da imagem, ou mixin do `NativeImage` ausente), os patches daquele envio são relidos do OpenGL, como antes.
- Antes de o vanilla fechar as imagens do atlas (`clearTextureData`), o Direct descarta as referências. Uma cópia em andamento termina antes do fechamento.
- A transferência inteira do atlas na ativação ou no reload não mudou: continua sendo `glGetTexImage` assíncrono, porque o conteúdo dos texels entre sprites só existe no OpenGL.

## Opção

`cpuAtlasAnimation` vem ligada por padrão e é o botão **Animação do atlas pela CPU** na nova aba **Texturas** do painel (F7, `/direct menu` ou Mods > Direct > Config). Essa aba reúne os controles do atlas de blocos: compressão BC7, threads do codificador, intervalo de animação e esta opção; os três primeiros saíram da aba Recursos, sem mudar chave, valor ou efeito. O preset **Compatibilidade** desliga a opção, e ela aparece em **Diagnóstico**. Desligada, o Direct volta a reler do OpenGL todas as regiões alteradas. Desligue-a se algum mod escrever no atlas de blocos por um caminho que não seja o `SpriteContents.upload`: nesse caso a cópia pela CPU não veria a alteração. Como as demais opções, ela fica num rascunho até **Salvar e aplicar** e reinicia o backend.

A linha `Direct costs` do log (com `profileRenderer`) agora inclui `atlasGlReads`, o número de leituras de patches feitas ao OpenGL na janela. No modo CPU esse número deve ficar perto de zero depois dos primeiros segundos.

## Validação

- `AtlasCpuMirrorTest` (gráfico, sem Minecraft): o atlas OpenGL de 64×64 com 4 mips é preenchido com lixo aleatório nas lacunas. Cinco sprites animados, incluindo patches que se sobrepõem nos mips 2 e 3, são enviados com o mesmo estado de unpack do `NativeImage._upload` ao longo de 7 rodadas. Todo patch enviado ao "DX12" é comparado byte a byte com o `glGetTexImage` real. Resultado: idênticos, com uma única leitura de base para todas as rodadas. Uma fonte inutilizável cai na releitura com o mesmo resultado, e fontes descartadas não são lidas. Com um defeito injetado de propósito (deslocamento de quadro ignorado), o teste falha.
- `RendererMixinTargetsTest` confere no bytecode do 1.21.1 os alvos `TextureAtlas.cycleAnimationFrames()V`, `clearTextureData()V`, `SpriteContents.upload(IIII[NativeImage;)V` e o campo `NativeImage.pixels J`.
- `build`, `test` (61), `graphicsTest` (56, incluindo o round trip GL → DX12 do atlas em `NativeTerrainInteropTest`) e `nativeSmokeTest`: todos passaram.

## Limites

O Minecraft não foi aberto. Ainda falta confirmar no jogo que os blocos animados (água, lava, fogo, portal, blocos animados de mods) continuam iguais com a opção ligada e desligada, e que `atlasGlReads` cai para perto de zero. Não há medição de FPS. A leitura removida já era assíncrona, então o ganho esperado é a ausência de trabalho e sincronização OpenGL periódicos, não uma mudança mensurável de quadro.

O que continua no OpenGL por necessidade, conforme as outras docs: a transferência inteira do atlas, a ponte D3D11On12/WGL, a composição no framebuffer do Minecraft, a captura da cena e as sincronizações GL, além de entidades, céu, GUI e tudo o que o Direct não renderiza (ver [tentativa-sem-d3d11on12.md](tentativa-sem-d3d11on12.md), [desempenho-1.9.0.md](desempenho-1.9.0.md) e [grafo-de-mundo-1.9.md](grafo-de-mundo-1.9.md)).
