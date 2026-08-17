package com.aigateway.config.model;

public record ConfigError(String path, String reason) {
    
    @Override
    public String toString(){
        return "gateway. " + path + " -> " + reason;
    }

    /** 快捷构造：路径直接用“channels[0].weight */
    public static ConfigError at(String path, String reason){
        return new ConfigError(path, reason);
    }
}
