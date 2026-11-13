package com.grocery.notification;

import com.grocery.common.StoreClock;
import com.grocery.product.Product;
import com.grocery.product.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Component
public class ExpiryScheduler {
    private final ProductRepository products; private final NotificationService notifications; private final StoreClock clock;
    @Value("${app.expiry-alert-days}") int alertDays;

    public ExpiryScheduler(ProductRepository p, NotificationService n, StoreClock c) { products = p; notifications = n; clock = c; }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 0 7 * * *", zone = "${app.timezone}")
    @Transactional
    public void scan() {
        LocalDate today = clock.today();
        for (Product p : products.findExpiring(today.plusDays(alertDays))) {
            long days = ChronoUnit.DAYS.between(today, p.getExpiryDate());
            String msg = days < 0 ? "EXPIRED: " + p.getName() + " expired on " + p.getExpiryDate() + " (" + p.getCurrentStock().stripTrailingZeros().toPlainString() + " in stock)"
                : "Expiring in " + days + " day(s): " + p.getName() + " (" + p.getExpiryDate() + ")";
            notifications.expiry(p, msg, "EXP:" + p.getId() + ":" + today);
        }
    }
}
