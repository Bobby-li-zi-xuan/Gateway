package com.aigateway.core.service;

import com.aigateway.core.domain.model.ModelInstance;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 加权随机选择器（不可变，构建后线程安全）。
 *
 * 算法（面试高频）：把候选按权重依次“摆”在一根数轴上：
 *   候选A 权重 8 → 区间 [0, 8)
 *   候选B 权重 2 → 区间 [8, 10)
 * 生成 [0, 总权重) 的随机数，落在哪个区间就选哪个候选，
 * 因此每个候选被选中的概率 = 自己的权重 / 总权重。
 *
 * 🖊 手敲 H1：本类为必手敲核心算法，按《版本1-详细实施计划》第 8 节实现。
 */
public final class WeightedRandomPicker {

    private final List<ModelInstance> candidates; // 候选列表（构造时防御性拷贝）
    private final int[] cumulative;               // cumulative[i] = 前 i+1 个候选的权重和
    private final int totalWeight;                // 所有权重之和，即数轴总长度

    public WeightedRandomPicker(List<ModelInstance> candidates) {
        // 防御性拷贝：外部再修改原列表不会影响本选择器，同时保证只读线程安全
        this.candidates = List.copyOf(candidates);
        this.cumulative = new int[candidates.size()];
        int sum = 0;
        for (int i = 0; i < candidates.size(); i++) {
            sum += candidates.get(i).weight();
            cumulative[i] = sum; // 第 i 个候选的区间右端点
        }
        this.totalWeight = sum;
    }

    /** 权重和 <= 0（空列表或全 0 权重）时视为空 */
    public boolean isEmpty() {
        return totalWeight <= 0;
    }

    /**
     * 做一次加权随机选择。
     *
     * @return 被选中的候选；列表为空时返回 {@link Optional#empty()}
     */
    public Optional<ModelInstance> pick() {
        if (isEmpty()) {
            return Optional.empty();
        }
        // [0, totalWeight) 的随机数：右开区间保证不会“越界”到数轴末尾
        int r = ThreadLocalRandom.current().nextInt(totalWeight);
        // 从第一个候选开始，找到第一个“右端点 > r”的候选即命中
        for (int i = 0; i < cumulative.length; i++) {
            if (r < cumulative[i]) {
                return Optional.of(candidates.get(i));
            }
        }
        // 防御性兜底：理论上走不到（r 一定落在某个区间内）
        return Optional.of(candidates.get(candidates.size() - 1));
    }
}
