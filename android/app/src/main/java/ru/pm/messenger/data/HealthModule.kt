package ru.pm.messenger.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HealthModule {
    @Binds
    @Singleton
    abstract fun healthApi(api: OkHttpHealthApi): HealthApi

    @Binds
    @Singleton
    abstract fun authApi(api: OkHttpAuthApi): AuthApi
}
