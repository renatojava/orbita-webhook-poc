# Órbita CRM: webhook do WhatsApp sem duplicidade e sem perda

Recorte da missão de código do Desafio do Board. Ataca os problemas 1 (mensagens duplicadas)
e 2 (fila que trava e mensagens sem registro).

## Como rodar

Precisa só de Docker. Para rodar os testes, também Java 25.

```bash
./mvnw verify          # 40 testes, 36 deles com Postgres real (Testcontainers) e HTTP real
./scripts/demo.sh      # sobe 3 pods + balanceador e executa a demo, incluindo kill -9 em um pod
docker compose down -v # limpa o ambiente
```

Para subir sem a demo: `docker compose up --build`. O balanceador fica em `localhost:8080`
(porta ocupada? `ORBITA_PORT=18080 ./scripts/demo.sh`).

## Arquitetura: vários pods, nenhum estado em memória

```
                    ┌──────────── nginx (no Kubernetes: Service / ALB) ────────────┐
Meta ── POST ──────▶│      app1            app2            app3      (mesma imagem)│
                    └────────┬────────────────┬────────────────┬───────────────────┘
                             │  webhook + workers em todos os pods │
                             ▼                ▼                ▼
                    ┌──────────────────── Postgres ────────────────────────────────┐
                    │ fila · histórico · janelas · outbox · bucket de envio · leases│
                    └───────────────────────────────────────────────────────────────┘
       migrate (Job/initContainer): aplica o schema uma vez, antes de os pods subirem
```

- **Os pods não conversam entre si nem guardam estado.** Toda a coordenação passa pelo Postgres:
  `SKIP LOCKED` divide a fila, a trava na linha do cliente faz o limite valer para o cliente e não por pod,
  e o relógio usado é sempre o `now()` do banco, nunca o do pod.
- **Matar um pod não perde nada.** O que ele estava processando volta para a fila quando o lease expira,
  e outro pod termina.
- **Escalar é subir réplicas.** Nada muda no código. O limite real é a soma dos pools de conexão:
  N pods × pool tem de caber no `max_connections` do banco (com muitos pods, usar RDS Proxy ou PgBouncer).
- **Migração com Flyway, num passo separado.** O mesmo jar roda em modo migração
  (`--orbita.workers.enabled=false`, sem servidor web) antes dos pods, e os pods sobem com
  `SPRING_FLYWAY_ENABLED=false`. Comecei com Liquibase e, com 3 pods subindo juntos num banco vazio,
  ele falhou ao criar as próprias tabelas de controle. O Flyway trava a migração com um advisory lock do
  Postgres: testei 3 pods migrando ao mesmo tempo, 3 vezes, e em todas um migrou, os outros esperaram e
  os 3 subiram. Mesmo assim, o passo separado continua sendo o padrão: a migração roda uma vez, falha
  antes do rollout e não depende do tempo de boot de cada pod.
- **Desligamento gracioso.** No SIGTERM de um rollout, o pod para de pegar trabalho e espera os envios
  em andamento (até 20 s).
- **Única variável em memória:** `inFlight` no `OutboundDispatcher`, que evita que o mesmo pod sirva
  o mesmo cliente duas vezes ao mesmo tempo. É só um atalho local. A correção vem das travas no banco.

## Onde está a idempotência

Tudo no banco, com `UNIQUE` e `ON CONFLICT`. Nada de "verifica e depois grava" nem de cache em memória,
porque com vários pods só o banco consegue decidir quem chegou primeiro.

| Camada | O que garante | Onde |
|---|---|---|
| Histórico | O mesmo `id` de mensagem vira um único registro, mesmo com 50 reenvios simultâneos | `UNIQUE (tenant_id, wamid)` em `001-pipeline-schema.sql:43` + `ON CONFLICT DO NOTHING` em `DeliveryProcessor.kt:54` |
| Efeito colateral | Só mensagem nova abre ou estende a janela de resposta. Duplicata não faz nada | `if (inserted)` em `DeliveryProcessor.kt:61` |
| Resposta automática | Uma resposta por lote de mensagens, nunca duas | Chave `auto-reply:<wamid>` em `ReplyWindowCloser.kt:23` + `UNIQUE (tenant_id, idempotency_key)` em `001-pipeline-schema.sql:64` + `ON CONFLICT` em `OutboundRepository.kt:18` |
| Vínculo mensagem → resposta | Cada mensagem pertence a uma única resposta | `messages.reply_key`, gravado em `ReplyWindowRepository.kt:53` |
| Fila | Dois pods nunca pegam a mesma entrega ao mesmo tempo | `FOR UPDATE SKIP LOCKED` em `DeliveryRepository.kt:28` |
| Pod que perdeu o lease | Não sobrescreve o resultado de quem retomou o trabalho | `attempts = ?` (fencing) em `DeliveryRepository.kt:61` |
| Envio para a Meta | Resultado incerto não é reenviado | `flagExpiredSendsAsUncertain` em `OutboundRepository.kt:91` |
| Limite de envio | O limite vale para o cliente, somando todos os pods | `FOR NO KEY UPDATE` na linha do cliente em `OutboundRepository.kt:52` |

As 3 primeiras camadas rodam na mesma transação, junto com o status da entrega.

## O fluxo

```
Meta → POST /webhooks/whatsapp → valida HMAC → grava em webhook_deliveries → 200
                                                      │
                 worker (SKIP LOCKED), em 1 transação ▼
                 messages (UNIQUE) + reply_windows (1 por contato) + DONE
                                                      │
                 janela fecha, em 1 transação         ▼
                 1 outbound_messages (UNIQUE) para N mensagens
                                                      │
                 dispatcher por cliente, com limite   ▼
                 WhatsAppGateway → SENT | retry | DEAD | UNCERTAIN
```

## Decisões

| Decisão | Por quê | O que custa |
|---|---|---|
| **Postgres como fila**, em vez do Redis | Durável e transacional com os dados, com uma peça a menos. O pico previsto é de 25 msg/s, e a PoC aguentou 500 msg/s | Depende do banco e exige limpar linhas antigas. Se passar de milhares de msg/s, a fila migra para SQS |
| **200 só depois de gravar** | Com o banco fora, responde 503 e o reenvio da Meta vira a rede de segurança. Assim nunca confirma o que não guardou | A resposta depende da latência de escrita |
| **`UNIQUE` + `ON CONFLICT`**, sem "verifica e depois grava" | É o banco que decide quem grava, de forma atômica, mesmo com reenvios simultâneos | Nenhum relevante |
| **Histórico, janela e status na mesma transação** | Uma queda no meio não deixa mensagem sem registro nem sem resposta prevista | Processamento precisa ser curto. A chamada de IA fica fora dessa transação |
| **Tentativas contadas ao pegar da fila**, não ao falhar | Uma mensagem que derruba o processo nunca chega ao tratamento de erro. Contando depois, ela voltaria para sempre | Uma queda do worker consome uma tentativa |
| **Outbox: a linha existe antes do envio** | Nenhum envio fica sem registro. É a resposta direta ao "ninguém soube dizer quais" | Uma escrita a mais por envio |
| **Janela deslizante por contato, no Postgres** | "Oi", "quero saber" e "da formação" viram 1 resposta, em vez de 3. No produto, seriam 1 chamada de IA com o contexto inteiro. A janela sobrevive a queda | Toda resposta espera ao menos a pausa (5 s por padrão, premissa a validar com conversas reais) |
| **Teto de espera (20 s) na janela** | Quem não para de digitar também é respondido | A resposta pode sair no meio do raciocínio da pessoa |
| **Limite por cliente com token bucket no Postgres** | Vale para qualquer número de réplicas e é configurável por cliente | Uma trava curta na linha do cliente a cada lote |
| **Cada cliente com seu lote e sua thread virtual** | Campanha grande ou envio lento só atrasam o próprio cliente. Respostas passam à frente das campanhas | Nenhum relevante nesta escala |
| **Pods sem estado, coordenados pelo Postgres** | Qualquer pod atende qualquer requisição e qualquer trabalho. Escalar ou matar um pod não exige nada além de subir ou derrubar a réplica | O banco vira o ponto central: precisa de alta disponibilidade (Multi-AZ) e de conexões dimensionadas |
| **Kotlin** | É a linguagem que domino, e o desafio deixa livre | O produto é Node. Os mecanismos acima são SQL e valem em TypeScript, mas este código não entra direto lá |

## E se a fila precisar sair do Postgres? (SQS)

A PoC aguentou 500 msg/s no notebook, 20 vezes o pico previsto, então a troca não é para o evento.
Se um dia for preciso, **o SQS substitui só o transporte** (`webhook_deliveries`). O estado continua
no Postgres, porque o SQS não guarda estado consultável nem permite alterar uma mensagem já enfileirada.

| Mecanismo | No Postgres hoje | No SQS |
|---|---|---|
| Lease | `locked_until` | Visibility timeout: a mensagem some enquanto um consumidor trabalha |
| Backoff | `next_attempt_at` | `ChangeMessageVisibility` com o atraso calculado, antes de devolver |
| Tentativas e DLQ | `attempts` contado ao pegar | `maxReceiveCount` + redrive para a DLQ, também contado ao receber |
| Idempotência | `UNIQUE` + `ON CONFLICT` | **Continua no Postgres.** A deduplicação da fila FIFO só vale por 5 minutos |
| Limite por cliente | Bucket na linha do cliente | **Continua no Postgres** (ou Redis). O SQS não limita por cliente |

**A janela deslizante não cabe no SQS sozinho:** não dá para adiar uma mensagem que já está na fila.
O padrão é a mensagem de checagem:

1. Cada mensagem nova atualiza `due_at` da janela no Postgres, como hoje, e publica
   `{cliente, contato}` com `DelaySeconds` igual à pausa.
2. Quando a checagem chega, o consumidor compara com o banco. Se `due_at` ainda está no futuro, a
   janela foi estendida e outra checagem já está a caminho, então ela é descartada. Se venceu, a janela fecha.
3. O teto de 20 s já está no `due_at` (`LEAST`), então nada muda nele.

Cuidados: `DelaySeconds` por mensagem só existe na fila **Standard**, não na FIFO, e vai até 15 minutos.
A Standard entrega pelo menos uma vez e pode duplicar, o que não é problema aqui, porque fechar a janela
já é idempotente (trava a linha da janela e apaga). Para prazos longos ou que mudam muito, o EventBridge
Scheduler resolve, mas é mais uma peça para operar.

## O limite honesto: envio único não existe

Se o processo cair entre enviar para a Meta e registrar o resultado, não há como saber se a mensagem saiu.
Como o requisito é nunca responder duas vezes, nesse caso a mensagem vira **`UNCERTAIN`**: **não é reenviada**
e fica visível em `/internal/dead-letters`. O lead pode ficar sem resposta nesse caso raro, mas nunca recebe
duas, e alguém fica sabendo. Para campanhas, a regra poderia ser outra. No produto real, o webhook de status
da Meta confirmaria esses casos sozinho.

## Provas

| Cenário | Resultado | Onde |
|---|---|---|
| 50 requisições idênticas em paralelo | 50 respostas 200, 1 registro, 1 resposta, 49 duplicatas contadas | `WebhookIdempotencyIT` e demo |
| Assinatura ausente, errada ou corpo alterado | 401, nada enfileirado | `WebhookSecurityIT` |
| Corpo que nunca processa, ou número desconhecido | Vai para `DEAD` após N tentativas com backoff, com replay | `InboundFailureIT` |
| Falha no meio de um lote | Nada gravado pela metade | `InboundFailureIT` |
| Worker morto | Trabalho retomado quando o lease expira, com limite de tentativas | `InboundFailureIT` |
| 3 pods dividindo 1.500 mensagens, com 1 pod derrubado no meio | Todos os pods trabalharam, 1.500 registros, 1.500 respostas, 0 duplicatas | `MultiPodIT` |
| Limite de 30/s com 3 pods | 98 a 99 envios em ~3 s para um limite de 100, em 4 execuções: o limite é do cliente, não do pod | `MultiPodIT` |
| `kill -9` real em 1 de 3 pods, com tráfego pelo balanceador | 3.000 enviadas, 2.988 aceitas (o nginx desviou para os outros pods), **0 aceitas perdidas** | demo |
| Campanha de 50/s com 3 pods | 501 envios em 10 s, divididos entre os 3 pods | demo |
| Banco fora do ar | 503, e o reenvio é gravado uma vez | demo |
| 3 mensagens seguidas da mesma pessoa | 1 resposta ligada às 3 | `ReplyWindowIT` e demo |
| Pessoa que não para de digitar | Respondida no teto de espera | `ReplyWindowIT` |
| Reenvio da Meta durante a janela | Não adia a resposta | `ReplyWindowIT` |
| 200 mensagens disputando com a janela fechando | Todas respondidas, nenhuma em duas respostas | `ReplyWindowIT` |
| Janela aberta quando o processo cai | A resposta sai depois | `ReplyWindowIT` |
| Campanha de 20 mil em um cliente | Outro cliente é respondido sem atraso além da janela | `TenantIsolationIT` e demo |
| 75 msg/s (3x o pico) por 30 s, com 3% de reenvios | 0 perdas, 0 duplicatas, p99 de 3 ms | `PeakLoadIT` |
| 500 msg/s por 15 s, com 3% de reenvios | 0 perdas, 0 duplicatas, p99 de 2 ms | `PeakLoadIT` com `-Dload.rate=500 -Dload.seconds=15` |

Também sabotei cópias do código para confirmar que os testes falham quando deveriam. Sem `UNIQUE` e
`ON CONFLICT`, sem rate limit, sem isolamento entre clientes, com janela que não desliza, sem teto de espera
e com duplicata adiando a janela, os testes correspondentes ficaram vermelhos. Sem a trava na linha do
cliente, os 3 pods enviaram 261 e 277 mensagens para um limite de 100, e o `MultiPodIT` reprovou.

**Sobre o `MultiPodIT`:** os 3 pods rodam na mesma JVM do teste e sobem um depois do outro, porque a
inicialização do Spring Boot divide estado de log e diretório temporário dentro de um processo. Depois de
subirem, rodam ao mesmo tempo. Pods de verdade, em processos separados, estão na demo com Docker.

**Limites dessas medições:** rodaram em um notebook, com o gerador de carga na mesma JVM, o envio à Meta
simulado e a janela de 300 ms do perfil de teste. A latência medida é a da resposta HTTP, não a da resposta ao lead. Isso indica que o desenho aguenta a carga, mas não é o teste
de capacidade na AWS, que precisa ser feito no ambiente real antes do evento.

## O que ficou de fora

- Chamada real à API da Meta: `FakeWhatsAppGateway` aceita tudo com latência fixa.
- `GET /webhooks/whatsapp` de verificação da Meta e webhooks de status.
- Autenticação em `/internal/*`, que **não pode ir para produção assim**.
- Limite de tamanho do corpo antes de ler a requisição, que ficaria no gateway/ALB.
- Mensagem que chega enquanto a resposta já está saindo: abre uma nova janela, e a primeira resposta não é cancelada.
- Janelas configuráveis por cliente: hoje o valor é único. Cliente que já responde por humano pode nem querer espera.
- Ordem entre mensagens do mesmo contato: o horário da Meta é gravado, mas retries podem inverter o processamento.
- Limpeza das linhas `DONE` e `SENT`, métricas e alertas.
- Tela para tratar `DEAD` e `UNCERTAIN`: hoje há listagem e replay de entradas pela API.

## Com mais tempo

1. Webhook de status da Meta para resolver os `UNCERTAIN` e medir entrega real.
2. Métricas e alertas: idade da fila, DLQ acima de zero, taxa de duplicatas.
3. Teste de carga na AWS com o limite real da Meta, incluindo falha do banco gerenciado.
4. Limite de gasto de IA por cliente, usando o mesmo mecanismo do limite de envio (problema 3).
5. Trazer estes mecanismos para o serviço Node, começando pelo webhook, sem reescrever o produto.

## Uso de IA

Implementado com ajuda do Claude Code. Os testes, as sabotagens e a demo foram executados de fato,
e os números acima vêm dessas execuções.
