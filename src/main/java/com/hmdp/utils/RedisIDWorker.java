package com.hmdp.utils;

import io.lettuce.core.ScriptOutputType;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@Data
public class RedisIDWorker {
    private static final long BEGIN_TIMESTAMP = 1704067200;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public Long nextId(String prefix){
        //1. 获取时间戳
        long epochSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timeStamp = epochSecond - BEGIN_TIMESTAMP;

        //2. 获取序列号
        //将当前时间按指定格式转化为字符串
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long number = stringRedisTemplate.opsForValue().increment("icr" + prefix + ":" + date);

        //3. 拼接并返回
        return timeStamp << 32 | number;
    }

/*    public static void main(String[] args) {
        LocalDateTime localDateTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
        long epochSecond = localDateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println(epochSecond);
    }*/
}
