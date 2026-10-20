import com.roadconquest.app.export.CsvUtil;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import kotlin.collections.CollectionsKt;

/** Local JDK fixture; calls the app's compiled CsvUtil, with the original Kotlin join as baseline. */
public class CsvExportAllocationProbe {
    static final com.sun.management.ThreadMXBean ALLOC =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    record Result(long bytes, long nanos, String sha256) {}
    static Result run(boolean streamed, List<String> fields, int rows) throws Exception {
        long thread = Thread.currentThread().threadId();
        long bytes = ALLOC.getThreadAllocatedBytes(thread), start = System.nanoTime();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest)))) {
            ZipEntry entry = new ZipEntry("roads.csv");
            entry.setTime(0);
            zip.putNextEntry(entry);
            Writer writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
            for (int i = 0; i < rows; i++) {
                if (streamed) CsvUtil.INSTANCE.writeRow(writer, fields);
                else {
                    writer.write(CollectionsKt.joinToString(fields, ",", "", "", -1, "...",
                        value -> CsvUtil.INSTANCE.escape(value)));
                    writer.write("\n");
                }
            }
            writer.flush();
            zip.closeEntry();
        }
        return new Result(ALLOC.getThreadAllocatedBytes(thread) - bytes,
            System.nanoTime() - start, HexFormat.of().formatHex(digest.digest()));
    }
    public static void main(String[] args) throws Exception {
        ALLOC.setThreadAllocatedMemoryEnabled(true);
        for (int coordinates : new int[]{0, 400, 5_000}) {
            StringBuilder geometry = new StringBuilder("[");
            for (int i = 0; i < coordinates; i++) {
                if (i != 0) geometry.append(',');
                geometry.append("[-74.000000001,40.").append(i).append(']');
            }
            geometry.append(']');
            List<String> fields = List.of("road-id", "Main, \"North\" Road", "2026-10-19T00:00:00Z",
                "2026-10-19T01:00:00Z", "0.99", "2", "true", geometry.toString());
            int rows = coordinates == 0 ? 20_000 : coordinates == 400 ? 1_000 : 100;
            for (int i = 0; i < 3; i++) {
                run(false, fields, rows / 10);
                run(true, fields, rows / 10);
            }
            for (int round = 0; round < 3; round++) {
                Result before, after;
                if (round % 2 == 0) { before = run(false, fields, rows); after = run(true, fields, rows); }
                else { after = run(true, fields, rows); before = run(false, fields, rows); }
                if (!before.sha256.equals(after.sha256)) throw new AssertionError("ZIP bytes differ");
                System.out.printf(Locale.ROOT,
                    "{\"coordinates\":%d,\"rows\":%d,\"round\":%d,\"before_allocated_bytes\":%d,\"after_allocated_bytes\":%d,\"before_ms\":%.3f,\"after_ms\":%.3f,\"identical_zip_sha256\":\"%s\"}%n",
                    coordinates, rows, round, before.bytes, after.bytes,
                    before.nanos / 1e6, after.nanos / 1e6, before.sha256);
            }
        }
    }
}
