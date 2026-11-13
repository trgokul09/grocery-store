package com.grocery.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.*;

/** Business-day helper: all reports use the store's local timezone (default Asia/Kolkata). */
@Component
public class StoreClock {
    private final ZoneId zone;
    public StoreClock(@Value("${app.timezone}") String tz) { this.zone = ZoneId.of(tz); }
    public ZoneId zone() { return zone; }
    public LocalDate today() { return LocalDate.now(zone); }
    public Instant startOf(LocalDate d) { return d.atStartOfDay(zone).toInstant(); }
    public OffsetDateTime odt(Instant i) { return i.atOffset(ZoneOffset.UTC); }
    public LocalDate toLocal(Instant i) { return i.atZone(zone).toLocalDate(); }
}
