package kz.savefood.app.feature.volunteer.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kz.savefood.app.feature.volunteer.data.VolunteerApi
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VolunteerModule {

    @Provides
    @Singleton
    fun provideVolunteerApi(retrofit: Retrofit): VolunteerApi = retrofit.create(VolunteerApi::class.java)
}
