package io.github.samzhu.ledger.service;

import java.nio.ByteBuffer;

import org.springframework.stereotype.Service;

import com.tdunning.math.stats.MergingDigest;
import com.tdunning.math.stats.TDigest;

import io.github.samzhu.ledger.config.LedgerProperties;

/**
 * T-Digest 延遲百分位計算服務。
 *
 * <p>使用 T-Digest 演算法計算延遲的百分位數 (P50/P90/P95/P99)。
 * T-Digest 是一種串流式近似演算法，具有以下優點：
 * <ul>
 *   <li>固定記憶體使用量 (~1KB per digest)</li>
 *   <li>可合併多個 digest</li>
 *   <li>尾部精度高 (P99, P99.9)</li>
 *   <li>更新效率 O(log n)</li>
 * </ul>
 *
 * @see <a href="https://github.com/tdunning/t-digest">T-Digest GitHub</a>
 */
@Service
public class LatencyDigestService {

    private final int compression;

    public LatencyDigestService(LedgerProperties properties) {
        this.compression = properties.latency() != null
            ? properties.latency().digestCompression()
            : 100;
    }

    /**
     * 建立新的 T-Digest 實例。
     *
     * @return 新的空 T-Digest
     */
    public TDigest createDigest() {
        return TDigest.createMergingDigest(compression);
    }

    /**
     * 從 byte 陣列反序列化 T-Digest。
     *
     * @param bytes 序列化的 T-Digest bytes，可為 null 或空
     * @return 反序列化的 T-Digest，如果輸入為空則返回新的空 digest
     */
    public TDigest deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return createDigest();
        }
        return MergingDigest.fromBytes(ByteBuffer.wrap(bytes));
    }

    /**
     * 將 T-Digest 序列化為 byte 陣列。
     *
     * @param digest 要序列化的 T-Digest
     * @return 序列化後的 byte 陣列
     */
    public byte[] serialize(TDigest digest) {
        ByteBuffer buffer = ByteBuffer.allocate(digest.byteSize());
        digest.asBytes(buffer);
        return buffer.array();
    }

    /**
     * 從 T-Digest 計算延遲統計資料。
     *
     * @param digest T-Digest 實例
     * @return 包含各百分位數的統計資料
     */
    public LatencyStats calculateStats(TDigest digest) {
        if (digest.size() == 0) {
            return LatencyStats.empty();
        }

        long count = digest.size();
        long min = (long) digest.getMin();
        long max = (long) digest.getMax();

        // 計算平均值（使用 centroids 的加權平均）
        double sum = 0;
        for (var centroid : digest.centroids()) {
            sum += centroid.mean() * centroid.count();
        }
        double avg = sum / count;

        return new LatencyStats(
            count,
            min,
            max,
            avg,
            digest.quantile(0.5),   // P50
            digest.quantile(0.9),   // P90
            digest.quantile(0.95),  // P95
            digest.quantile(0.99)   // P99
        );
    }

    /**
     * 合併多個 T-Digest。
     *
     * @param digests 要合併的 T-Digest 陣列
     * @return 合併後的新 T-Digest
     */
    public TDigest merge(TDigest... digests) {
        TDigest merged = createDigest();
        for (TDigest d : digests) {
            if (d != null) {
                merged.add(d);
            }
        }
        return merged;
    }

    /**
     * 將多個延遲值加入 T-Digest。
     *
     * @param digest 目標 T-Digest
     * @param latencies 延遲值陣列 (毫秒)
     */
    public void addAll(TDigest digest, long... latencies) {
        for (long latency : latencies) {
            digest.add(latency);
        }
    }

    /**
     * 延遲統計資料記錄。
     *
     * @param count 樣本數量
     * @param minMs 最小延遲 (毫秒)
     * @param maxMs 最大延遲 (毫秒)
     * @param avgMs 平均延遲 (毫秒)
     * @param p50Ms P50 延遲 (毫秒)
     * @param p90Ms P90 延遲 (毫秒)
     * @param p95Ms P95 延遲 (毫秒)
     * @param p99Ms P99 延遲 (毫秒)
     */
    public record LatencyStats(
        long count,
        long minMs,
        long maxMs,
        double avgMs,
        double p50Ms,
        double p90Ms,
        double p95Ms,
        double p99Ms
    ) {
        /**
         * 建立空的延遲統計資料。
         */
        public static LatencyStats empty() {
            return new LatencyStats(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        /**
         * 檢查統計資料是否為空。
         */
        public boolean isEmpty() {
            return count == 0;
        }
    }
}
