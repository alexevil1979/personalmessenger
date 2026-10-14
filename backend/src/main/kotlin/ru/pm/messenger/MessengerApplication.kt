package ru.pm.messenger

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import ru.pm.messenger.identity.AuthProperties

@SpringBootApplication
@EnableConfigurationProperties(AuthProperties::class)
class MessengerApplication

fun main(args: Array<String>) {
    runApplication<MessengerApplication>(*args)
}
