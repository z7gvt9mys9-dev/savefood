package kz.savefood.app.core.push.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kz.savefood.app.core.push.PushApi
import retrofit2.Retrofit
import javax.inject.Singleton

/** Provides [PushApi] from the shared Retrofit instance (configured in
 *  NetworkModule). Kept separate so the push feature owns its own wiring. */
@Module
@InstallIn(SingletonComponent::class)
object PushModule {

    @Provides
    @Singleton
    fun providePushApi(retrofit: Retrofit): PushApi = retrofit.create(PushApi::class.java)
}
