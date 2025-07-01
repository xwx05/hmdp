package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    // 用于redis逻辑过期时间
    private LocalDateTime expireTime;
    private Object data;
}
