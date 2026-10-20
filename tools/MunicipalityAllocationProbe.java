import com.roadconquest.app.data.ProgressionRepository;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import sun.misc.Unsafe;

/** Run the same fixture against baseline/current compiled app classes on JDK 21. */
public class MunicipalityAllocationProbe {
    static volatile Object sink;
    public static void main(String[] args) throws Exception {
        // This private pure name normalizer needs no repository fields. Bypass
        // its Android database constructor; never execute Android stub methods.
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var repository = ((Unsafe) field.get(null)).allocateInstance(ProgressionRepository.class);
        Method method = ProgressionRepository.class.getDeclaredMethod("municipalityIdentity", String.class);
        method.setAccessible(true);
        var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        var digest = MessageDigest.getInstance("SHA-256");
        String[] names = {"Pine Hill", "Township of Pine Hill", "City of Québec", "The Pine Hill Borough", "", "city of", "town of x city"};
        for (String name : names) {
            digest.update(method.invoke(repository, name).toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        System.out.printf("{\"compatibility_cases\":%d,\"sha256\":\"%s\"}%n", names.length, HexFormat.of().formatHex(digest.digest()));
        for (String name : names) {
            for (int i = 0; i < 20_000; i++) sink = method.invoke(repository, name);
            for (int round = 0; round < 3; round++) {
                long thread = Thread.currentThread().threadId();
                long before = allocation.getThreadAllocatedBytes(thread);
                for (int i = 0; i < 10_000; i++) sink = method.invoke(repository, name);
                long bytes = allocation.getThreadAllocatedBytes(thread) - before;
                System.out.printf(Locale.ROOT, "{\"name_index\":%d,\"lookups\":10000,\"round\":%d,\"allocated_bytes\":%d}%n",
                    java.util.Arrays.asList(names).indexOf(name), round, bytes);
            }
        }
    }
}
