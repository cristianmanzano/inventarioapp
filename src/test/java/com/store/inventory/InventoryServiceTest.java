package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryServiceTest {

  private InventoryService service;

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      this.instant = this.instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  @BeforeEach
  void setUp() {
    service = Inventory.create(Clock.systemUTC(), (sku, available) -> {});
    service.registerProduct("SKU-1", ProductCategory.STANDARD);
  }

  @Test
  void reservingReducesAvailableUnits() {
    service.addStock("SKU-1", 10);
    service.reserve("ORDER-1", "SKU-1", 3);
    assertEquals(7, service.available("SKU-1"));
  }

  @Test
  void cannotReserveMoreThanAvailable() {
    service.addStock("SKU-1", 2);
    assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 3));
  }

  @Test
  void confirmedUnitsStaySold() {
    service.addStock("SKU-1", 5);
    service.reserve("ORDER-1", "SKU-1", 2);
    service.confirm("ORDER-1");
    assertEquals(3, service.available("SKU-1"));
  }

  @Test
  void lowStockAlertIsTriggeredOnceUntilStockIsRestocked() {
    AtomicInteger alerts = new AtomicInteger();
    service =
        Inventory.create(
            Clock.systemUTC(),
            (sku, available) -> {
              assertEquals("SKU-1", sku);
              assertTrue(available <= 5);
              alerts.incrementAndGet();
            });
    service.registerProduct("SKU-1", ProductCategory.STANDARD);

    service.addStock("SKU-1", 10);
    service.reserve("ORDER-1", "SKU-1", 5);
    service.reserve("ORDER-2", "SKU-1", 1);

    assertEquals(1, alerts.get());

    service.addStock("SKU-1", 10);
    service.reserve("ORDER-3", "SKU-1", 9);
    assertEquals(2, alerts.get());
  }

  @Test
  void expiredReservationsAreReleased() {
    MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
    service = Inventory.create(clock, (sku, available) -> {});
    service.registerProduct("SKU-1", ProductCategory.STANDARD);
    service.addStock("SKU-1", 10);
    service.reserve("ORDER-1", "SKU-1", 2);

    clock.advance(Duration.ofMinutes(16));

    assertEquals(10, service.available("SKU-1"));
  }
}
