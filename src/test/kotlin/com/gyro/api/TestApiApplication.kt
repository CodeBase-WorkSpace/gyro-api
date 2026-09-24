package com.gyro.api

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<ApiApplication>().with(TestcontainersConfiguration::class).run(*args)
}
