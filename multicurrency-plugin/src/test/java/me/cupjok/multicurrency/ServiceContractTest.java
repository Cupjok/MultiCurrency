package me.cupjok.multicurrency;

import me.cupjok.multicurrency.api.Actor;
import me.cupjok.multicurrency.api.BalanceEntry;
import me.cupjok.multicurrency.api.CurrencyException;
import me.cupjok.multicurrency.api.FailureReason;
import me.cupjok.multicurrency.api.TransactionContext;
import me.cupjok.multicurrency.api.TransactionRecord;
import me.cupjok.multicurrency.api.TransactionResult;
import me.cupjok.multicurrency.api.TransactionType;
import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import me.cupjok.multicurrency.core.currency.CurrencyRegistry;
import me.cupjok.multicurrency.core.service.CurrencyService;
import me.cupjok.multicurrency.core.storage.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static me.cupjok.multicurrency.TestSupport.ctx;
import static me.cupjok.multicurrency.TestSupport.d;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Functional contract of the service/API. Runs against every storage backend. */
abstract class ServiceContractTest {

    protected CurrencyService service;
    protected Database db;

    protected abstract Database createDatabase() throws Exception;

    protected int threads() {
        return 4;
    }

    @BeforeEach
    void setUp() throws Exception {
        db = createDatabase();
        service = new CurrencyService(db, "test-server", threads(), TestSupport.LOG);
        service.start(TestSupport.registry()).join();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Null when setUp was skipped (no MariaDB/MySQL configured).
        if (service != null) {
            service.close();
        }
    }

    protected BigDecimal bal(UUID p, String c) {
        return service.balance(p, c).join();
    }

    protected TransactionResult deposit(UUID p, String c, String amount) {
        return service.deposit(p, c, d(amount), ctx()).join();
    }

    private static void assertFailed(FailureReason expected, TransactionResult r) {
        assertFalse(r.success(), "expected failure " + expected);
        assertEquals(expected, r.failureReason());
    }

    // ------------------------------------------------------------------ currencies

    @Test
    void currencyDiscovery() {
        assertEquals(5, service.currencies().size());
        assertTrue(service.currency("COINS").isPresent());
        assertTrue(service.isEnabled("coins"));
        assertFalse(service.isEnabled("off"));
        assertFalse(service.isEnabled("missing"));
        assertTrue(service.currency("missing").isEmpty());
    }

    @Test
    void unknownCurrencyRejectedEverywhere() {
        UUID p = UUID.randomUUID();
        assertFailed(FailureReason.UNKNOWN_CURRENCY, deposit(p, "nope", "1"));
        CompletionException e = assertThrows(CompletionException.class, () -> service.balance(p, "nope").join());
        assertEquals(FailureReason.UNKNOWN_CURRENCY, ((CurrencyException) e.getCause()).reason());
        assertFailed(FailureReason.UNKNOWN_CURRENCY, service.withdraw(p, null, d("1"), ctx()).join());
    }

    @Test
    void disabledCurrencyRefusesMutationsButCanBeRead() {
        UUID p = UUID.randomUUID();
        assertFailed(FailureReason.CURRENCY_DISABLED, deposit(p, "off", "1"));
        assertFailed(FailureReason.CURRENCY_DISABLED, service.set(p, "off", d("1"), ctx()).join());
        assertFailed(FailureReason.CURRENCY_DISABLED, service.transfer(p, UUID.randomUUID(), "off", d("1"), ctx()).join());
        assertEquals(0, bal(p, "off").signum());
    }

    // ------------------------------------------------------------------ balances

    @Test
    void newAccountReportsStartingBalanceAndRecordsItOnFirstWrite() {
        UUID p = UUID.randomUUID();
        assertEquals(d("50"), bal(p, "starter"));
        TransactionResult r = deposit(p, "starter", "5");
        assertTrue(r.success());
        assertEquals(d("55"), r.balanceAfter());
        List<TransactionRecord> h = service.history(p, "starter", 10).join();
        assertEquals(2, h.size());
        assertEquals(TransactionType.DEPOSIT, h.get(0).type());
        assertEquals(TransactionType.INITIAL, h.get(1).type());
        assertEquals(d("50"), h.get(1).amount());
    }

    @Test
    void depositWithdrawSet() {
        UUID p = UUID.randomUUID();
        TransactionResult r = deposit(p, "coins", "10.25");
        assertTrue(r.success());
        assertEquals(d("10.25"), r.balanceAfter());
        assertTrue(r.transactionId() != null && !r.transactionId().isBlank());

        r = service.withdraw(p, "coins", d("0.25"), ctx()).join();
        assertTrue(r.success());
        assertEquals(d("10.00"), r.balanceAfter());

        r = service.set(p, "coins", d("3.5"), ctx()).join();
        assertTrue(r.success());
        assertEquals(d("3.50"), bal(p, "coins"));

        r = service.set(p, "coins", d("0"), ctx()).join();
        assertTrue(r.success());
        assertEquals(0, bal(p, "coins").signum());
    }

    @Test
    void withdrawBeyondBalanceFailsAndChangesNothing() {
        UUID p = UUID.randomUUID();
        deposit(p, "coins", "5");
        assertFailed(FailureReason.INSUFFICIENT_FUNDS, service.withdraw(p, "coins", d("5.01"), ctx()).join());
        assertEquals(d("5.00"), bal(p, "coins"));
        assertTrue(service.withdraw(p, "coins", d("5"), ctx()).join().success());
        assertFailed(FailureReason.INSUFFICIENT_FUNDS, service.withdraw(p, "coins", d("0.01"), ctx()).join());
        assertEquals(d("0.00"), bal(p, "coins"));
    }

    @Test
    void balanceLimitEnforced() {
        UUID p = UUID.randomUUID();
        assertTrue(deposit(p, "capped", "100").success());
        assertFailed(FailureReason.BALANCE_LIMIT_EXCEEDED, deposit(p, "capped", "1"));
        assertFailed(FailureReason.AMOUNT_TOO_LARGE, service.set(p, "capped", d("101"), ctx()).join());
        assertFailed(FailureReason.AMOUNT_TOO_LARGE, deposit(p, "capped", "101"));
        assertEquals(d("100"), bal(p, "capped"));
    }

    @Test
    void invalidAmountsRejected() {
        UUID p = UUID.randomUUID();
        assertFailed(FailureReason.INVALID_AMOUNT, deposit(p, "coins", "0"));
        assertFailed(FailureReason.INVALID_AMOUNT, deposit(p, "coins", "-5"));
        assertFailed(FailureReason.INVALID_AMOUNT, service.deposit(p, "coins", null, ctx()).join());
        assertFailed(FailureReason.INVALID_AMOUNT, service.set(p, "coins", d("-0.01"), ctx()).join());
        assertFailed(FailureReason.INVALID_AMOUNT, service.withdraw(p, "coins", d("-1"), ctx()).join());
        assertFailed(FailureReason.INVALID_AMOUNT, service.transfer(p, UUID.randomUUID(), "coins", d("-1"), ctx()).join());
        assertFailed(FailureReason.INVALID_PRECISION, deposit(p, "coins", "0.001"));
        assertFailed(FailureReason.INVALID_PRECISION, deposit(p, "gems", "1.5"));
        assertFailed(FailureReason.AMOUNT_TOO_LARGE, service.deposit(p, "gems", new BigDecimal("1E+40"), ctx()).join());
        assertEquals(0, bal(p, "coins").signum());
        assertEquals(0, service.history(p, null, 10).join().size(), "no ledger entry for rejected operations");
    }

    @Test
    void nullArgumentsRejected() {
        assertFailed(FailureReason.INVALID_ARGUMENT, service.deposit(null, "coins", d("1"), ctx()).join());
        assertFailed(FailureReason.INVALID_ARGUMENT, service.deposit(UUID.randomUUID(), "coins", d("1"), null).join());
        assertFailed(FailureReason.INVALID_ARGUMENT, service.transfer(UUID.randomUUID(), null, "coins", d("1"), ctx()).join());
    }

    @Test
    void hasIsAdvisoryRead() {
        UUID p = UUID.randomUUID();
        deposit(p, "coins", "2");
        assertTrue(service.has(p, "coins", d("2")).join());
        assertFalse(service.has(p, "coins", d("2.01")).join());
        assertThrows(CompletionException.class, () -> service.has(p, "coins", d("-1")).join());
    }

    // ------------------------------------------------------------------ transfers

    @Test
    void transferMovesExactlyOnce() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        deposit(a, "coins", "10");
        TransactionResult r = service.transfer(a, b, "coins", d("3.33"), ctx()).join();
        assertTrue(r.success());
        assertEquals(d("6.67"), r.balanceAfter());
        assertEquals(d("3.33"), r.counterpartyBalanceAfter());
        assertEquals(d("6.67"), bal(a, "coins"));
        assertEquals(d("3.33"), bal(b, "coins"));
        TransactionRecord rec = service.history(b, "coins", 1).join().getFirst();
        assertEquals(TransactionType.TRANSFER, rec.type());
        assertEquals(a, rec.account());
        assertEquals(b, rec.counterparty());
        assertEquals(d("10.00"), rec.balanceBefore());
        assertEquals(d("0.00"), rec.counterpartyBalanceBefore());
        assertEquals("test-server", rec.serverId());
    }

    @Test
    void transferDisabledCurrencyCannotBeTransferredThroughTheApi() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        deposit(a, "gems", "100");
        TransactionContext asPlugin = TransactionContext.of(Actor.plugin("SomePlugin"), "trade");
        TransactionContext asConsole = TransactionContext.of(Actor.console(), "admin");
        TransactionContext asPlayer = TransactionContext.of(Actor.player(a, "A"), "pay");
        for (TransactionContext c : List.of(asPlugin, asConsole, asPlayer, ctx("gem-key"))) {
            assertFailed(FailureReason.TRANSFER_DISABLED, service.transfer(a, b, "gems", d("1"), c).join());
            assertFailed(FailureReason.TRANSFER_DISABLED, service.transfer(a, b, "GEMS", d("1"), c).join());
        }
        assertEquals(d("100"), bal(a, "gems"));
        assertEquals(d("0"), bal(b, "gems"));
        assertEquals(0, service.history(b, "gems", 10).join().size());
    }

    @Test
    void selfTransferRejected() {
        UUID a = UUID.randomUUID();
        deposit(a, "coins", "10");
        assertFailed(FailureReason.SELF_TRANSFER, service.transfer(a, a, "coins", d("1"), ctx()).join());
        assertEquals(d("10.00"), bal(a, "coins"));
    }

    @Test
    void failedTransferIsAllOrNothing() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        deposit(a, "coins", "1");
        assertFailed(FailureReason.INSUFFICIENT_FUNDS, service.transfer(a, b, "coins", d("1.01"), ctx()).join());
        assertEquals(d("1.00"), bal(a, "coins"));
        assertEquals(d("0.00"), bal(b, "coins"));

        UUID c = UUID.randomUUID();
        UUID full = UUID.randomUUID();
        deposit(c, "capped", "50");
        deposit(full, "capped", "60");
        assertFailed(FailureReason.BALANCE_LIMIT_EXCEEDED, service.transfer(c, full, "capped", d("41"), ctx()).join());
        assertEquals(d("50"), bal(c, "capped"), "sender not debited when the credit fails");
        assertEquals(d("60"), bal(full, "capped"));
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void idempotencyKeyAppliesOnce() {
        UUID p = UUID.randomUUID();
        TransactionResult first = service.deposit(p, "coins", d("5"), ctx("order-1")).join();
        assertTrue(first.success());
        TransactionResult second = service.deposit(p, "coins", d("5"), ctx("order-1")).join();
        assertFailed(FailureReason.DUPLICATE_TRANSACTION, second);
        assertEquals(first.transactionId(), second.transactionId());
        // Same key on a different operation type is still the same transaction.
        assertFailed(FailureReason.DUPLICATE_TRANSACTION, service.withdraw(p, "coins", d("1"), ctx("order-1")).join());
        assertEquals(d("5.00"), bal(p, "coins"));
        assertTrue(service.deposit(p, "coins", d("5"), ctx("order-2")).join().success());
        assertEquals(d("10.00"), bal(p, "coins"));
    }

    @Test
    void rejectedOperationDoesNotConsumeItsKey() {
        UUID p = UUID.randomUUID();
        assertFailed(FailureReason.INSUFFICIENT_FUNDS, service.withdraw(p, "coins", d("1"), ctx("k")).join());
        deposit(p, "coins", "1");
        assertTrue(service.withdraw(p, "coins", d("1"), ctx("k")).join().success());
    }

    // ------------------------------------------------------------------ queries

    @Test
    void historyAndTop() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        deposit(a, "coins", "30");
        deposit(b, "coins", "20");
        deposit(a, "gems", "7");
        service.transfer(a, b, "coins", d("1"), ctx()).join();

        List<TransactionRecord> all = service.history(a, null, 10).join();
        assertEquals(3, all.size());
        assertTrue(all.get(0).sequence() > all.get(1).sequence(), "newest first");
        assertEquals(2, service.history(a, "coins", 10).join().size());
        assertEquals(1, service.history(a, "coins", 1).join().size());

        List<BalanceEntry> top = service.top("coins", 10, 0).join();
        assertEquals(a, top.get(0).player());
        assertEquals(d("29.00"), top.get(0).balance());
        assertEquals(b, top.get(1).player());
        assertEquals(1, service.top("coins", 10, 1).join().size());
    }

    @Test
    void playerNamesRecorded() {
        UUID p = UUID.randomUUID();
        service.recordPlayerName(p, "Steve").join();
        assertEquals(p, service.findPlayerByName("steve").join().orElseThrow());
        service.recordPlayerName(p, "Alex").join();
        assertTrue(service.findPlayerByName("steve").join().isEmpty());
        deposit(p, "coins", "1");
        assertEquals("Alex", service.top("coins", 1, 0).join().getFirst().lastKnownName());
    }

    // ------------------------------------------------------------------ currency lifecycle (editor support)

    /** The test registry with {@code id} replaced (or removed when {@code replacement} is null). */
    private static CurrencyRegistry replace(String id, CurrencyDefinition replacement) {
        List<CurrencyDefinition> out = new ArrayList<>();
        for (CurrencyDefinition c : TestSupport.registry().all()) {
            if (!c.id().equals(id)) {
                out.add(c);
            } else if (replacement != null) {
                out.add(replacement);
            }
        }
        return new CurrencyRegistry(out);
    }

    private static CurrencyDefinition off(boolean enabled, int scale) {
        return new CurrencyDefinition("off", "Off", "", enabled, scale, d("0"), null, true, null);
    }

    @Test
    void decimalsLockOnlyWhenACurrencyIsFirstEnabled() {
        assertFalse(service.stats("off").join().registered(), "a disabled, never-enabled currency is not registered");

        service.reload(replace("off", off(false, 2))).join();
        assertTrue(service.conflictedCurrencies().isEmpty(), "decimals of a never-enabled currency can still change");
        assertFalse(service.stats("off").join().registered());

        service.reload(replace("off", off(true, 2))).join();
        assertEquals(Integer.valueOf(2), service.stats("off").join().storedScale());
        UUID p = UUID.randomUUID();
        assertTrue(deposit(p, "off", "1.25").success());

        service.reload(replace("off", off(false, 0))).join();
        assertTrue(service.conflictedCurrencies().contains("off"), "once enabled, the scale stays locked even when disabled again");
        service.reload(replace("off", off(true, 0))).join();
        assertFailed(FailureReason.CURRENCY_DISABLED, deposit(p, "off", "1"));

        service.reload(replace("off", off(true, 2))).join();
        assertEquals(d("1.25"), bal(p, "off"), "restoring the stored scale restores the exact balance");
    }

    @Test
    void removedCurrencyKeepsItsDataAndGetsItBackWhenReAdded() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        deposit(a, "gems", "7");
        deposit(b, "gems", "3");
        CurrencyService.CurrencyStats s = service.stats("GEMS").join();
        assertEquals(Integer.valueOf(0), s.storedScale());
        assertEquals(2, s.accounts());
        assertEquals(2, s.ledgerEntries());
        assertEquals(0, d("10").compareTo(s.totalSupply()));

        service.reload(replace("gems", null)).join();
        assertTrue(service.currency("gems").isEmpty());
        assertFailed(FailureReason.UNKNOWN_CURRENCY, deposit(a, "gems", "1"));
        assertEquals(2, service.stats("gems").join().accounts(), "data of a removed currency is kept");

        service.reload(TestSupport.registry()).join();
        assertEquals(d("7"), bal(a, "gems"));
        assertEquals(d("3"), bal(b, "gems"));
    }

    @Test
    void statsOfUnusedAndInvalidIds() {
        CurrencyService.CurrencyStats s = service.stats("never_used").join();
        assertFalse(s.registered());
        assertEquals(0, s.accounts());
        assertEquals(0, s.totalSupply().signum());
        CompletionException e = assertThrows(CompletionException.class, () -> service.stats("Bad Id!").join());
        assertEquals(FailureReason.UNKNOWN_CURRENCY, ((CurrencyException) e.getCause()).reason());
    }

    @Test
    void overlappingReloadsKeepTheLastSubmittedConfiguration() {
        CurrencyRegistry withoutGems = replace("gems", null);
        List<CompletableFuture<CurrencyRegistry>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            futures.add(service.reload(i % 2 == 0 ? TestSupport.registry() : withoutGems));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        assertTrue(service.currency("gems").isEmpty(), "an older reload must not overwrite a newer one");
        assertEquals(4, service.currencies().size());
    }

    @Test
    void balanceChangeListenerSeesOnlyAppliedChanges() {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        service.setBalanceChangeListener((player, currency) -> seen.add(player + ":" + currency));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(deposit(a, "coins", "5").success());
        assertEquals(List.of(a + ":coins"), seen);
        assertFalse(service.withdraw(a, "coins", d("100"), ctx()).join().success());
        assertFalse(deposit(a, "nope", "1").success());
        assertFalse(deposit(a, "coins", "0").success());
        assertFalse(service.transfer(a, b, "gems", d("1"), ctx()).join().success());
        assertEquals(1, seen.size(), "rejected operations change nothing and notify nobody");
        assertTrue(service.transfer(a, b, "coins", d("1"), ctx()).join().success());
        assertEquals(3, seen.size());
        assertTrue(seen.contains(b + ":coins"));
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void closedServiceRefusesWork() {
        service.close();
        assertFailed(FailureReason.SERVICE_UNAVAILABLE, deposit(UUID.randomUUID(), "coins", "1"));
        CompletionException e = assertThrows(CompletionException.class, () -> service.balance(UUID.randomUUID(), "coins").join());
        assertEquals(FailureReason.SERVICE_UNAVAILABLE, ((CurrencyException) e.getCause()).reason());
    }

    @Test
    void failedResultCarriesNoBalances() {
        TransactionResult r = deposit(UUID.randomUUID(), "coins", "0");
        assertNull(r.balanceAfter());
        assertNull(r.transactionId());
        assertEquals("coins", r.currencyId());
    }
}
