package com.aigateway.core.service;

import com.aigateway.core.domain.model.ModelInstance;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 加权随机选择器（不可变，构建后线程安全）。
 * 算法：把候选按权重在数轴上依次累加，随机数落在哪个区间就选谁。
 */
public final class WeightedRandomPicker{
    private final List<ModelInstance> candidates;
    private final int[] cumulative;   // cumulative[i] = 前 i+1 个候选的权重和
    private final int totalWeight;

    public WeightedRandomPicker(List<ModelInstance> candidates){
        this.candidates = List.copyOf(candidates);
        this.cumulative = new int[candidates.size()];
        int sum = 0;
        for(int i = 0; i < candidates.size();i++){
            sum += candidates.get(i).weight();
            cumulative[i] = sum;
        }
        this.totalWeight = sum;
    }

    public boolean isEmpty(){
        return totalWeight <= 0;
    }

    public Optional<ModelInstance> pick(){
        if(isEmpty()){
            return Optional.empty();
        }
        int r = ThreadLocalRandom.current().nextInt(totalWeight);
        for(int i = 0; i < cumulative.length; i ++){
            if(r < cumulative[i]){
                return Optional.of(candidates.get(i));
            }
        }
        return Optional.of(candidates.get(candidates.size() - 1));
    }
}