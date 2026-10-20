import com.roadconquest.app.achievements.*;
import com.roadconquest.app.data.DataSummary;
import com.roadconquest.app.map.FogGrid;
import com.roadconquest.app.map.DistinctGeoJson;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Compare this identical fixture with baseline/current compiled app classes on JDK 21. */
public class PhoneAllocationProbe {
    static final com.sun.management.ThreadMXBean ALLOC =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static volatile Object sink;
    static AchievementMetrics metrics(long value, Set<String> owned, Map<String, Long> points) {
        return new AchievementMetrics(value, value, value, value, 101, value, value, owned, points);
    }
    static DataSummary summary(long value) {
        return new DataSummary(0, 0, null, null, value * 1609.344, value, -1);
    }
    static String digest(Object value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args) throws Exception {
        ALLOC.setThreadAllocatedMemoryEnabled(true);
        var base = Achievements.INSTANCE.progress(summary(100_000), metrics(100_000, Set.of(), Map.of()));
        Set<String> owned = new HashSet<>();
        for (var item : base) owned.add(item.getId());
        // Snapshot signatures cover earned values, retained rewards after deduplication,
        // historic payouts, NaN/infinite distance, empty progress and unknown ledger IDs.
        var signatures = new ArrayList<String>();
        for (long value : new long[]{0, 1, 5, 10, 25, 100, 100_000}) {
            for (int mode = 0; mode < 3; mode++) {
                var points = new HashMap<String, Long>();
                if (mode == 2) for (String id : owned) points.put(id, 7L);
                signatures.add(digest(Achievements.INSTANCE.progress(summary(value),
                    metrics(value, mode == 0 ? Set.of() : owned, points))));
            }
        }
        for (double distance : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.0}) {
            signatures.add(digest(Achievements.INSTANCE.progress(new DataSummary(0, 0, null, null, distance, 100_000, -1),
                metrics(100_000, owned, Map.of()))));
        }
        signatures.add(digest(Achievements.INSTANCE.progress(summary(0), metrics(0,
            Set.of("battery_5", "battery_1", "unknown-ledger-id"), Map.of("battery_5", 7L, "battery_1", 999L)))));
        System.out.println("{\"compatibility_cases\":" + signatures.size() + ",\"sha256\":\"" + digest(signatures) + "\"}");
        for (long value : new long[]{100_000, 0}) {
            var current = summary(value);
            var metrics = metrics(value, owned, Map.of());
            for (int i = 0; i < 3_000; i++) sink = Achievements.INSTANCE.progress(current, metrics);
            for (int round = 0; round < 3; round++) {
                long thread = Thread.currentThread().threadId();
                long before = ALLOC.getThreadAllocatedBytes(thread);
                for (int i = 0; i < 10_000; i++) sink = Achievements.INSTANCE.progress(current, metrics);
                long bytes = ALLOC.getThreadAllocatedBytes(thread) - before;
                System.out.printf(Locale.ROOT, "{\"progress\":%d,\"owned_cards\":%d,\"refreshes\":10000,\"round\":%d,\"allocated_bytes\":%d}%n",
                    value, owned.size(), round, bytes);
            }
        }
        double[][] cells = new double[25][];
        for (int i = 0; i < cells.length; i++) cells[i] = FogGrid.INSTANCE.corners(40 + (i / 5 - 2) * .02, -74 + (i % 5 - 2) * .02);
        for (double center : new double[]{-74, 286}) {
            for (int i = 0; i < 3_000; i++) for (double[] cell : cells) sink = FogGrid.INSTANCE.nearLongitude(cell, center);
            for (int round = 0; round < 3; round++) {
                long thread = Thread.currentThread().threadId(), before = ALLOC.getThreadAllocatedBytes(thread);
                for (int i = 0; i < 10_000; i++) for (double[] cell : cells) sink = FogGrid.INSTANCE.nearLongitude(cell, center);
                long bytes = ALLOC.getThreadAllocatedBytes(thread) - before;
                System.out.printf(Locale.ROOT, "{\"grid_center\":%.1f,\"cells\":25,\"refreshes\":10000,\"round\":%d,\"allocated_bytes\":%d,\"last_cell_sha256\":\"%s\"}%n",
                    center, round, bytes, digest(Arrays.toString((double[])sink)));
            }
        }
        String first = "x".repeat(128_000), latest = new String(first.toCharArray());
        var publisher = new DistinctGeoJson<String>("");
        int[] callbacks = {0};
        publisher.publish(first, value -> { callbacks[0]++; return true; });
        publisher.publish(latest, value -> { callbacks[0]++; return true; });
        var field = publisher.getClass().getDeclaredField("applied");
        field.setAccessible(true);
        var references = new IdentityHashMap<Object, Boolean>();
        references.put(latest, true); // Renderer snapshot's current field.
        references.put(field.get(publisher), true);
        System.out.printf(Locale.ROOT, "{\"equal_payload_chars\":128000,\"renderer_and_publisher_distinct_payload_references\":%d,\"native_callbacks\":%d}%n",
            references.size(), callbacks[0]);
    }
}
