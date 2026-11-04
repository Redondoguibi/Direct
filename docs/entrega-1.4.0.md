# Entrega Direct + Raydiance + Lus — 2026-11-03

## Implementado

Direct 1.4.0/API 1.3.0 e Raydiance 1.4.0, usando o projeto principal mod/Direct.
Captura V2 de 20 bytes com normais/materiais, transparência ordenada após entidades,
shadow map DX12 próximo, vento com bases ancoradas/plantas duplas, pequenas ondas,
refração e reflexos em tela. Raydiance solicita os efeitos por API opcional, transmite
controles/perfis ao vivo e continua funcionando sem Direct. Lus mantém um único pack.

Desligar Effects/integração remove solicitações e passes opcionais. Efeitos zerados
liberam os alvos correspondentes; saída do mundo libera geometria/alvos mesmo sem
novo renderLevel. Evita sombra GL duplicada e composição vazia motivada apenas por ela.
Transparência vazia ou orçamento insuficiente é detectada antes da captura. Falta de
orçamento preserva a camada vanilla e mantém o opaco DX12, sem ciclo de reinicialização.
Fabulous/alvos incompatíveis preservam transparência vanilla; falhas reais de interop
mantêm a recuperação do backend. O perfil Desempenho do Lus também desliga refração.

Pipeline sem sombras/vento especializado, criado uma vez e selecionado sem recompilar.
Bytecode VS/PS idêntico ao anterior, verificado no compilador. O benchmark oscilou e
não confirma ganho/regressão estável de FPS; os números estão em dx12-terrain-performance.md.

## Verificado

- Direct: build/Javadoc, 29 testes unitários, 9 gráficos (incluindo Lus), smoke WARP 1/1.
- Raydiance: build/assets, 35 testes unitários, 19 gráficos (incluindo os 9 do Lus).
- validateLus também executado separadamente nos dois projetos; são testes repetidos,
  não contados novamente nos totais acima. Zero falhas, erros ou testes ignorados.
- JAR Direct contém a mesma DLL verificada, por SHA-256; JAR Raydiance não contém backend nativo.
- Textura e fence DX12 reais rejeitadas pelo driver Intel UHD 630 31.0.101.2140 com 0x500.
  A extensão anunciada não bastou: a importação direta não foi adotada. Causa interna
  do driver não determinada. Ponte 11On12 mantida e fallback GLsync verificado.

## Instalação e teste no modpack

Os arquivos destinados a mods são exclusivamente direct-1.4.0.jar e raydiance-1.4.0.jar.
Shaderpack: config/raydiance/shaderpacks/lus, com lus.hlsl apenas como detalhe opcional.
Configurações e pack-options existentes devem ser preservados; o backup fica fora de mods.

A configuração encontrada antes da entrega usa DX12 replacement/profiling ligados,
OpenGL pack none e enableDirectIntegration=false. Para ver Lus e os efeitos nativos:
F8 > Shaderpacks > Pack: lus; Effects: ON; Integration > Direct DX12 integration: ON.
HLSL: none evita o passe extra de detalhe; não impede sombras/água nativas.
Ajustar Sombras/Água/Reflexos/Vento e Quality em Effects; Save confirma, Cancel desfaz.

O usuário deve abrir Minecraft. Esta execução não o abriu e não certifica FPS no jogo.
Referência anterior: 60 FPS desligado / 50 ligado. Faltam comparação na mesma cena e
p95/p99, validação visual dos hooks no mundo, chuva/noite/dimensões, água corrente/alagada,
blocos de outros mods, reload, resize, alternância e sessão longa. Custos de transparência
são separados em logs/direct-translucency-performance.csv, uma janela por minuto.

Sombras nativas são próximas, sem cascatas/entidades; reflexos usam a cena em tela.
A nova transparência tem custos reais de cópia/sincronização; não há promessa de ganho
ou equivalência ao Mellow. Consulte native-shader-contract.md para detalhes verificáveis.


## Tentativa de abertura observada

Às 18:54 o usuário iniciou o modpack, os dois mods 1.4.0 foram carregados e o
cliente foi fechado durante o reload de recursos. O usuário confirmou que fechou
para permitir a aplicação das mudanças finais. ConcurrentModificationException
em TextureManager.close foi registrado no encerramento; não é evidência de falha
espontânea durante jogo. Nenhum mundo foi validado nessa tentativa. A revisão
final com o caminho especializado foi instalada após o fechamento, com hashes
conferidos. NeoForge apenas acrescentou dx12Translucency/directDetail aos arquivos
de configuração; os valores preexistentes foram comparados com o backup e preservados.
