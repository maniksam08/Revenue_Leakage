package dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;


public enum AgeBucket {
    ZERO_TO_30, THIRTY_ONE_TO_60, SIXTY_ONE_PLUS;

   public record Cutoffs(Instant cut30, Instant cut60) {}

    public static Cutoffs cutoffs(LocalDate today, ZoneId zone) {
        return new Cutoffs(today.minusDays(30).atStartOfDay(zone).toInstant(),
                           today.minusDays(60).atStartOfDay(zone).toInstant());
    }

    public static AgeBucket classify(Instant orderCreatedAt, LocalDate today, ZoneId zone) {
        long ageDays = ChronoUnit.DAYS.between(orderCreatedAt.atZone(zone).toLocalDate(), today);
        if (ageDays <= 30) return ZERO_TO_30;      // includes future-dated rows (clock skew)
        if (ageDays <= 60) return THIRTY_ONE_TO_60;
        return SIXTY_ONE_PLUS;
    }
}
