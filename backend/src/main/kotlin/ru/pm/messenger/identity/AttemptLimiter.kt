package ru.pm.messenger.identity

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class AttemptLimiter(
    private val redis: StringRedisTemplate,
) {
    fun blocked(key: String, limit: Int): Boolean {
        val count = redis.opsForValue().get(key)?.toLongOrNull() ?: 0
        return count >= limit
    }

    fun record(key: String, window: Duration) {
        val count = redis.opsForValue().increment(key) ?: 1
        if (count == 1L) {
            redis.expire(key, window)
        }
    }

    fun clear(key: String) {
        redis.delete(key)
    }
}
