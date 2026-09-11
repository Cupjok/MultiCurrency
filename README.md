# MultiCurrency

MultiCurrency is a Paper/Purpur plugin that adds any number of independent currencies, such as
Coins, Gems, Tokens or Event Points. Balance changes are transaction-safe, and other plugins can
use a public developer API.

It runs **alongside** your main economy (for example CMI). It does not replace that economy,
does not touch its balances, and does not register itself with Vault.

```text
CMI                      ── primary economy (unchanged)
MultiCurrency            ── Coins, Gems, Tokens, Event Points, … unlimited
Other plugins            ── use the MultiCurrency API, never its database
```

## Requirements

| | |
|---|---|
| Server | Purpur / Paper **26.2** |
| Java | **25** |
| Storage | SQLite (built in) or MariaDB 10.5+ / MySQL 8.0.16+ |
| Folia | Not supported |

On first start the server downloads HikariCP, the SQLite driver and the MariaDB driver from Maven
Central (Paper `libraries:` mechanism). The server needs internet access the first time.

## Installation

1. Put `MultiCurrency-<version>.jar` into `plugins/`.
2. Start the server once. This creates `plugins/MultiCurrency/config.yml`.
3. Configure your currencies and storage, then restart.

## Configuration

```yaml
server-id: "server-1"          # unique per server when sharing a MariaDB database

storage:
  type: sqlite                 # sqlite | mariadb | mysql
  table-prefix: "mc_"
  sqlite:
    file: "multicurrency.db"
  mariadb:
    host: "127.0.0.1"
    port: 3306
    database: "multicurrency"
    username: "multicurrency"
    password: "change-me"
    pool-size: 8
    properties: {}             # extra Connector/J properties, e.g. sslMode: verify-full

currencies:
  coins:
    display-name: "Coins"
    symbol: "🪙"
    decimals: 2
    starting-balance: "0"
    max-balance: "1000000000000"
    transfer-enabled: true
    format: "{symbol} {amount}"
  gems:
    display-name: "Gems"
    symbol: "💎"
    decimals: 0
    transfer-enabled: false
```

### Currency options

| Key | Default | Meaning |
|---|---|---|
| *(section key)* | – | Stable id: 1–32 characters from `a-z`, `0-9` and `_`. It is stored in the database, so **never rename it**. |
| `display-name` | id | Name shown to players. |
| `symbol` | `""` | Used by `format`. |
| `enabled` | `true` | `false` keeps balances but refuses every change. |
| `decimals` | `0` | 0–8 decimal places. Fixed once the currency is used (see below). |
| `starting-balance` | `0` | Balance of a new account. Put decimal values in quotes. |
| `max-balance` | largest representable | Upper limit per account. |
| `transfer-enabled` | **`false`** | Whether players may pay each other in this currency. |
| `format` | `{symbol}{amount}` | Placeholders are `{symbol}`, `{amount}`, `{name}` and `{id}`. |

You can add as many currencies as you want. `/currency reload` applies currency and message
changes. Storage changes need a restart. If any currency in the file is invalid, the reload is
refused as a whole and the previous configuration stays active; the errors are shown to the
sender and in the console.

**`decimals` locks the first time a currency is enabled.** Balances are stored as whole minor
units, so a later scale change would multiply or divide every balance. A currency that was never
enabled cannot hold balances, so its decimals can still be corrected. Once it has been enabled, a
changed value is refused, not applied: the currency is disabled (reads and writes) and a console
error is logged. Change the value back to use the currency again.

### Automatic config update

When a new plugin version adds settings or messages, they are added to your existing
`config.yml` on start, with their comments. Existing values are never changed or removed, and the
`currencies` section is never touched (deleted example currencies do not come back). The previous
file is kept in `plugins/MultiCurrency/backups/`. A `config.yml` that is not valid YAML stops the
plugin instead of starting it with default settings (which could point it at an empty database).

## In-game editor

`/currency editor` opens a menu to create, edit and delete currencies.

- **Cosmetic settings** (display name, symbol, format) are typed in chat and saved at once.
- **Economy settings** (enabled, player transfers, starting and maximum balance, decimals) need a
  confirmation screen that explains the effect.
- **New currencies** start disabled. Their decimals can be changed until they are enabled for the
  first time; enabling asks for confirmation because it locks them.
- **Delete** removes the currency from `config.yml` after a separate confirmation screen that shows
  the number of accounts and the total supply. Balances and history stay in the database; creating
  the same id again restores them (its decimals are then fixed to the stored value).

Safety rules: every change is re-checked against the permission, validated with the same rules as
`/currency reload` and only then written. The old file is backed up (`backups/`, newest 20 kept)
and the new one replaces it atomically. If `config.yml` was edited by hand and not reloaded yet,
the editor refuses to save until you run `/currency reload`, so manual edits are never lost or
applied by surprise. Menu items cannot be taken out of the menu. Chat answers to the editor's
questions are not shown in public chat. Every change is logged to the console and to
`plugins/MultiCurrency/editor-audit.log`. Confirm buttons ignore clicks right after the screen opens,
so a double click cannot confirm by accident.

Text input refuses formatting tags (`<red>`), colour codes (`&c`, `§c`) and invisible characters,
because placeholder output reaches other plugins that may interpret them.

The editor changes this server's `config.yml` only. When several servers share one database, copy
the change to the other servers' `config.yml` and run `/currency reload` there. A server whose
decimals disagree with the stored ones disables that currency instead of misreading balances.

## PlaceholderAPI

Registered automatically when PlaceholderAPI is installed.

| Placeholder | Example |
|---|---|
| `%multicurrency_balance_<id>%` | `1,234.50` |
| `%multicurrency_balance_formatted_<id>%` | `🪙 1,234.50` (the currency's `format`) |
| `%multicurrency_balance_raw_<id>%` | `1234.50` (plain number, for sorting) |
| `%multicurrency_name_<id>%`, `%multicurrency_symbol_<id>%` | `Coins`, `🪙` |
| `%multicurrency_top_name_<id>_<rank>%` | player at rank 1–10 |
| `%multicurrency_top_balance_<id>_<rank>%`, `..._top_balance_raw_<id>_<rank>%` | their balance |

Placeholders never wait for the database. They read a short cache (`placeholders.balance-cache-seconds`,
default 5; `top-cache-seconds`, default 30) and show `placeholders.loading` until the first value
arrives. A change made on this server updates the cached value immediately; a change made by
another server sharing the database appears within the cache time. The cache only feeds
placeholders: every balance change still reads and locks the database row.

## Commands

| Command | Permission | Default |
|---|---|---|
| `/cbal [player] [currency]` (also `/currency balance`) | `multicurrency.command.balance` (others: `.balance.others`) | all (others: op) |
| `/cpay <player> <currency> <amount>` (also `/currency pay`) | `multicurrency.command.pay` | all |
| `/currency list` | `multicurrency.command.list` | all |
| `/currency info <currency>` | `multicurrency.command.info` | all |
| `/currency top <currency> [page]` | `multicurrency.command.top` | all |
| `/currency history [player] [currency]` | `multicurrency.command.history` (others: `.history.others`) | all (others: op) |
| `/currency give <player> <currency> <amount> [reason]` | `multicurrency.admin.give` | op |
| `/currency take <player> <currency> <amount> [reason]` | `multicurrency.admin.take` | op |
| `/currency set <player> <currency> <amount> [reason]` | `multicurrency.admin.set` | op |
| `/currency reload` | `multicurrency.admin.reload` | op |
| `/currency editor` | `multicurrency.admin.editor` (name, symbol, format) | op |
| | `multicurrency.admin.editor.economy` (enabled, transfers, decimals, starting/max balance) | op |
| | `multicurrency.admin.editor.create`, `multicurrency.admin.editor.delete` | op |

`multicurrency.admin` grants all admin permissions.

MultiCurrency deliberately does **not** take over `/pay`, `/bal` or `/money`, because CMI owns those
commands. Players use `/cpay` and `/cbal`.

`/cpay` works only for currencies with `transfer-enabled: true`. The service layer enforces this
rule, so the API cannot bypass it either (see below).

## Developer API

Add the API jar as a `provided` dependency. It is attached to every GitHub release as
`MultiCurrency-API-<version>.jar`. You can also build it with `mvn install` and depend on
`me.cupjok.multicurrency:multicurrency-api`. Add `softdepend: [MultiCurrency]` to your plugin
descriptor.

```java
MultiCurrencyApi api = Bukkit.getServicesManager().load(MultiCurrencyApi.class);
// or: MultiCurrencyProvider.get()

TransactionContext ctx = TransactionContext.of(Actor.plugin("MyShop"), "bought 16 diamonds")
        .withIdempotencyKey("myshop:order:" + orderId);   // optional, makes retries safe

api.withdraw(player.getUniqueId(), "gems", new BigDecimal("25"), ctx).thenAccept(result -> {
    if (result.success()) {
        // hop back to the main thread before touching the player/world
        Bukkit.getScheduler().runTask(plugin, () -> giveItems(player));
    } else {
        // result.failureReason(): INSUFFICIENT_FUNDS, CURRENCY_DISABLED, ...
    }
});
```

| Method | Purpose |
|---|---|
| `currencies()`, `currency(id)`, `isEnabled(id)` | Discover currencies |
| `balance(uuid, id)` | Current balance. A new account reports the starting balance. |
| `has(uuid, id, amount)` | Advisory check only. To charge a player, call `withdraw`. |
| `deposit` / `withdraw` / `set` | System/plugin balance changes |
| `transfer(from, to, id, amount, ctx)` | Player-to-player transfer. It always respects `transfer-enabled` and has no override. |
| `history(uuid, id, limit)`, `top(id, limit, offset)` | Audit ledger and leaderboard |

Rules:

- Every method returns a `CompletableFuture`. The future completes on a MultiCurrency database
  thread. **Never `join()` a future on the main thread.**
- Mutations do not throw for business failures. Check `TransactionResult.success()` and
  `failureReason()`.
- Amounts are `BigDecimal`. If an amount has more decimal places than the currency allows, the
  call fails with `INVALID_PRECISION`. The amount is never rounded.
- `withdraw` checks the balance and debits it in one atomic step. Do **not** call `has()` first and
  then `withdraw()` expecting that to be safe. Call `withdraw()` and handle `INSUFFICIENT_FUNDS`.
- `OUTCOME_UNKNOWN` means the database failed during commit. Retry with the same idempotency key
  to find out safely: the retry returns `DUPLICATE_TRANSACTION` if the first attempt was applied.

## Storage

- **SQLite:** one file in the plugin folder, WAL mode, `synchronous=FULL`, one connection.
  Use it for a single server.
- **MariaDB/MySQL:** InnoDB, HikariCP pool, `READ COMMITTED`. Several servers can safely share
  one database. Correctness comes from database row locks and conditional writes, not from
  JVM-local locks.

**MySQL 8** uses the `caching_sha2_password` login by default. Over a plain connection the driver
then fails with `RSA public key is not available client side`. Use TLS, or allow key retrieval on a
trusted private network only:

```yaml
storage:
  type: mysql
  mariadb:
    properties:
      sslMode: verify-full        # TLS with a trusted certificate (recommended)
      # sslMode: trust            # TLS with a self-signed certificate
      # allowPublicKeyRetrieval: true   # no TLS; trusted private network only
```

Tables (with the default prefix `mc_`):

| Table | Contents |
|---|---|
| `mc_balances` | One row per player per currency. The value is a `BIGINT` count of minor units with `CHECK (balance >= 0)`. |
| `mc_transactions` | Append-only ledger: id, idempotency key, currency, type, actor, account, counterparty, amount, balances before and after, reason, server, time |
| `mc_currencies` | The scale each currency's balances are stored with |
| `mc_players` | Last known player names, for offline lookups and leaderboards |
| `mc_schema_version` | Migration state |

## Transaction-safety principles

1. **No check-then-act.** Each change is one database transaction. The transaction locks the
   balance row (`SELECT … FOR UPDATE`), checks it, writes it with a compare-and-set `UPDATE`, and
   appends a ledger row before it commits.
2. **Atomic transfers.** The debit, the credit and the ledger entry commit together or not at all.
   Rows are locked in a fixed order, so opposite transfers cannot deadlock. Deadlocks and lock
   timeouts are retried from scratch.
3. **Exact numbers.** Balances are `long` minor units. Amounts are parsed as `BigDecimal`.
   Overflow, negative values, excess precision and huge exponents are all rejected.
4. **Fail closed.** When storage is unavailable or its state is unclear, the operation is refused,
   not guessed. A failed commit is reported as `OUTCOME_UNKNOWN`, never as success.
5. **Idempotency.** An optional idempotency key has a unique index, so a key applies at most once,
   even under concurrent retries from several servers.
6. **Never on the main thread.** All SQL runs on a bounded MultiCurrency executor. The storage
   layer throws if it is called from the server thread.

These properties have regression tests: concurrent withdrawal, double-spend and duplicate-request
attacks, run on SQLite and on MariaDB with two independent service instances. Fault-injection
tests cover every step of a transaction. A mutation check showed that removing the row lock and
the compare-and-set makes these tests fail.

## CMI coexistence

- MultiCurrency is not a Vault economy provider and never calls CMI.
- MultiCurrency uses its own tables and its own command names (`/cpay`, `/cbal`, `/currency`).
- CMI money and MultiCurrency currencies are completely separate.

## Building

```bash
# Java 25
mvn clean package
# plugin: multicurrency-plugin/target/MultiCurrency-<version>.jar
# API:    multicurrency-api/target/MultiCurrency-API-<version>.jar
```

The optional MariaDB integration tests run when `MC_TEST_MARIADB_HOST` is set:

```bash
MC_TEST_MARIADB_HOST=127.0.0.1 MC_TEST_MARIADB_USER=... MC_TEST_MARIADB_PASSWORD=... \
MC_TEST_MARIADB_DB=multicurrency_test mvn test
```

## Limitations

- No Bukkit events for transactions yet.
- No Vault bridge, by design: MultiCurrency does not act as the primary economy.
- The in-game editor edits the local `config.yml` only; servers sharing a database need the same
  change in their own file.
- Deleting a currency keeps its balances and history in the database. There is no purge.
- Folia is not supported.
- API and command balance reads are not cached (one indexed query each). Only placeholders use a cache.

## License

MIT
