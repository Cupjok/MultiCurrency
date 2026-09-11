# MultiCurrency

[![Latest Release](https://img.shields.io/github/v/release/Cupjok/MultiCurrency?style=flat-square)](https://github.com/Cupjok/MultiCurrency/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-62b47a?style=flat-square)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-25-orange?style=flat-square)](https://adoptium.net/)
[![Platform](https://img.shields.io/badge/Platform-Paper%20%7C%20Purpur-blue?style=flat-square)](https://papermc.io/)
[![License](https://img.shields.io/github/license/Cupjok/MultiCurrency?style=flat-square)](https://github.com/Cupjok/MultiCurrency/blob/main/LICENSE)
[![Built with Claude Code](https://img.shields.io/badge/Built%20with-Claude%20Code-D97757?style=flat-square&logo=claude&logoColor=white)](https://claude.ai/)

**Unlimited independent, transaction-safe currencies for Minecraft Paper and Purpur servers.**

MultiCurrency lets you add as many separate currencies as you need — Coins, Gems, Tokens, Event Points, or anything else — while keeping your existing primary economy completely untouched.

> **MultiCurrency is a secondary currency system, not a replacement economy.**
> It does not replace CMI, does not modify its balances, and does not register as a Vault economy provider.

## ✨ Highlights

- 💰 **Unlimited currencies** — create as many independent currencies as your server needs.
- 🔒 **Transaction-safe** — atomic balance changes, transfers, row locking, compare-and-set updates, and idempotency support.
- 🌐 **Multi-server ready** — safely share a MariaDB/MySQL database across multiple servers.
- 🖥️ **In-game editor** — create, edit, enable, disable and delete currencies without manually editing YAML.
- 📊 **PlaceholderAPI** — balances, formatted values, currency information and top-player placeholders.
- 🔄 **Safe configuration reloads** — invalid currency configuration rejects the entire reload instead of partially applying it.
- 🗃️ **SQLite or MariaDB/MySQL** — SQLite for single-server setups, MariaDB/MySQL for shared storage.
- 🧩 **Developer API** — asynchronous `CompletableFuture` API with `BigDecimal` amounts and idempotency keys.
- 🛡️ **Fail-closed design** — uncertain database outcomes are reported instead of being guessed as successful.
- ⚡ **Async database operations** — SQL work never runs on the Minecraft main thread.

## 📦 Requirements

| Requirement | Version |
|---|---|
| Minecraft | **26.2** |
| Server | **Paper / Purpur** |
| Java | **25** |
| Storage | **SQLite**, **MariaDB 10.5+**, or **MySQL 8.0.16+** |
| Folia | ❌ Not supported |

> On first start, Paper's `libraries:` mechanism downloads HikariCP and the database drivers from Maven Central. Internet access is required for the initial startup.

## 🚀 Installation

1. Download the latest `MultiCurrency-<version>.jar` from [Releases](https://github.com/Cupjok/MultiCurrency/releases).
2. Put the plugin into your server's `plugins/` folder.
3. Start the server once.
4. Configure `plugins/MultiCurrency/config.yml`.
5. Restart the server.
6. Create your currencies and use `/currency list` to verify them.

For developers, `MultiCurrency-API-<version>.jar` is also attached to every release.

## 💎 Example Configuration

```yaml
server-id: "server-1"

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
    properties: {}

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

### Currency Options

| Option | Default | Description |
|---|---|---|
| `display-name` | Currency id | Name shown to players. |
| `symbol` | `""` | Symbol used by the currency format. |
| `enabled` | `true` | Disabled currencies keep their balances but reject changes. |
| `decimals` | `0` | Number of decimal places, from 0–8. Locks when the currency is first enabled. |
| `starting-balance` | `0` | Starting balance for new accounts. |
| `max-balance` | Maximum | Maximum balance per account. |
| `transfer-enabled` | `false` | Allows player-to-player payments. |
| `format` | `{symbol}{amount}` | Supports `{symbol}`, `{amount}`, `{name}` and `{id}`. |

Currency IDs are stable database identifiers. They may contain 1–32 lowercase characters from `a-z`, `0-9` and `_`. **Do not rename an existing currency ID.**

## 🎮 Commands

| Command | Purpose | Default permission |
|---|---|---|
| `/cbal [player] [currency]` | View a balance | `multicurrency.command.balance` |
| `/cpay <player> <currency> <amount>` | Pay another player | `multicurrency.command.pay` |
| `/currency list` | List currencies | `multicurrency.command.list` |
| `/currency info <currency>` | View currency information | `multicurrency.command.info` |
| `/currency top <currency> [page]` | View leaderboard | `multicurrency.command.top` |
| `/currency history [player] [currency]` | View transaction history | `multicurrency.command.history` |
| `/currency give <player> <currency> <amount> [reason]` | Give currency | `multicurrency.admin.give` |
| `/currency take <player> <currency> <amount> [reason]` | Take currency | `multicurrency.admin.take` |
| `/currency set <player> <currency> <amount> [reason]` | Set balance | `multicurrency.admin.set` |
| `/currency reload` | Reload configuration | `multicurrency.admin.reload` |
| `/currency editor` | Open the currency editor | `multicurrency.admin.editor` |

`multicurrency.admin` grants all administrative permissions.

MultiCurrency intentionally does **not** take over `/pay`, `/bal` or `/money`. Those commands remain available to your primary economy such as CMI.

## 🛡️ Transaction Safety

MultiCurrency is designed around database-level correctness rather than JVM-local locks.

1. **Atomic mutations** — balance checks and updates happen inside one database transaction.
2. **Row locking** — concurrent balance changes are serialized at the database level.
3. **Atomic transfers** — debit, credit and ledger entry commit together or not at all.
4. **Idempotency** — optional idempotency keys prevent the same transaction from being applied twice.
5. **Exact amounts** — amounts use `BigDecimal` at the API boundary and are stored as integer minor units.
6. **Fail closed** — storage failures never become false successes.
7. **Deadlock retry** — retryable database conflicts are restarted safely from the beginning.
8. **Async storage** — database work runs on a bounded MultiCurrency executor, never on the server thread.

The project includes regression and fault-injection tests covering concurrent withdrawals, duplicate requests, double-spend attempts and transaction failure paths.

## 🌐 Multi-Server Storage

For a single server, SQLite is simple and requires no external database.

For multiple servers, use MariaDB or MySQL and give each server a unique `server-id`:

```yaml
server-id: "survival-1"

storage:
  type: mariadb
  mariadb:
    host: "127.0.0.1"
    port: 3306
    database: "multicurrency"
    username: "multicurrency"
    password: "change-me"
```

Shared-database correctness comes from database transactions and row-level locking, not from locks inside a single Minecraft process.

### MySQL 8

MySQL 8 may require TLS or public-key retrieval for authentication. Prefer TLS on production networks:

```yaml
storage:
  type: mysql
  mariadb:
    properties:
      sslMode: verify-full
      # allowPublicKeyRetrieval: true  # trusted private network only; prefer TLS
```

## 🔌 PlaceholderAPI

If PlaceholderAPI is installed, MultiCurrency registers its expansion automatically.

| Placeholder | Example |
|---|---|
| `%multicurrency_balance_<id>%` | `1,234.50` |
| `%multicurrency_balance_formatted_<id>%` | `🪙 1,234.50` |
| `%multicurrency_balance_raw_<id>%` | `1234.50` |
| `%multicurrency_name_<id>%` | `Coins` |
| `%multicurrency_symbol_<id>%` | `🪙` |
| `%multicurrency_top_name_<id>_<rank>%` | Top player name |
| `%multicurrency_top_balance_<id>_<rank>%` | Top player balance |
| `%multicurrency_top_balance_raw_<id>_<rank>%` | Raw top-player balance |

Placeholder values use a short non-blocking cache. Normal balance operations and API reads are not cached.

## 🧩 Developer API

The API is provided as a separate `MultiCurrency-API-<version>.jar` and should be used as a `provided` dependency.

```java
MultiCurrencyApi api = Bukkit.getServicesManager().load(MultiCurrencyApi.class);

TransactionContext ctx = TransactionContext.of(
        Actor.plugin("MyShop"),
        "bought 16 diamonds"
).withIdempotencyKey("myshop:order:" + orderId);

api.withdraw(
        player.getUniqueId(),
        "gems",
        new BigDecimal("25"),
        ctx
).thenAccept(result -> {
    if (result.success()) {
        // Return to the Minecraft thread before touching players/world state.
    }
});
```

### API Rules

- Every API operation returns a `CompletableFuture`.
- Never call `join()` on the Minecraft main thread.
- Mutations report business failures through `TransactionResult`; they do not rely on exceptions for normal failures.
- Amounts use `BigDecimal` and are never silently rounded.
- Use `withdraw()` directly rather than calling `has()` and then `withdraw()` as a check-then-act sequence.
- Retry `OUTCOME_UNKNOWN` using the same idempotency key when available.
- `transfer()` always respects the currency's `transfer-enabled` setting.

## 🖥️ In-Game Editor

`/currency editor` provides a guided GUI for managing currencies.

- Cosmetic settings are edited through chat and saved immediately.
- Economy-sensitive settings require confirmation.
- New currencies start disabled.
- Decimal precision becomes fixed when a currency is first enabled.
- Deleting a currency requires confirmation and keeps its balances/history in the database.
- Recreating the same currency ID restores access to the existing stored balances.
- Changes are permission-checked and validated before being written.
- Configuration changes are backed up and written atomically.
- Changes are recorded in `editor-audit.log`.
- Formatting tags, colour codes and invisible characters are rejected from text input.

The editor changes only the current server's `config.yml`. When multiple servers share a database, apply the same configuration change on each server.

## 🔄 Configuration Safety

`/currency reload` validates the complete currency configuration before applying it. If any currency is invalid, the reload is rejected as a whole and the previous configuration remains active.

On startup, new settings from newer versions can be added automatically without changing existing values. Existing currencies are preserved. Previous configuration files are backed up.

An invalid YAML file stops the plugin rather than silently replacing it with defaults.

## 💾 Storage Schema

With the default `mc_` prefix:

| Table | Purpose |
|---|---|
| `mc_balances` | Player balances per currency |
| `mc_transactions` | Append-only transaction ledger |
| `mc_currencies` | Stored decimal scale for currencies |
| `mc_players` | Last known player names |
| `mc_schema_version` | Database migration state |

Balances are stored as integer minor units, avoiding floating-point money errors.

## ⚠️ Limitations

- No Bukkit events for transactions yet.
- No Vault bridge by design; MultiCurrency is not the primary economy.
- The in-game editor changes only the local `config.yml`.
- Deleting a currency does not purge its balances or history from the database.
- Folia is not supported.
- API and command balance reads are not cached; only PlaceholderAPI values use a cache.

## 🏗️ Building

Requires **Java 25** and Maven.

```bash
mvn clean package
```

Output:

```text
multicurrency-plugin/target/MultiCurrency-<version>.jar
multicurrency-api/target/MultiCurrency-API-<version>.jar
```

Optional MariaDB integration tests can be enabled with:

```bash
MC_TEST_MARIADB_HOST=127.0.0.1 \\
MC_TEST_MARIADB_USER=... \\
MC_TEST_MARIADB_PASSWORD=... \\
MC_TEST_MARIADB_DB=multicurrency_test \\
mvn test
```

## 📚 Links

- [Latest Releases](https://github.com/Cupjok/MultiCurrency/releases)
- [Issues](https://github.com/Cupjok/MultiCurrency/issues)
- [Source Code](https://github.com/Cupjok/MultiCurrency)
- [License](https://github.com/Cupjok/MultiCurrency/blob/main/LICENSE)

## 📄 License

MultiCurrency is released under the [MIT License](https://github.com/Cupjok/MultiCurrency/blob/main/LICENSE).
