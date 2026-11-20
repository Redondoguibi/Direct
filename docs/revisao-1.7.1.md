# Revisão corretiva — Direct 1.7.1 / Raydiance 1.6.1

Revisão estática dos fluxos Java, JNI, C++, HLSL e persistência de configurações usando o fluxo Athos GPT. A solicitação autoriza revisão e correção. Os testes continuam adiados por instrução da sessão.

## Defeitos corrigidos

- **HIGH — recursos compartilhados de passes:** reconfigurar shaders destruía os alvos/descritores funcionais antes de concluir a nova alocação. Remover uma cadeia também recriava alvos ainda usados pela outra etapa. Os alvos agora são reutilizados por tamanho; a configuração só troca pipelines após compilar e garantir recursos. A primeira alocação incompleta é limpa sem substituir pipelines funcionais.
- **HIGH — desbloqueio de interop:** os dois retornos WGL de desbloqueio na etapa world eram ignorados. Falhas agora interrompem o fluxo e entram no tratamento do backend; não são seguidas por submissão/apresentação consideradas bem-sucedidas. Falhas de execução também são expostas em `worldFailure()`.
- **MEDIUM — persistência de packs somente de parâmetros:** `prepare()` retornava antes de ler os valores engine salvos quando não havia controles próprios. Agora todos os packs carregam sua configuração.
- **MEDIUM — aplicação parcial de configurações inválidas:** a leitura engine era separada e modificava o resultado incrementalmente antes de validar o arquivo inteiro. Foi consolidada numa transação: formato, números e modos discretos são validados antes da aplicação; uma falha preserva todos os padrões. Quality/FXAA fracionários e inválidos são rejeitados, inclusive na restauração de snapshots.
- **MEDIUM — valores de outro pack após falha:** selecionar um pack sem HLSL válido mantinha o manifesto anterior ativo; os controles poderiam alterar o pack errado. A seleção agora remove o estado anterior antes do carregamento e bloqueia edições nativas quando o novo pack não está pronto.
- **MEDIUM — HLSL avulso bloqueando configurações OpenGL:** apenas escolher um arquivo HLSL legado fazia `NativeShaderPacks.selected()` suprimir todo o pós-processamento independente, mesmo quando a integração estava desligada. A exclusividade global agora pertence à seleção de um pack de pasta. O HLSL avulso continua como passe de terreno opcional, sem se passar por um pack da cena completa.
- **MEDIUM — profundidade interpolada:** os HLSL usavam o sampler linear de cor para profundidade. Agora usam `Load` em texel limitado às dimensões da imagem, preservando descontinuidades entre superfícies.
- **MEDIUM — ambiente e névoa:** atmosfera não era zerada em dimensões sem skylight e god rays não eram bloqueados com câmera submersa. Esses guards foram repostos. O multiplicador de névoa nativa só altera a câmera no ar, preservando água/lava/neve em pó.
- **MEDIUM — integração com Direct antigo:** o bridge tentava registrar efeitos antes de verificar se a etapa world existia e podia repetir uma liberação incompleta por falta do método de névoa. O backend agora anuncia `world-post-v1`; o bridge exige essa capacidade para packs e libera corretamente solicitações antigas.
- **LOW — diagnóstico:** um pack recém-carregado era anunciado como DX12 ativo antes de produzir uma imagem. O estado inicial agora indica espera. Erros de reflexão mostram a causa original.

## Compilação e limites

Java/C++ e HLSL são compilados sem executar testes, native smoke, Minecraft ou benchmarks. Revisão e compilação não comprovam comportamento no driver ou correção visual.

Os avisos X4000 do FXC nas funções dos shaders portados permanecem sob investigação; não foram suprimidos nem declarados resolvidos. A ponte world ainda transfere cor/profundidade entre GL e DX12 e sincroniza a cena. O HLSL avulso de terreno tem escopo diferente do pack world; não substitui por si só todos os efeitos da cena.

Artefatos corrigidos: `direct-1.7.1.jar`, `raydiance-1.6.1.jar` e `Lus-DX12.zip`. Sem instalação automática na instância.
