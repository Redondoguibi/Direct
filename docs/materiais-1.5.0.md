# Direct e Raydiance 1.5.0 — materiais configuráveis no Lus

## Divisão dos projetos

Direct contém C++, JNI, HLSL nativo, recursos de GPU e sincronização. Raydiance contém
Java, configurações, interface, perfis, carregador de packs e seus efeitos OpenGL.
Não foram acrescentados C++, DLLs ou dependência obrigatória do Direct ao Raydiance.
Lus continua sendo uma única pasta de pack, com o HLSL de detalhe opcional separado.

## Recursos novos

| Controle em F8 > Materiais DX12 | Chave para packs/perfis | Comportamento |
| --- | --- | --- |
| Brilho dos materiais | materialSpecular | Destaque solar conforme normal, direção de visão e material; metal, vidro e água têm resposta distinta |
| Superfícies molhadas | materialWetness | Chuva escurece e dá brilho a faces voltadas para cima, usando luz do céu como máscara |
| Materiais emissivos | materialEmissive | Recupera a contribuição sem iluminação da textura de superfícies classificadas como emissivas |
| Absorção da água | waterAbsorption | Atenuação por canal e opacidade conforme distância entre superfície da água e profundidade da cena |

Todos aceitam 0..1, variam sem recompilar shaders e podem ser usados nos presets do
pack ou nos perfis dinâmicos por bioma, dimensão, clima e horário. Zero desliga a
contribuição. Save/Cancel e restauração dos presets usam o sistema existente.

São efeitos opcionais do backend DX12: exigem DX12 Terrain no Direct, Effects e
Direct DX12 integration no Raydiance. HLSL pode permanecer em none. Raydiance sem
Direct mantém seus efeitos OpenGL; os quatro recursos novos não têm implementação GL.
Direct anterior à API 1.4 mantém a integração de geometria existente e informa que
materiais precisam da API nova. Desligar a integração libera também os materiais.

## API 1.4.0

```java
if (service.terrainFeatures().contains("material-lighting")) {
    service.requestTerrainEffects("example.pack",
        new DirectTerrainEffects(.35f, .4f, .18f, .25f, 1),
        new DirectMaterialEffects(.2f, .3f, .4f, .25f));
}
// Encerramento, desativação ou troca de dono:
service.releaseTerrainEffects("example.pack");
```

Geometria e materiais são publicados em um único snapshot imutável, com a mesma
arbitragem de proprietário. A chamada antiga de dois argumentos continua disponível
e limpa materiais anteriores daquele proprietário. Nenhum ponteiro nativo é exposto.
Valores inválidos não substituem a solicitação válida. JNI valida novamente os limites.

## Custo e escolhas do Lus

Brilho, chuva e emissivos usam os passes existentes e os vértices de 20 bytes já
capturados. Não acrescentam alvo de renderização. O buffer de constantes de geometria
tem quatro floats adicionais, com alocação alinhada; são 768 bytes adicionais nas três
posições do ring. Sem efeitos necessários, opaco/recortes usam a variante rápida.
Brilho solar não força essa variante de materiais à noite; chuva não a força em tempo seco.

A absorção usa a etapa translúcida e seu snapshot de profundidade. Quando usada sem
refração/reflexos, dispensa o snapshot de cor e a respectiva cópia. Em 1080p isso evita
8.294.400 bytes de imagem adicional. Refração/reflexos continuam compartilhando os
snapshots existentes. Fallback por orçamento e por alvo incompatível continua ativo.

O teste de estresse isolado (Intel UHD 630, 1080p, 16 quads sobrepostos, 120 amostras
após 30 de aquecimento) mediu custo significativo de materiais:

| Execução | Materiais | GPU p50 / p95 (ms) |
| --- | --- | --- |
| A1 | desligados | 9,5754 / 10,1402 |
| B1 | ligados | 20,7768 / 22,3640 |
| B2 | ligados | 20,7004 / 21,5397 |
| A2 | desligados | 9,7208 / 11,1241 |

Essa carga enfatiza sobreposição e não prevê o custo de uma cena real. Não é benchmark
de FPS do Minecraft. Dados: `archive/material-cost-1.5.0.txt`; reprodução pelo benchmark
existente com `-PbenchmarkColorAlias=true -PbenchmarkMaterials=true` ou `false`.

Por essa evidência, os quatro efeitos novos ficam zerados nos padrões do Lus e no
perfil Desempenho. Equilibrado restaura esses padrões. Qualidade solicita respectivamente
0,35 / 0,55 / 0,45 / 0,35; os sliders permitem ajustar cada um. As opções já existentes
do pack foram preservadas. Não foi ativado um efeito caro silenciosamente no modpack.

## Limites e validação

Não são materiais PBR com mapas normal/specular externos, iluminação global ou luz
emitida sobre blocos vizinhos. A classificação de blocos continua conservadora; alguns
blocos de outros mods exigirão extensão dessa classificação. A máscara de chuva usa
chuva global/luz do céu, não uma simulação de gotas, poças ou oclusão exata por bioma.
Absorção depende da profundidade visível e não resolve volumes de água ocultos ou
interseções complexas de transparência. Reflexos continuam limitados à cena em tela.

Passaram Direct: 36 unitários, 19 gráficos, validateLus 1 e smoke WARP 1/1;
Raydiance: 37 unitários, 19 gráficos, validateLus 9 e verificação de assets.
Os validateLus repetem os casos do pack presentes nas suítes gráficas; não são casos
únicos adicionais. Sem falhas nem testes ignorados.

Testes de pixels nas duas rotas de interop verificam destaque no metal, chuva na pedra,
emissivos sem clarear pedra, desligamento exato, ausência de brilho à noite, ausência
de chuva em faces sem luz do céu e profundidade preservada. Água mais profunda absorve
mais luz com refração/reflexos desligados; entidades à frente continuam intactas.
Testes de API verificam troca/liberação de proprietário e compatibilidade da chamada
antiga. Raydiance é testado sem o JAR do Direct e aceita as novas opções em packs/perfis.

Minecraft não foi iniciado pelo agente. Ainda precisam de validação no jogo: interface
F8 em uso real, carregamento conjunto dos mods, aparência no mundo, chuva em biomas,
blocos modificados, sessão longa e impacto em FPS/frametime. A queda anterior ao ativar
DX12 Terrain não é declarada resolvida por esta expansão visual.
