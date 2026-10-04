package com.store.inventory;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class InventoryServiceImpl implements InventoryService {

  private final Clock clock;
  private final StockAlertListener alertListener;
  private final Map<String, ProductCategory> registeredProducts = new HashMap<>();
  private final Map<String, Integer> stockBySku = new HashMap<>();
  private final Map<String, Reservation> reservationsByOrder = new HashMap<>();
  private final Set<String> lowStockAlertedSkus = new HashSet<>();

  InventoryServiceImpl(Clock clock, StockAlertListener alertListener) {
    this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    this.alertListener = alertListener == null ? (sku, availableUnits) -> {} : alertListener;
  }

  @Override
  public synchronized void registerProduct(String sku, ProductCategory category) {
    String normalizedSku = requireSku(sku);
    Objects.requireNonNull(category, "category cannot be null");
    registeredProducts.put(normalizedSku, category);
  }

  @Override
  public synchronized void addStock(String sku, int quantity) {
    String normalizedSku = requireSku(sku);
    requirePositiveQuantity(quantity, "quantity");

    if (!registeredProducts.containsKey(normalizedSku)) {
      throw new IllegalArgumentException("Product is not registered: " + normalizedSku);
    }

    stockBySku.merge(normalizedSku, quantity, Integer::sum);
    expireReservations();
    evaluateLowStockAlert(normalizedSku);
  }

  @Override
  public synchronized Reservation reserve(String orderId, String sku, int quantity) {
    String normalizedOrderId = requireOrderId(orderId);
    String normalizedSku = requireSku(sku);
    requirePositiveQuantity(quantity, "quantity");

    expireReservations();

    if (reservationsByOrder.containsKey(normalizedOrderId)) {
      throw new IllegalStateException(
          "Order already has an active reservation: " + normalizedOrderId);
    }

    ProductCategory category = registeredProducts.get(normalizedSku);
    if (category == null) {
      throw new InsufficientStockException(normalizedSku, quantity, 0);
    }

    int orderLimit = getOrderLimit(category);
    if (quantity > orderLimit) {
      throw new OrderLimitExceededException(normalizedSku, quantity, orderLimit);
    }

    int available = computeAvailable(normalizedSku);
    if (quantity > available) {
      throw new InsufficientStockException(normalizedSku, quantity, available);
    }

    Instant expiresAt = clock.instant().plus(getReservationDuration(category));
    Reservation reservation =
        new Reservation(normalizedOrderId, normalizedSku, quantity, expiresAt);
    reservationsByOrder.put(normalizedOrderId, reservation);
    evaluateLowStockAlert(normalizedSku);
    return reservation;
  }

  @Override
  public synchronized void confirm(String orderId) {
    String normalizedOrderId = requireOrderId(orderId);
    Reservation reservation = reservationsByOrder.remove(normalizedOrderId);
    if (reservation == null) {
      throw new IllegalStateException("Order has no active reservation: " + normalizedOrderId);
    }

    if (!reservation.expiresAt().isAfter(clock.instant())) {
      throw new IllegalStateException("Reservation expired for order: " + normalizedOrderId);
    }

    String sku = reservation.sku();
    int currentStock = stockBySku.getOrDefault(sku, 0);
    int remainingStock = Math.max(0, currentStock - reservation.quantity());
    stockBySku.put(sku, remainingStock);
    evaluateLowStockAlert(sku);
  }

  @Override
  public synchronized int available(String sku) {
    String normalizedSku = requireSku(sku);
    expireReservations();
    return computeAvailable(normalizedSku);
  }

  private void expireReservations() {
    Instant now = clock.instant();
    Iterator<Map.Entry<String, Reservation>> iterator = reservationsByOrder.entrySet().iterator();
    while (iterator.hasNext()) {
      Reservation reservation = iterator.next().getValue();
      if (!reservation.expiresAt().isAfter(now)) {
        iterator.remove();
      }
    }
  }

  private int computeAvailable(String sku) {
    int totalStock = stockBySku.getOrDefault(sku, 0);
    int reservedUnits = 0;
    for (Reservation reservation : reservationsByOrder.values()) {
      if (sku.equals(reservation.sku())) {
        reservedUnits += reservation.quantity();
      }
    }
    return Math.max(0, totalStock - reservedUnits);
  }

  private void evaluateLowStockAlert(String sku) {
    expireReservations();
    int availableUnits = computeAvailable(sku);
    boolean shouldAlert = availableUnits <= 5;
    if (shouldAlert && !lowStockAlertedSkus.contains(sku)) {
      alertListener.onLowStock(sku, availableUnits);
      lowStockAlertedSkus.add(sku);
      return;
    }
    if (!shouldAlert) {
      lowStockAlertedSkus.remove(sku);
    }
  }

  private int getOrderLimit(ProductCategory category) {
    return switch (category) {
      case FLASH_SALE -> 2;
      case STANDARD, PRE_ORDER -> Integer.MAX_VALUE;
    };
  }

  private Duration getReservationDuration(ProductCategory category) {
    return switch (category) {
      case STANDARD -> Duration.ofMinutes(15);
      case PRE_ORDER -> Duration.ofHours(24);
      case FLASH_SALE -> Duration.ofMinutes(5);
    };
  }

  private String requireSku(String sku) {
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU cannot be null or blank");
    }
    return sku.trim();
  }

  private String requireOrderId(String orderId) {
    if (orderId == null || orderId.isBlank()) {
      throw new IllegalArgumentException("Order ID cannot be null or blank");
    }
    return orderId.trim();
  }

  private void requirePositiveQuantity(int quantity, String fieldName) {
    if (quantity <= 0) {
      throw new IllegalArgumentException(fieldName + " must be positive");
    }
  }
}
