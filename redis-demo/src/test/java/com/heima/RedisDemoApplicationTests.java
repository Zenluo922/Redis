package com.heima;

import com.heima.redis.pojo.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

@SpringBootTest
class RedisDemoApplicationTests {
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    @Test
    void testString() {
        redisTemplate.opsForValue().set("name","张无忌");

        Object name = redisTemplate.opsForValue().get("name");
        System.out.println(name);
    }

    @Test
    void testUser() {
        redisTemplate.opsForValue().set("user:100",new User("张无忌",20));

        User user =(User)redisTemplate.opsForValue().get("user:100");
        System.out.println("user = " + user);
    }

}
