package ru.savefood.app.feature.shop.di
import android.content.Context
import coil.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.savefood.app.feature.shop.data.ShopApi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Named
import javax.inject.Singleton
@Module
@InstallIn(SingletonComponent::class)
object ShopModule {
    @Provides
    @Singleton
    fun provideShopApi(retrofit: Retrofit): ShopApi = retrofit.create(ShopApi::class.java)
    @Provides
    @Singleton
    @Named("authImageLoader")
    fun provideAuthImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(okHttpClient)
        .build()
}
