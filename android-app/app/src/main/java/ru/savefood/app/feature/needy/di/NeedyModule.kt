package ru.savefood.app.feature.needy.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.savefood.app.feature.needy.data.NeedyApi
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NeedyModule {

    @Provides
    @Singleton
    fun provideNeedyApi(retrofit: Retrofit): NeedyApi = retrofit.create(NeedyApi::class.java)
}
