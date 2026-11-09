package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testRedisConnection() {
        String pong = stringRedisTemplate.getConnectionFactory().getConnection().ping();
        System.out.println("Redis PING => " + pong);
        assert "PONG".equals(pong);
    }
}
