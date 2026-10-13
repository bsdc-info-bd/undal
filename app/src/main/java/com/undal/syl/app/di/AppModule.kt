package com.undal.syl.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.undal.syl.app.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/** Preferences DataStore file used for location, cart and session flags. */
private val Context.undalDataStore: DataStore<Preferences> by preferencesDataStore(name = "undal_prefs")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun preferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.undalDataStore

    @Provides
    @Singleton
    @Named("workerBaseUrl")
    fun workerBaseUrl(): String = UndalBuildInfo.workerBaseUrl

    @Provides
    @Singleton
    @Named("appVersionName")
    fun appVersionName(): String = BuildConfig.VERSION_NAME

    /**
     * One OkHttp client for geocoding, the Worker API and Coil.
     * Timeouts are generous because Bangladeshi mobile data can be slow, and a
     * geocode that times out at three seconds is a worse home page than one that
     * takes eight.
     */
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Nominatim requires an identifying user agent; the app also acts as the API key here. */
    @Provides
    @Singleton
    @Named("nominatimUserAgent")
    fun nominatimUserAgent(): String =
        "UndalApp/${BuildConfig.VERSION_NAME} (+${UndalBuildInfo.workerHost})"
}
