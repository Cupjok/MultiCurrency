package me.cupjok.multicurrency.core.storage;

/** SQL differences between the supported databases. Kept deliberately small. */
public enum Dialect {
    SQLITE,
    /** MariaDB and MySQL 8 (InnoDB). */
    MARIADB;

    /** Row lock for read-modify-write inside a transaction. SQLite serialises writers instead. */
    public String forUpdate() {
        return this == MARIADB ? " FOR UPDATE" : "";
    }

    public String autoIncrementPrimaryKey() {
        return this == MARIADB ? "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    public String tableOptions() {
        return this == MARIADB ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin" : "";
    }

    public String upsertPlayer(String table) {
        if (this == MARIADB) {
            return "INSERT INTO " + table + " (player_uuid, name, name_lower, updated_at) VALUES (?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE name = VALUES(name), name_lower = VALUES(name_lower), updated_at = VALUES(updated_at)";
        }
        return "INSERT INTO " + table + " (player_uuid, name, name_lower, updated_at) VALUES (?, ?, ?, ?)"
                + " ON CONFLICT(player_uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower, updated_at = excluded.updated_at";
    }
}
