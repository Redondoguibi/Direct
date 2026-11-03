# Integração Direct / Raydiance / Lus — 2026-11-02

## Estado: correções de estabilidade entregues; migração de materiais incompleta

Versões compiladas: Direct 1.2.1 / API 1.1.0; Raydiance 1.2.2; Lus com 16 controles próprios.
O projeto Direct usado é mod/Direct. A cópia mod/Direct Group/Direct é antiga e não foi utilizada.

## Evidências e correções

- latest.log do modpack, linhas 224267–224278: GL_INVALID_ENUM na importação da fence,
  seguido de falha na criação da root signature do passe de extensão. O modpack tinha
  direct-1.2.0.jar de 127628 bytes, anterior à correção de root CBV.
- O projeto principal já substituía 64 constantes de raiz + tabela SRV (65 DWORDs,
  acima do limite) por CBV. Essa implementação foi recompilada e entregue como 1.2.1,
  evitando confusão com os dois JARs 1.2.0 diferentes.
- Teste isolado com janela GL e backend D3D12 reais: Intel UHD 630, driver
  31.0.101.2140 em ambas as APIs. Criação do semáforo passa; importar o HANDLE real
  com GL_HANDLE_TYPE_D3D12_FENCE_EXT retorna 0x500. Não é inferência a partir do log.
  A causa interna no driver não foi determinada. A especificação aceita esse enum:
  https://registry.khronos.org/OpenGL/extensions/EXT/EXT_external_objects_win32.txt
- InteropSemaphore registra a rejeição por contexto GL e não repete a mesma tentativa
  em cada reinicialização do renderer. A primeira rejeição continua diagnosticada;
  o teste verifica retorno pelo caminho CPU e preservação da fence. Não promete
  eliminar o primeiro GL_INVALID_ENUM nem tornar a importação suportada pelo driver.
- Erros GL anteriores são registrados, sem atribuí-los silenciosamente à importação.
- compositeTerrainFrame verifica o resultado da espera nativa antes de compor.
- Encerramento executa uma única conclusão GL quando existe GLsync pendente, em vez
  de chamar glFinish duas vezes. A capability GPU semaphores passa a refletir o fallback.
- Raydiance continua descobrindo o terreno nativo mesmo com sua integração opcional
  desligada, evitando confundir desativação de passes com ausência do renderer.
- Raydiance exige API 1.1+ para passes HLSL e explica a incompatibilidade de API 1.0;
  seu compositor independente permanece disponível.

## Cobertura real

Direct: 24 testes unitários e 4 gráficos, incluindo o HLSL Lus. Smoke nativo: 1 teste.
Raydiance: 35 testes unitários e 19 gráficos, incluindo os 9 testes Lus.
Nenhuma falha ou teste ignorado. validateLus repete um subconjunto e não deve ser
somado a esses números. Testes gráficos usam GPU, D3D12, WGL e GL reais, mas cenas
sintéticas; não equivalem a uma sessão de Minecraft nem a um benchmark de FPS.

Inspecionados: hooks de camadas, captura de malhas, assinatura de recursos dos passes,
submissão/espera/composição, compartilhamento das fences, capabilities públicas,
descoberta/registro HLSL no Raydiance e bloqueios dos efeitos de terreno OpenGL.

## Pendências de implementação — não confundir com testes pendentes

Sombras completas, água, reflexos e materiais NÃO foram transferidos para DX12.
O Direct captura solid/cutout/cutoutMipped. O formato de vértice atual não transporta
identificação de material nem normais; não há submissão da camada translúcida,
ordenação de transparências ou contrato de efeitos de material para os packs.
Os passes HLSL públicos ainda não amostram profundidade (depthInput=false).

A migração precisa preservar ordem com entidades/água, blending e profundidade;
introduzir identificação de materiais, contrato de configurações, recursos de
sombras/reflexos e testes de transparência. Simplesmente cancelar a camada vanilla
ou habilitar água OpenGL sob DX12 não implementa esses recursos corretamente.
O fallback e as implementações atuais foram preservados.

## Validação pendente no jogo

Reiniciar o modpack com os JARs novos. Verificar DX12 Terrain + Lus em dia/noite,
chuva, água, resize, troca de pack, recarga, mudança de dimensão e alternância DX12.
Confirmar ausência do ciclo root-signature/reinicialização, cobertura correta do
fallback, estado indicado na tela e FPS/tempo de frame na mesma cena.
Esta entrega NÃO conclui todos os objetivos solicitados de migração do backend.
