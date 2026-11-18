# Decisões técnicas (ADRs)

Este arquivo registra **o que foi escolhido, por quê, o que foi descartado e o custo de cada
escolha**. As decisões estão em ordem de impacto; cada uma pode ser lida isoladamente.

Contexto comum: serviço de carteira em BRL, sem interface gráfica, consumido por outros sistemas via
HTTP, com concorrência real e a exigência de que "saldo nunca negativo" e "nunca aplicar duas vezes"
valham **no banco**.

---

## ADR-1: A transação e o lock pertencem ao banco, não ao Spring

**Decisão.** `PKG_WALLET.CREATE_WALLET` e `PKG_WALLET.REGISTER_TRANSACTION` abrem, confirmam
(`COMMIT`) e desfazem (`ROLLBACK`) a própria transação. A aplicação chama o procedure sem
`@Transactional`, com o pool em `auto-commit=false`, e nunca segura o lock da carteira entre
requisições.

**Alternativas consideradas.**

| Alternativa | Por que não |
|---|---|
| `@Transactional` no serviço Java | a fronteira transacional fica em duas linguagens; qualquer `commit` implícito do pool (ou um `auto-commit=true` mal configurado) libera o `FOR UPDATE` antes do `UPDATE` do saldo |
| Procedure com `PRAGMA AUTONOMOUS_TRANSACTION` | o commit autônomo é invisível para o chamador, dificulta composição (transferência entre carteiras precisa de uma única transação) e esconde o custo do commit |
| Deixar o commit para o driver/JDBC | comportamento depende do pool e do `auto-commit`; o `SELECT ... FOR UPDATE` deixa de proteger a atualização |

**Consequências.**

* Positivo: a unidade de trabalho que protege a invariante está no mesmo lugar que a invariante;
  nenhum cliente (API, batch, outro sistema) consegue aplicar meia operação; o lock dura exatamente
  a chamada.
* Positivo: o `COMMIT` explícito também libera o lock no caminho de replay idempotente, evitando
  segurar a carteira enquanto o cliente lê a resposta.
* Negativo: o procedure **não pode ser composto** com outras escritas na mesma transação. Se um dia
  for necessário gravar efeitos colaterais no mesmo commit, eles precisam entrar no package (ou
  migrar para a abordagem de transação do chamador).
* Negativo: `ROLLBACK` dentro do package descarta qualquer trabalho pendente na conexão. É seguro
  aqui porque nenhum outro componente escreve na mesma conexão; está documentado no javadoc da
  classe e no cabeçalho do package.
* Restrição operacional: `spring.datasource.hikari.auto-commit=false` é obrigatório. Com
  auto-commit ligado, o Oracle confirmaria logo após o `SELECT ... FOR UPDATE` e o lock cairia antes
  do `UPDATE`. Está no `application.yml` com comentário explicando.

---

## ADR-2: Idempotência por carteira, com fingerprint do payload e constraint única

**Decisão.** A chave é `(WALLET_ID, IDEMPOTENCY_KEY)`, **escopo por carteira**, e é obrigatória.
Ao lado dela guardamos `REQUEST_HASH` = SHA-256 de `tipo|valor normalizado|descrição trimada`.

* mesma chave + mesmo hash ⇒ devolve o resultado original (`replayed = true`), saldo intacto;
* mesma chave + hash diferente ⇒ `ORA-20003` / HTTP 409;
* `UNIQUE (WALLET_ID, IDEMPOTENCY_KEY)` garante a regra mesmo se alguém escrever fora do package.

**Alternativas consideradas.**

| Alternativa | Por que não |
|---|---|
| Tabela separada de idempotência | mais um objeto para manter consistente com o extrato; a chave já pertence naturalmente ao lançamento e a `UNIQUE` resolve a corrida |
| Chave global (não por carteira) | impede que dois clientes usem a mesma chave em carteiras diferentes sem motivo; o escopo por carteira é o mais próximo do domínio |
| Ignorar o payload e só comparar a chave | aceitaria silenciosamente uma segunda operação diferente como "retry", exatamente o bug que o enunciado pede para evitar |
| Retornar 200 sempre | perde a distinção entre "criei agora" (201) e "já existia" (200), que é útil para o cliente e aparece no header `Idempotent-Replayed` |
| Deixar a checagem só na aplicação | duas instâncias concorrentes passariam pela checagem e o banco aplicaria duas vezes |

**Consequências.**

* Positivo: retry de timeout/rede é seguro e barato (não abre lock: o caminho rápido é um `SELECT`
  pelo índice único).
* Positivo: dá para provar o comportamento com teste concorrente determinístico.
* Negativo: o cliente precisa gerar e reenviar a chave; documentado no contrato (8 a 80 caracteres,
  `[A-Za-z0-9._:-]`).
* Negativo: a descrição entra no hash, então reenviar a mesma chave mudando só a descrição retorna
  409. É intencional (payload diferente é conflito), mas exige disciplina do consumidor.

---

## ADR-3: Leitura por JDBC, escrita só por package (sem JPA)

**Decisão.** As leituras usam `JdbcTemplate`/`NamedParameterJdbcTemplate` com `RowMapper` explícito.
Não há entidades JPA, `ddl-auto`, `EntityManager` nem sessão de persistência.

**Alternativas consideradas.**

* **JPA/Hibernate para leitura**: o enunciado permite, mas traria um segundo modelo de dados
  (entidades anotadas), *dirty checking* e a tentação de usar `save()` para escrita, justamente o
  caminho que precisa passar pelo package. Para duas tabelas e três consultas, o custo não se paga.
* **JPA só para leitura + JDBC para escrita**: funciona, mas mistura dois gerenciamentos de conexão
  e dois modelos de mapeamento sem ganho real.

**Consequências.**

* Positivo: SQL explícito, sem N+1 e sem "mágica"; mapeamento `TIMESTAMP WITH TIME ZONE` →
  `OffsetDateTime` fica visível; nenhuma transação implícita criada por engano.
* Positivo: o schema é propriedade do Flyway, não do ORM (nada de `ddl-auto=update`).
* Positivo: como o usuário de runtime não é dono de nenhum objeto, todo SQL referencia
  `WALLET_OWNER.WALLET` / `WALLET_OWNER.PKG_WALLET`. Optei por **qualificar** em vez de criar
  sinônimos (exigiria `CREATE [PUBLIC] SYNONYM`, mais um privilégio) ou trocar o `CURRENT_SCHEMA`
  da sessão (esconderia o fato de que os privilégios vêm de grants). O schema é configurável por
  `DB_SCHEMA` e validado como identificador antes de ser concatenado.
* Negativo: consultas e mapeamentos são escritos à mão (mais código para novos campos).
  Se o read model crescer muito, o próximo passo natural é um `JdbcClient`/`SimpleJdbcCall` ou um
  read model dedicado.

---

## ADR-4: Dinheiro em `NUMBER(19,2)` + `BigDecimal`, com escala validada nos dois lados

**Decisão.** Valores monetários são `NUMBER(19,2)` no banco e `BigDecimal` na API, sempre com 2
casas. A validação existe nos três pontos: DTO (`@Digits(fraction = 2)`), domínio
(`Amounts.normalize`) e banco (`CHECK (amount = ROUND(amount,2))`, `CHECK (balance >= 0)`).

**Por quê.** Ponto flutuante não representa centavos; `scale` diferente de 2 em um `BigDecimal`
vindo do JSON produziria comparações e somas erradas. O CHECK no banco impede que um bug de
aplicação (ou um `INSERT` de DBA) grave `10.005`.

**Consequências.** Positivo: aritmética exata e invariantes testáveis no banco. Negativo: qualquer
cálculo futuro (juros, câmbio) precisa definir a política de arredondamento explicitamente: não há
arredondamento implícito aqui, valores com mais de 2 casas são **rejeitados**, não arredondados.

---

## ADR-5: Sequences explícitas, gaps aceitos

**Decisão.** `SEQ_WALLET` e `SEQ_WALLET_TRANSACTION` com `NEXTVAL` dentro do package, `CACHE` para
throughput e `NOORDER` para escalabilidade (RAC). Nenhuma dependência de contiguidade.

**Alternativas consideradas.**

* `IDENTITY`: mais simples, mas o valor gerado não é recuperável antes do `INSERT` sem
  `RETURNING`, e o controle fica menos explícito no package.
* UUID gerado na aplicação: perde ordenação local, ocupa mais espaço (RAW(16)) e transfere ao
  cliente a responsabilidade por um identificador que é do serviço.
* `MAX(id)+1`: corrida clássica; descartado.

**Consequências.** Positivo: geração sem round trip, chave disponível antes do `INSERT` (usada na
trilha de auditoria), comportamento previsível sob concorrência. Negativo: gaps por rollback e por
perda de cache, documentado no `COMMENT ON SEQUENCE` e irrelevante para o domínio, já que a ordem
do extrato vem de `occurred_at` + `id`, não do id isolado.

---

## ADR-6: Imutabilidade e auditoria por trigger, com a auditoria em schema separado

**Decisão.**

* `TRG_WALLET_TRANSACTION_IMMUTABLE`: `BEFORE UPDATE OR DELETE` no extrato → `ORA-20010`. Vale para
  qualquer sessão, inclusive o dono das tabelas (não é só um grant).
* `TRG_WALLET_BALANCE_AUDIT`: `AFTER UPDATE OF balance` grava em `WALLET_AUDIT.BALANCE_AUDIT` com
  saldo anterior, saldo novo, delta, autor da sessão e o id da transação causadora.
* A trilha fica no schema `WALLET_AUDIT`; `WALLET_OWNER` recebe apenas `INSERT`, `WALLET_APP` não
  recebe nada.
* A trilha também é append-only (`TRG_BALANCE_AUDIT_IMMUTABLE`, criada no bootstrap, `ORA-20011`).

**Como a trigger sabe qual transação causou a mudança.** O package mantém
`g_current_transaction_id` e o expõe por `pkg_wallet.current_transaction_id()`. A trigger lê esse
valor: as linhas aplicadas pela API saem com `transaction_id` preenchido; uma alteração manual de
DBA sai com `transaction_id` nulo, o que é, em si, um sinal de auditoria útil.

**Alternativas consideradas.**

| Alternativa | Por que não |
|---|---|
| Auditoria escrita pela aplicação | uma falha entre `UPDATE` e `INSERT` na auditoria deixaria saldo sem rastro; e o próprio app poderia "esquecer" |
| `DBMS_FGA`/Unified Auditing | registra SQL e sessão, não o par saldo anterior/novo; seria complementar, não substituto |
| Trigger gravando na mesma tabela `WALLET` | a trilha ficaria ao alcance do usuário de runtime (que tem `SELECT`) |
| `WALLET_AUDIT` com DML para o app | quebra o requisito de a aplicação não ter acesso à trilha |

**Consequências.** Positivo: a trilha é gerada pelo banco, na mesma transação, sem chance de
divergir; a aplicação não consegue ler nem escrever nela. Negativo: trigger `FOR EACH ROW` adiciona
custo por atualização de saldo (uma linha extra por operação, aceitável no volume esperado);
mudanças de esquema da trilha exigem o bootstrap (DBA), não só Flyway.

---

## ADR-7: Bootstrap com DBA e o resto em Flyway

**Decisão.** `db/bootstrap/01_schemas.sh` roda uma única vez, na criação do banco, como `SYS`, e
cria: os três usuários (senhas vindas de variáveis de ambiente), os grants de sistema mínimos, a
tabela/sequence da trilha no schema `WALLET_AUDIT`, o `GRANT INSERT` cruzado que o dono dos objetos
precisa e a trigger de imutabilidade da trilha. Todo o resto (tabelas, sequences, package, triggers
e **todos os grants de runtime**) está em migrações Flyway versionadas.

**Por que não tudo em Flyway.** Criar usuários e criar um objeto em outro schema exigem privilégios
de DBA (`CREATE USER`, `CREATE ANY TABLE`). Conceder isso ao usuário das migrações violaria o
princípio do menor privilégio. A alternativa seria rodar o Flyway como `SYSTEM`, o que é pior: o
schema de migração passaria a ser DBA. Também considerei duas instâncias de Flyway (uma por schema),
mas o custo (três datasources, ordenação entre elas, duas tabelas de histórico) não se justifica
para um único objeto.

**Consequências.** Positivo: o caminho de runtime tem privilégio mínimo real, verificável por teste
(`ORA-01031`, `ORA-00942`); DDL é 100% versionada e o bootstrap é idempotente. Negativo: existem
**dois** lugares de DDL (bootstrap e Flyway), mitigado por o bootstrap conter apenas o que é
estruturalmente impossível fazer sem DBA, e por estar documentado no topo do próprio script e no
README.

---

## ADR-8: Autenticação por API key, com falha ao subir sem chave

**Decisão.** Header `X-API-Key`, comparado em tempo constante (`MessageDigest.isEqual`, sem
short-circuit) contra a lista de `WALLET_API_KEYS`. Não há valor default: a aplicação recusa subir
sem chave configurada (`IllegalStateException` no `ApiKeyVerifier`). Health/info e a documentação
OpenAPI são públicos (health precisa ser público para o healthcheck do container).

**Alternativas consideradas.** JWT/OAuth (fora de escopo pelo enunciado, e exigiria um emissor de
tokens); mTLS (infra pesada para o teste); API key com hash no banco (melhor, mas o escopo do
teste não pede gestão de credenciais, ficou registrado como próximo passo).

**Consequências.** Positivo: simples de operar e testar, sem segredo versionado. Negativo: uma única
classe de credencial para todos os endpoints, sem escopos nem rotação automática; a comparação
constante evita *timing attack*, mas a chave viaja em texto claro a menos que o transporte seja TLS
(necessário em produção, fora do compose local).

---

## ADR-9: Mapa de erros

**Decisão.** Um único vocabulário de códigos, do banco à resposta HTTP: `RAISE_APPLICATION_ERROR`
usa `ORA-20001..ORA-20011` com o nome do erro na mensagem; `OracleErrorTranslator` mapeia o código
numérico para `ErrorCode` (enum que carrega status HTTP e título); o handler serializa em
`application/problem+json` (RFC 9457) com `type`, `title`, `status`, `code`, `message`, `path`,
`traceId`, `timestamp` e `violations[]`.

Escolhas de status:

| Situação | Status | Alternativa descartada e por quê |
|---|---|---|
| saldo insuficiente | **422** | `409`: o recurso não está em conflito, a requisição é que viola uma regra; `400` sugeriria erro de sintaxe. `422` deixa claro "entendi, mas não posso aplicar" |
| mesma chave, outro payload | **409** | `422`: aqui há conflito real com um estado já registrado (a chave tem outro resultado) |
| chave ausente/malformada | **400** | `428 Precondition Required` é elegante, mas menos difundido; o enunciado pede semântica e documentação, e 400 com `code` específico é mais previsível para o cliente |
| carteira inexistente | **404** | `400`: o recurso faz parte da rota, 404 é o que o cliente espera |
| deadlock/serialização | **503** | `409`: não há conflito de dados, é contenção; `503` sinaliza "repita" e combina com `Retry-After` (não enviado hoje) |
| falha inesperada | **500** com `traceId` | devolver a mensagem do Oracle: vazaria detalhe interno; o log tem o stack trace correlacionado |

**Consequências.** Positivo: o cliente programa contra `code`, não contra texto; o mesmo nome
aparece no banco, no log e na resposta. Negativo: o `message` carrega o prefixo do código e detalhes
em inglês vindos do PL/SQL (ex.: `INSUFFICIENT_FUNDS: balance ...`), enquanto os textos gerados pela
aplicação estão em português, inconsistência cosmética consciente: preferi manter a mensagem do
banco intacta a reescrevê-la, para não perder informação de diagnóstico.

---

## ADR-10: Paginação por offset, com limite duro

**Decisão.** `page`/`size` com `OFFSET ... ROWS FETCH NEXT ... ROWS ONLY` (Oracle 12c+), default 20,
máximo 100 (configurável), ordenação fixa `occurred_at DESC, id DESC`. `size` fora da faixa é
**erro 400**, não é ajustado silenciosamente.

**Alternativas consideradas.** Keyset pagination (`WHERE (occurred_at, id) < (:lastSeen...)`) é mais
eficiente em tabelas grandes e não sofre com inserções durante a navegação, mas exige que o cliente
carregue um cursor, e o enunciado pede paginação simples e o extrato é consultado por período.
Ficou registrado como evolução.

**Consequências.** Positivo: contrato simples, `totalElements` correto (uma consulta `COUNT`).
Negativo: `OFFSET` grande custa leitura; mitigado pelo limite de 100 e pelo índice composto.

---

## ADR-11: O que eu faria com mais tempo

Em ordem de valor, e sem mudar o desenho atual:

1. **Transferência entre carteiras** no mesmo package, com lock ordenado por `id` (evita deadlock
   entre A→B e B→A), duas linhas de extrato e uma chave de idempotência por transferência.
2. **Limite diário de débito** com agregação no banco e tabela de controle por dia, e a regra precisa
   estar no mesmo lugar que o saldo para não abrir janela de corrida.
3. **Job de conciliação (`DBMS_SCHEDULER`)** comparando `SUM(extrato) = saldo` e registrando
   divergências na auditoria; a trilha já tem todos os dados necessários.
4. **Keyset pagination** no extrato e projeção `content` sem `description` quando não pedida.
5. **Observabilidade**: métricas Micrometer por resultado (`applied`, `replayed`, `rejected`,
   `conflict`), contador de retries por `ORA-00060`, e logs estruturados em JSON.
6. **Teste de carga (k6)** com N operações concorrentes por carteira, imprimindo o confronto entre
   saldo final, extrato e auditoria; o teste de integração já faz isso em escala pequena (20/8).
7. **API keys com hash e escopos**, rotação e `X-API-Key` por cliente, mantendo o filtro atual.
8. **`Retry-After`** nas respostas 503 e um retry limitado no cliente para deadlock.
9. **Partição por mês** em `WALLET_TRANSACTION` quando o volume justificar, mantendo o índice local.

---

## ADR-12: Mock maker subclass nos testes

**Decisão.** `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` contém
`mock-maker-subclass`.

**Por quê.** O mock maker inline (default do Mockito 5) instrumenta classes com um agente Byte Buddy
instalado por self-attach via processo externo. Em ambientes onde a criação desse processo é
restrita (containers, sandboxes, políticas de endpoint), a suíte falha na inicialização com
"Could not self-attach to current VM". Os dublês deste projeto são interfaces e classes sem métodos
finais, então o maker de subclass cobre 100% dos casos, e o arquivo é de fato lido pelo Mockito
(verificado: um valor inválido derruba a inicialização).

**Consequência.** Se algum dia for necessário mockar classe `final` ou método `static`, será preciso
remover esse arquivo e garantir que o ambiente permita o agente. O Mockito ainda pode emitir um
aviso informativo de self-attach na primeira inicialização em JVMs recentes; ele não indica que a
instrumentação inline esteja sendo usada para estes testes.

---

## ADR-13: Testcontainers 1.21.4 (negociação de API com Docker recente)

**Decisão.** O pom.xml fixa 	estcontainers.version em 1.21.4 (o Spring Boot 3.5.6 gerencia
1.21.3).

**Por quê.** Com o Docker Engine 29 (Docker Desktop 4.88), o cliente embutido na 1.21.3 negocia uma
versão de API que o engine recusa com 400 Bad Request sem mensagem, e o Testcontainers conclui que
"não existe ambiente Docker" e os testes de integração são **ignorados** em vez de executados. A
1.21.4 negocia corretamente com o engine atual; com ela a suíte de integração roda de ponta a ponta
(verificado com gvenzl/oracle-free:23.26.4-slim-faststart).

**Consequência.** Um override de versão para acompanhar o engine; quando o Spring Boot passar a
gerenciar uma versão equivalente ou superior, o override pode ser removido. Sem Docker, os testes
continuam sendo ignorados de forma limpa (disabledWithoutDocker = true).
