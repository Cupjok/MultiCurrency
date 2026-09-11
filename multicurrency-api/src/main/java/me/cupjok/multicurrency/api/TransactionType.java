package me.cupjok.multicurrency.api;

/** Kind of ledger entry. */
public enum TransactionType {
    /** Account creation with the currency's starting balance. */
    INITIAL,
    DEPOSIT,
    WITHDRAW,
    SET,
    TRANSFER
}
