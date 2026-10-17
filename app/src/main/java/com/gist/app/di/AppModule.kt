package com.gist.app.di

import android.content.Context
import androidx.room.Room
import com.gist.app.data.GistDatabase
import com.gist.app.data.MessageDao
import com.gist.app.data.SecureStore
import com.gist.app.data.SessionDao
import com.gist.app.data.TinkSecureStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModule {
    @Binds
    @Singleton
    abstract fun bindSecureStore(impl: TinkSecureStore): SecureStore
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient {
        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000L
                requestTimeoutMillis = 60_000L
                socketTimeoutMillis = 60_000L
            }
            engine {
                config {
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(60, TimeUnit.SECONDS)
                    followRedirects(true)
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GistDatabase {
        return Room.databaseBuilder(
            context,
            GistDatabase::class.java,
            "gist.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideSessionDao(db: GistDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideMessageDao(db: GistDatabase): MessageDao = db.messageDao()
}
