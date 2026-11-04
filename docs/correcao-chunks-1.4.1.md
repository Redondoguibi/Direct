# Direct 1.4.1 — estabilidade de chunks e propriedade por seção

## Causas verificadas

Na 1.4.0, a submissão DX12 ocorria antes de compileSections/uploadAllPendingUploads.
A captura publicava dados ao terminar a compilação, antes da aceitação do upload vanilla.
Qualquer seção visível pendente/em compilação tornava todo o conjunto incompatível;
compilação assíncrona iniciada depois da submissão também descartava a imagem completa.
Isso alternava a renderização global mesmo quando o restante do terreno estava pronto.

## Correção

- O quadro nativo é preparado depois do processamento dos uploads vanilla.
- Capturas são identificadas pelos MeshData realmente enviados aos VertexBuffers.
- A aceitação de CompiledSection confirma que todas as camadas correspondem à mesma
  captura e à mesma geração. Cópia DX12 só é considerada atual após seu upload.
- Compilar em segundo plano preserva a propriedade da geometria atualmente exibida;
  compilações canceladas/antigas não antecipam nem substituem a versão aceita.
- Se uma seção ainda não está pronta, apenas ela usa vanilla. O DX12 desenha o subconjunto
  pronto; o draw vanilla de cada buffer já composto é suprimido, evitando duplicidade.
- Propriedade congelada no quadro evita reavaliar trabalho assíncrono de compilação
  como se já tivesse alterado seus buffers. Falha real de captura/interop continua
  liberando o fallback, sem suprimir draws vanilla antes da composição bem-sucedida.
- Seleção dos casters de sombra também exclui cópias obsoletas/incompatíveis, incluindo
  as fora da câmera. Exclusões sem recurso nativo correspondente deixam de ocupar a fila.
- Atualizações obsoletas são descartadas antes de alocar/copiar seus buffers nativos.
- A fila continua coalescida por seção e limitada por orçamento de upload por quadro.
  Seu limite de memória é suave e diagnosticado; a thread de renderização não espera
  dois segundos pela fila. Fontes não consumidas usam referências fracas para não manter
  compilações canceladas vivas. Isso não constitui um limite rígido de toda a RAM do jogo.

O HUD mantém DX12 com a contagem de seções vanilla quando houver composição mista.
Vanilla completo ainda é correto na inicialização sem qualquer cópia nativa pronta,
troca de mundo/reload incompatível ou falha real. Não foi apenas removida a mensagem.
A transparência mantém ordenação da camada inteira: se faltar seção translúcida atual,
essa camada preserva vanilla; isso não devolve as camadas opacas prontas ao vanilla.

## Verificação

Testes de ciclo de vida cobrem compilação em segundo plano, atualização de uma entre
duas seções, upload parcial/cancelado, chegada obsoleta, buffer substituído por outro mod,
seção reutilizada e troca de mundo. Teste de GPU verifica casters excluídos/reincluídos
sem sombras de geometria obsoleta; testes existentes mantêm cobertura de cor/depth e
oclusão da geometria GL na área não coberta por DX12, além de Lus, água e reflexos.
Build, Javadoc, testes unitários, gráficos, validateLus e smoke WARP executados.
Os novos hooks ainda precisam de confirmação funcional no cliente real; nenhum Minecraft
foi iniciado pelo agente, nem foi solicitado novo teste de FPS nesta correção.

## Outras evidências da sessão anterior

latest.log da sessão 1.4.0: 23183 falhas de inicialização OpenAL, além de avisos de sons
repetidos. Isso é um problema de áudio separado, não corrigido alterando o renderizador.
Às 19:28 a janela registrou zero camadas base vanilla e 20,42 FPS, portanto a alternância
não explica sozinha toda a perda. Espera DX12 média de 22,64 ms e custos da transparência
também estavam presentes. São observações de sessão, sem comparação controlada.
Não há promessa de ganho de FPS nem declaração de que todos os problemas do jogo acabaram.
