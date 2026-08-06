package com.aigateway.core.service;

import com.aigateway.core.domain.model.ModelInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 加权随机选择器单测（计划第 16.1 节）。
 *
 * 覆盖边界：
 * - 权重 8:2 分布落在宽松区间（防 flaky）；
 * - 单个候选永远选中；
 * - 空列表 isEmpty() 为 true、pick() 为空。
 */
class WeightedRandomPickerTest {

    private static ModelInstance instance(String instanceId, int weight) {
        // V2 ModelInstance 新增价格/质量/延迟画像字段，测试用默认值
        return new ModelInstance(instanceId, "qwen", "mock-a", "qwen-large",
                weight, null, 0.0, 0.0, 0.5, 1000L);
    }

    @Test
    void weightedDistribution_shouldFollowWeights() {
        // 权重 8:2，跑 1000 次：a 的出现次数应接近 800（宽松区间防 flaky）
        var picker = new WeightedRandomPicker(List.of(instance("mock-a:qwen-large", 8),
                instance("mock-b:qwen-small", 2)));

        int countA = 0;
        for (int i = 0; i < 1000; i++) {
            if (picker.pick().orElseThrow().instanceId().startsWith("mock-a")) {
                countA++;
            }
        }
        assertThat(countA).isBetween(750, 850); // 8/10 概率，宽松区间
    }

    @Test
    void singleCandidate_shouldAlwaysPickIt() {
        var picker = new WeightedRandomPicker(List.of(instance("only:model", 1)));

        for (int i = 0; i < 100; i++) {
            assertThat(picker.pick()).get()
                    .extracting(ModelInstance::instanceId).isEqualTo("only:model");
        }
    }

    @Test
    void emptyPicker_shouldReturnEmpty() {
        var picker = new WeightedRandomPicker(List.of());

        assertThat(picker.isEmpty()).isTrue();
        assertThat(picker.pick()).isEmpty();
    }
}
