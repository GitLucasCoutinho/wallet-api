# Wallet API

Serviço de carteira digital em BRL onde **as regras de consistência são resolvidas dentro do
Oracle**: o saldo nunca fica negativo, nenhuma operação é aplicada duas vezes e toda alteração de
saldo deixa trilha de auditoria, mesmo sob concorrência real.

A aplicação Java é uma camada de API sobre `PKG_WALLET`: lê com SQL simples (usuário só tem
`SELECT`), escreve **apenas** chamando o package PL/SQL (usuário só tem `EXECUTE`).

```
cliente ──HTTP──> Spring Boot ──JDBC──> PKG_WALLET (PL/SQL) ──> Oracle Database Free
                  (WALLET_APP)          (WALLET_OWNER, definer)      (+ WALLET_AUDIT)
```

---

## 1. Stack

| Item | Versão / escolha |
|---|---|
| Java | 21 (LTS) |
| Spring Boot | 3.5.6 (Web, Validation, JDBC, Actuator) |
| Banco | Oracle Database Free 23 (container `gvenzl/oracle-free:23.26.4-slim-faststart`) |
| Driver | `ojdbc11` 23.6.0.24.10 |
| Migrações | Flyway 11 (DDL, PL/SQL, triggers e grants versionados) |
| Documentação | springdoc-openapi 2.8.6 (`/swagger-ui.html`, `/v3/api-docs`) |
| Persistência | JDBC (`JdbcTemplate` + `CallableStatement`), sem JPA (ver [DECISIONS.md](DECISIONS.md)) |
| Testes | JUnit 5, AssertJ, MockMvc e Testcontainers (Oracle real) |

---

## 2. Como subir do zero

Pré-requisitos: **Docker** com pelo menos **4 GB de memória** disponíveis (menos que isso o Oracle
Free não inicia) e, apenas para rodar fora do container, **JDK 21 + Maven 3.9+**.

```bash
cp .env.example .env      # ajuste as senhas e a API key
docker compose up --build -d
docker compose ps         # aguarde app e oracle como "healthy"
curl http://localhost:8080/actuator/health
```

A primeira subida leva alguns minutos: o Oracle cria o banco, executa
`db/bootstrap/01_schemas.sh` (cria os três schemas) e a aplicação aplica as migrações Flyway antes
de começar a atender. Acompanhe com `docker compose logs -f app`.

* API: <http://localhost:8080/api/v1/wallets>
* Swagger UI: <http://localhost:8080/swagger-ui.html>
* OpenAPI JSON: <http://localhost:8080/v3/api-docs>
* Health: <http://localhost:8080/actuator/health> (público, usado pelo healthcheck)
* Oracle: `localhost:1521`, serviço `FREEPDB1`

Para zerar o banco: `docker compose down -v`.

### Rodar a aplicação fora do Docker (IDE / `mvn spring-boot:run`)

```bash
docker compose up -d oracle          # só o banco
export DB_URL='jdbc:oracle:thin:@//localhost:1521/FREEPDB1'
export DB_USER=WALLET_APP
export DB_PASSWORD='WalletApp_123456'
export DB_MIGRATION_USER=WALLET_OWNER
export DB_MIGRATION_PASSWORD='WalletOwner_123456'
export WALLET_API_KEYS=local-dev-key
mvn spring-boot:run
```

```powershell
docker compose up -d oracle
$env:DB_URL='jdbc:oracle:thin:@//localhost:1521/FREEPDB1'
$env:DB_USER='WALLET_APP'
$env:DB_PASSWORD='WalletApp_123456'
$env:DB_MIGRATION_USER='WALLET_OWNER'
$env:DB_MIGRATION_PASSWORD='WalletOwner_123456'
$env:WALLET_API_KEYS='local-dev-key'
mvn spring-boot:run
```

### Variáveis de ambiente

| Variável | Obrigatória | Descrição |
|---|---|---|
| `ORACLE_PASSWORD` | sim (container) | senha do `SYS` do container, usada só no bootstrap |
| `WALLET_OWNER_PASSWORD` | sim | dono dos objetos e usuário das migrações |
| `WALLET_AUDIT_PASSWORD` | sim | dono da trilha de auditoria |
| `WALLET_APP_PASSWORD` | sim | usuário de runtime da aplicação (sem DDL, sem DML) |
| `WALLET_API_KEYS` | sim | lista de API keys separadas por vírgula; **a aplicação não sobe sem isso** |
| `DB_URL` | não | padrão `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |
| `DB_USER` / `DB_PASSWORD` | não | padrão `WALLET_APP` / vazio |
| `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` | não | padrão `WALLET_OWNER` / vazio |
| `DB_SCHEMA` | não | schema dono dos objetos, referenciado qualificado no SQL (padrão `WALLET_OWNER`) |
| `DB_POOL_SIZE` | não | conexões Hikari (padrão 20) |
| `WALLET_MAX_PAGE_SIZE` | não | tamanho máximo de página do extrato (padrão 100) |
| `SERVER_PORT` | não | padrão 8080 |
| `LOG_LEVEL` | não | nível de log de `com.wallet.api` (padrão INFO) |

Nenhuma senha é versionada: o `.env` está no `.gitignore` e os schemas são criados com senhas vindas
do ambiente. Senhas do Oracle devem ter 8+ caracteres; evite aspas e espaços.

---

## 3. API

Todos os endpoints usam `X-API-Key`. Erros seguem **RFC 9457** (`application/problem+json`) com um
`code` estável, e toda resposta carrega `X-Request-Id`.

### 3.1 Criar carteira

```bash
curl -i -X POST http://localhost:8080/api/v1/wallets \
  -H 'X-API-Key: local-dev-key' \
  -H 'Content-Type: application/json' \
  -d '{"holderName":"Ana Souza","holderDocument":"12345678900"}'
```

```http
HTTP/1.1 201 Created
Location: http://localhost:8080/api/v1/wallets/1000
X-Request-Id: 1f8c0f9e-...

{
  "id": 1000,
  "holderName": "Ana Souza",
  "holderDocument": "12345678900",
  "currency": "BRL",
  "balance": 0.00,
  "createdAt": "2026-01-02T13:00:00.123-03:00"
}
```

### 3.2 Consultar saldo

```bash
curl -s http://localhost:8080/api/v1/wallets/1000 -H 'X-API-Key: local-dev-key'
```

### 3.3 Registrar operação (crédito/débito)

`Idempotency-Key` é **obrigatório**: 8 a 80 caracteres, `[A-Za-z0-9._:-]`.

```bash
curl -i -X POST http://localhost:8080/api/v1/wallets/1000/transactions \
  -H 'X-API-Key: local-dev-key' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: pix-2026-01-02-0001' \
  -d '{"type":"CREDIT","amount":150.75,"description":"Pix recebido"}'
```

```http
HTTP/1.1 201 Created
Idempotent-Replayed: false

{
  "walletId": 1000,
  "transactionId": 42,
  "type": "CREDIT",
  "amount": 150.75,
  "balanceAfter": 150.75,
  "occurredAt": "2026-01-02T13:05:10.442-03:00",
  "replayed": false
}
```

Repetindo a **mesma** requisição (mesma chave, mesmo payload):

```http
HTTP/1.1 200 OK
Idempotent-Replayed: true

{ "walletId": 1000, "transactionId": 42, "amount": 150.75, "balanceAfter": 150.75, "replayed": true }
```

Mesma chave com **payload diferente** (ex.: `150.76`):

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{
  "type": "https://wallet-api/errors/idempotency-conflict",
  "title": "Conflito de chave de idempotência",
  "status": 409,
  "code": "IDEMPOTENCY_CONFLICT",
  "message": "IDEMPOTENCY_CONFLICT: idempotency key already used with a different payload",
  "path": "/api/v1/wallets/1000/transactions",
  "traceId": "1f8c0f9e-...",
  "timestamp": "2026-01-02T13:06:00Z"
}
```

Débito sem saldo:

```http
HTTP/1.1 422 Unprocessable Entity

{ "code": "INSUFFICIENT_FUNDS", "status": 422, "message": "INSUFFICIENT_FUNDS: balance 150.75 is lower than the requested debit of 500.00", ... }
```

### 3.4 Extrato

```bash
curl -s 'http://localhost:8080/api/v1/wallets/1000/transactions?page=0&size=20&type=CREDIT&from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z' \
  -H 'X-API-Key: local-dev-key'
```

```json
{
  "content": [
    {
      "id": 42,
      "walletId": 1000,
      "type": "CREDIT",
      "amount": 150.75,
      "balanceBefore": 0.00,
      "balanceAfter": 150.75,
      "description": "Pix recebido",
      "occurredAt": "2026-01-02T13:05:10.442-03:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false,
  "hasPrevious": false
}
```

* Ordenação fixa: mais recente primeiro (`occurred_at DESC, id DESC`).
* `from`/`to` são ISO-8601 com offset e `to` é inclusivo; `type` aceita `CREDIT`/`DEBIT` em
  qualquer caixa.
* `size` fora de `1..100` retorna `400 INVALID_PARAMETER`; `page` negativo idem.

### 3.5 Catálogo de erros

| `code` | HTTP | Quando |
|---|---|---|
| `AUTHENTICATION_REQUIRED` | 401 | `X-API-Key` ausente |
| `INVALID_API_KEY` | 401 | chave não reconhecida |
| `VALIDATION_ERROR` | 400 | corpo inválido; `violations[]` diz campo e motivo |
| `MALFORMED_REQUEST` | 400 | JSON quebrado ou enum inválido no corpo |
| `MISSING_IDEMPOTENCY_KEY` | 400 | cabeçalho `Idempotency-Key` ausente/vazio |
| `INVALID_IDEMPOTENCY_KEY` | 400 | chave fora do formato (8 a 80, `[A-Za-z0-9._:-]`) |
| `INVALID_AMOUNT` | 400 | valor ausente, ≤ 0, com mais de 2 casas ou acima de `NUMBER(19,2)` |
| `INVALID_TRANSACTION_TYPE` | 400 | tipo diferente de `CREDIT`/`DEBIT` |
| `INVALID_HOLDER` | 400 | titular ausente ou acima do tamanho da coluna |
| `INVALID_DESCRIPTION` | 400 | descrição acima de 255 caracteres |
| `INVALID_PARAMETER` | 400 | paginação/período inválidos |
| `WALLET_NOT_FOUND` | 404 | carteira inexistente |
| `RESOURCE_NOT_FOUND` | 404 | rota inexistente |
| `METHOD_NOT_ALLOWED` | 405 | método não suportado na rota |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | `Content-Type` diferente de `application/json` |
| `IDEMPOTENCY_CONFLICT` | 409 | mesma chave, payload diferente |
| `INSUFFICIENT_FUNDS` | 422 | débito maior que o saldo |
| `CONCURRENT_MODIFICATION` | 503 | deadlock/serialização: seguro repetir |
| `DATABASE_UNAVAILABLE` | 503 | banco fora do ar |
| `LEDGER_IMMUTABLE` | 500 | tentativa de alterar/apagar lançamento (não deveria chegar ao cliente) |
| `INTERNAL_ERROR` | 500 | falha inesperada; use o `traceId` para achar o log |

O mesmo nome de `code` é usado dentro do banco (`ORA-20001..ORA-20011`), o que mantém o mapeamento
entre erro de negócio e HTTP em um único lugar auditável (`OracleErrorTranslator`).

### 3.6 Por que 422 para saldo insuficiente e 409 para conflito de chave

A requisição está **sintaticamente correta** (não é 400), a carteira existe e o cliente está
autenticado (não é 404/401), mas a operação viola uma regra de negócio do recurso, e 422 é o status
que expressa isso. `409` fica reservado para o conflito de estado entre a chave de idempotência já
registrada e o novo payload, que é exatamente o caso "o recurso está em um estado incompatível com
esta requisição". Alternativas e trade-offs em [DECISIONS.md](DECISIONS.md#adr-9-mapa-de-erros).

---

## 4. Modelo de dados

Tudo é criado por migração Flyway (`src/main/resources/db/migration`), nada de `ddl-auto`, nada
criado à mão.

### 4.1 Schemas

| Schema | Papel | Privilégios |
|---|---|---|
| `WALLET_OWNER` | dono das tabelas, sequences, package e triggers; usuário do Flyway | `CREATE TABLE/SEQUENCE/PROCEDURE/TRIGGER`, quota em `USERS` |
| `WALLET_APP` | usuário de runtime do Spring | `CREATE SESSION`, `SELECT` em `wallet`/`wallet_transaction`, `EXECUTE` em `pkg_wallet`. **Sem DML, sem DDL, sem acesso à auditoria** |
| `WALLET_AUDIT` | dona da trilha de saldo | `CREATE SESSION`; a trilha é `INSERT` apenas para o dono do package, leitura para conciliação |

Usuários e a trilha (que vive no schema de auditoria, fora do alcance do dono dos objetos) são
criados por `db/bootstrap/01_schemas.sh`, o único passo que exige DBA. O restante (tabelas,
sequences, package, triggers e todos os grants) é Flyway. Justificativa em
[DECISIONS.md](DECISIONS.md#adr-7-bootstrap-com-dba-e-o-resto-em-flyway).

Como `WALLET_APP` não é dono de nada, todo SQL da aplicação referencia os objetos qualificados
(`WALLET_OWNER.WALLET`, `WALLET_OWNER.PKG_WALLET`). O schema é configurável por `DB_SCHEMA` e
validado como identificador antes de entrar no SQL, sem sinônimos e sem trocar o
`CURRENT_SCHEMA` da sessão, o que deixaria o modelo de privilégios menos explícito.

### 4.2 Tabelas

**`WALLET`**: saldo autoritativo, uma linha por carteira.

| Coluna | Tipo | Regra |
|---|---|---|
| `ID` | `NUMBER(19)` | PK, `SEQ_WALLET.NEXTVAL` |
| `HOLDER_NAME` | `VARCHAR2(120 CHAR)` | `NOT NULL`, `TRIM` não vazio (CHECK) |
| `HOLDER_DOCUMENT` | `VARCHAR2(32 CHAR)` | opcional |
| `CURRENCY` | `CHAR(3 CHAR)` | `DEFAULT 'BRL'`, CHECK `= 'BRL'` |
| `BALANCE` | `NUMBER(19,2)` | `DEFAULT 0`; CHECK `>= 0` e `= ROUND(balance,2)` |
| `CREATED_AT` / `UPDATED_AT` | `TIMESTAMP(6) WITH TIME ZONE` | `systimestamp` |

**`WALLET_TRANSACTION`**: extrato append-only.

| Coluna | Tipo | Regra |
|---|---|---|
| `ID` | `NUMBER(19)` | PK, `SEQ_WALLET_TRANSACTION.NEXTVAL` |
| `WALLET_ID` | `NUMBER(19)` | FK → `WALLET(ID)` |
| `TYPE` | `VARCHAR2(10)` | CHECK `IN ('CREDIT','DEBIT')` |
| `AMOUNT` | `NUMBER(19,2)` | CHECK `> 0` e escala 2 |
| `BALANCE_BEFORE` / `BALANCE_AFTER` | `NUMBER(19,2)` | CHECK que prova a aritmética (`credit = before + amount`, `debit = before - amount`) |
| `IDEMPOTENCY_KEY` | `VARCHAR2(80 CHAR)` | `NOT NULL` + `UNIQUE (WALLET_ID, IDEMPOTENCY_KEY)` |
| `REQUEST_HASH` | `VARCHAR2(64 CHAR)` | SHA-256 do payload normalizado |
| `DESCRIPTION` | `VARCHAR2(255 CHAR)` | opcional |
| `OCCURRED_AT` | `TIMESTAMP(6) WITH TIME ZONE` | `systimestamp` |

Índice `IX_WALLET_TRANSACTION_WALLET_OCCURRED (WALLET_ID, OCCURRED_AT DESC, ID DESC)` atende o
extrato paginado; a constraint de unicidade já cria o índice usado na busca por idempotência.

**`WALLET_AUDIT.BALANCE_AUDIT`**: trilha de saldo, alimentada por trigger, inacessível para
`WALLET_APP`: `ID`, `WALLET_ID`, `TRANSACTION_ID` (nullable), `PREVIOUS_BALANCE`, `NEW_BALANCE`,
`DELTA`, `CHANGED_AT`, `CHANGED_BY`.

### 4.3 Objects de banco

| Objeto | Função |
|---|---|
| `SEQ_WALLET`, `SEQ_WALLET_TRANSACTION` | ids explícitos (`NOCACHE`/`CACHE`, `NOORDER`: gaps são esperados e irrelevantes) |
| `PKG_WALLET` | único caminho de escrita: `create_wallet`, `register_transaction` |
| `TRG_WALLET_TRANSACTION_IMMUTABLE` | bloqueia `UPDATE`/`DELETE` no extrato (`ORA-20010`) |
| `TRG_WALLET_BALANCE_AUDIT` | cada mudança de saldo gera uma linha em `BALANCE_AUDIT`, na mesma transação |
| `TRG_BALANCE_AUDIT_IMMUTABLE` (bootstrap) | trilha append-only |
| Grants (V6) | `WALLET_APP`: `SELECT` + `EXECUTE`, nunca DML |

---

## 5. Onde cada regra é garantida

| Regra | Mecanismo | Onde |
|---|---|---|
| Saldo nunca negativo | `SELECT ... FOR UPDATE` + checagem antes do débito + `CHECK (balance >= 0)` | `PKG_WALLET`, V1 |
| Nenhuma operação aplicada em dobro | dupla checagem da chave (antes e depois do lock) + `UNIQUE (wallet_id, idempotency_key)` | `PKG_WALLET`, V1 |
| Mesma chave com outro payload é conflito | `REQUEST_HASH` (SHA-256 do payload normalizado) comparado ao registrar | Java (`RequestFingerprint`) + `PKG_WALLET` |
| Saldo final = soma de créditos − débitos aplicados | extrato com `balance_before/after` + CHECK de consistência + linha de saldo atualizada na mesma transação | V1, `PKG_WALLET` |
| Extrato imutável | trigger `BEFORE UPDATE OR DELETE` (vale até para o dono do schema) | V5 |
| Trilha de auditoria completa | trigger `AFTER UPDATE OF balance` gravando no schema `WALLET_AUDIT`, com o id da transação causadora | V5 |
| Aplicação não escreve direto no banco | grants: só `SELECT` + `EXECUTE` | V6 |
| Operação atômica | `COMMIT`/`ROLLBACK` dentro do package: extrato + saldo + auditoria caem juntos | V4 |

---

## 6. Concorrência e idempotência (o coração)

```
POST /wallets/{id}/transactions
        │
        ▼
normaliza tipo/valor, valida Idempotency-Key, calcula o fingerprint do payload
        │
        ▼
PKG_WALLET.REGISTER_TRANSACTION
        │
        ├─ 1. busca (wallet_id, key): achou e mesmo hash  ──► devolve resultado original (replayed=1)
        │
        ├─ 2. SELECT * FROM wallet WHERE id = :id FOR UPDATE   ◄── ponto de serialização
        │
        ├─ 3. busca a chave DE NOVO (outra sessão pode ter aplicado enquanto esperávamos o lock)
        │       achou e mesmo hash ──► devolve o original e COMMIT (nada mudou)
        │       achou e hash diferente ──► ORA-20003 (409)
        │
        ├─ 4. débito? saldo < valor ──► ORA-20002 (422)
        │
        ├─ 5. INSERT wallet_transaction + UPDATE wallet.balance
        │       └─ trigger grava a trilha de auditoria
        │
        └─ 6. COMMIT (ou ROLLBACK + re-raise em qualquer erro)
```

Por que isso funciona sob concorrência:

* **Todas** as escritas de uma carteira passam pelo lock da linha de `WALLET`. Duas requisições
  simultâneas para a mesma carteira executam em série; carteiras diferentes não se bloqueiam (não
  há lock global, nem `LOCK TABLE`, nem deadlock entre duas carteiras distintas).
* A checagem de idempotência acontece **de novo** depois do lock. Se a requisição concorrente já
  tinha aplicado a chave, ela é devolvida como replay, e não existe janela "check-then-act".
* A `UNIQUE` é a última linha de defesa: se algum cliente escrever fora do package, o banco ainda
  recusa a duplicidade.
* O package é `AUTHID DEFINER` e controla a transação: o Java nunca vê um estado intermediário, e
  não existe transação aberta entre requisições (Hikari configurado com `auto-commit=false`, o
  commit é do procedure).
* O lock dura o tempo da chamada; a resposta HTTP não segura conexão além disso.

Provas automatizadas (`WalletApiIT`):

* 20 débitos simultâneos de `10.00` em uma carteira com `100.00` ⇒ exatamente 10 aceitos, 10
  recusados com 422, saldo final `0.00`, 10 linhas no extrato e 10 na auditoria, nenhuma negativa.
* 8 envios simultâneos da mesma operação/chave ⇒ **1** aplicado, 7 replays, mesmo `transactionId`,
  saldo afetado uma única vez.
* `UPDATE`/`DELETE` no extrato como `WALLET_OWNER` ⇒ `ORA-20010`.
* `WALLET_APP` tentando `INSERT`/`UPDATE`/`DELETE` ⇒ `ORA-01031`; lendo `WALLET_AUDIT` ⇒ `ORA-00942`.
* Trilha de auditoria conferida linha a linha (soma dos deltas = saldo; `transaction_id` presente
  nas operações da API e nulo em alteração manual de DBA).

---

## 7. Testes

```bash
mvn test      # 71 testes: domínio, serviço, contrato HTTP (MockMvc) - não precisa de banco
mvn verify    # acima + testes de integração com Oracle real (Testcontainers)
```

| Suíte | O que cobre |
|---|---|
| `AmountsTest`, `IdempotencyKeysTest`, `RequestFingerprintTest` | regras de valor (2 casas, > 0, limite da coluna), formato da chave, determinismo do fingerprint |
| `WalletApplicationServiceTest` | validação de entrada, 404, paginação/offset, normalização do payload, propagação de erros |
| `OracleErrorTranslatorTest` | `ORA-20xxx` → `ErrorCode`, extração da mensagem, deadlock/unique/indisponibilidade |
| `WalletControllerTest` | status HTTP, `Location`, header `Idempotent-Replayed`, corpo de erro RFC 9457, validação, 401/405/415, `X-Request-Id` |
| `WalletApiIT` | 18 testes de fluxo completo contra Oracle real: idempotência, concorrência, imutabilidade, privilégios, auditoria, filtros do extrato |

Os testes de integração sobem o container `gvenzl/oracle-free` com o mesmo bootstrap do compose e
deixam o Flyway aplicar as migrações: é o caminho de produção. Sem Docker eles são **ignorados**
(`@Testcontainers(disabledWithoutDocker = true)`).

> O mock maker do Mockito está fixado em `mock-maker-subclass`
> (`src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`): evita o self-attach do
> agente Byte Buddy, que falha em ambientes com restrição de processos/portas e não é necessário
> para os dublês usados aqui (interfaces e classes sem métodos finais).

---

## 8. Estrutura do projeto

```
wallet-api/
├── db/bootstrap/01_schemas.sh          # cria schemas, trilha e grants cruzados (DBA, uma vez)
├── docker-compose.yml                  # oracle + app, healthchecks, senhas por env
├── Dockerfile                          # build multi-stage, runtime non-root
├── .env.example                        # variáveis esperadas (nada secreto versionado)
├── src/main/resources/db/migration     # V1 tabelas, V2 sequences, V3/V4 package, V5 triggers, V6 grants
├── src/main/java/com/wallet/api/
│   ├── web/                            # controllers, DTOs, filtros, erros RFC 9457, OpenAPI
│   ├── application/                    # casos de uso (validação + orquestração)
│   ├── domain/                         # modelo, regras de valor, portas, exceções
│   ├── infrastructure/                 # JDBC: repositório, gateway do package, tradução de erros Oracle
│   └── config/                         # properties, OpenAPI, segurança
└── src/test/java/com/wallet/api/       # unitários, slice web e IT com Testcontainers
```

---

## 9. Decisões técnicas

Estão detalhadas, com alternativas e trade-offs, em **[DECISIONS.md](DECISIONS.md)**. Resumo:

1. A transação e o lock pertencem ao banco (`PKG_WALLET`), não ao Spring.
2. Idempotência por carteira, com fingerprint do payload e constraint única.
3. Leitura por JDBC; escrita só por package, sem JPA.
4. Dinheiro em `NUMBER(19,2)`/`BigDecimal`, com escala validada nos dois lados.
5. Sequences explícitas; gaps são aceitos e documentados.
6. Imutabilidade e auditoria via triggers, com auditoria em schema separado.
7. Bootstrap com DBA apenas para usuários/objetos cruzados; todo o resto em Flyway.
8. API key em tempo constante, sem default (falha ao subir sem chave).
9. Mapa de erros único (`code` do banco = `code` da API).

---

## 10. Escopo não implementado (e por quê)

O enunciado pede foco: "escopo menor, bem-feito e bem-defendido". Ficaram fora, conscientemente:

* múltiplas moedas, câmbio, juros, autenticação de usuário final (JWT/OAuth), agendamento,
  estorno/chargeback e frontend: fora de escopo pelo enunciado;
* transferência entre carteiras, limite diário de débito, job de conciliação com `DBMS_SCHEDULER`,
  teste de carga k6 e métricas Prometheus: itens "plus", não obrigatórios. O desenho atual
  (lock por linha, package transacional, extrato imutável) é a base direta para eles; o que eu
  faria em seguida está em [DECISIONS.md](DECISIONS.md#adr-11-o-que-eu-faria-com-mais-tempo).
