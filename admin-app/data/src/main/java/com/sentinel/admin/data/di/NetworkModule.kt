package com.sentinel.admin.data.di

import android.content.Context
import com.sentinel.admin.data.remote.ReconnectPolicy
import com.sentinel.admin.data.remote.SequenceGenerator
import com.sentinel.admin.data.remote.api.DeviceApi
import com.sentinel.admin.data.remote.protocol.DeviceUpdateEventMapper
import com.sentinel.admin.data.remote.protocol.MessageSerializer
import com.sentinel.admin.data.remote.websocket.WebSocketDataSource
import com.sentinel.admin.data.repository.AuthRepositoryImpl
import com.sentinel.admin.data.repository.AudioRepositoryImpl
import com.sentinel.admin.data.repository.ConnectionRepositoryImpl
import com.sentinel.admin.data.repository.ContactRepositoryImpl
import com.sentinel.admin.data.repository.DeviceRepositoryImpl
import com.sentinel.admin.data.session.SessionPreferencesImpl
import com.sentinel.admin.domain.repository.AudioRepository
import com.sentinel.admin.domain.repository.AuthRepository
import com.sentinel.admin.domain.repository.ConnectionRepository
import com.sentinel.admin.domain.repository.ContactRepository
import com.sentinel.admin.domain.repository.DeviceRepository
import com.sentinel.admin.domain.session.SessionPreferences
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module providing network-layer dependencies for the Admin application.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context? = null
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)

        if (context != null) {
            val attestor = com.sentinel.admin.data.security.SovereignSiliconAttestor(context)
            builder.addInterceptor { chain ->
                val proof = try { attestor.generateSiliconProof("ADMIN-001") } catch (_: Throwable) { null }
                val reqBuilder = chain.request().newBuilder()
                if (proof != null && proof.siliconDnaHash.isNotBlank()) {
                    reqBuilder.header("X-Sentinel-Silicon-DNA", proof.siliconDnaHash)
                    reqBuilder.header("X-Sentinel-Device-Model", proof.deviceModel)
                    reqBuilder.header("X-Sentinel-SoC-Model", proof.socModel)
                    if (proof.hwNonce.isNotBlank() && proof.hwSignature.isNotBlank() && proof.hwCertBase64.isNotBlank()) {
                        reqBuilder.header("X-Sentinel-HW-Nonce", proof.hwNonce)
                        reqBuilder.header("X-Sentinel-HW-Sig", proof.hwSignature)
                        reqBuilder.header("X-Sentinel-HW-Cert", proof.hwCertBase64)
                    }
                }
                chain.proceed(reqBuilder.build())
            }
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder().build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        moshi: Moshi,
        sessionPreferences: SessionPreferences
    ): Retrofit {
        val baseUrl = deriveHttpBaseUrl(sessionPreferences.serverUrl)
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideDeviceApi(retrofit: Retrofit): DeviceApi {
        return retrofit.create(DeviceApi::class.java)
    }

    /**
     * Converts a WebSocket URL to an HTTP base URL.
     * ws://host:port/ws  → http://host:port/
     * wss://host:port/ws → https://host:port/
     */
    private fun deriveHttpBaseUrl(wsUrl: String?): String {
        if (wsUrl.isNullOrBlank()) return "http://localhost:8080/"
        return try {
            val httpScheme = if (wsUrl.startsWith("wss://")) "https" else "http"
            val withoutScheme = wsUrl
                .removePrefix("wss://")
                .removePrefix("ws://")
            val hostPort = withoutScheme.split("/").first()
            "$httpScheme://$hostPort/"
        } catch (_: Exception) {
            "http://localhost:8080/"
        }
    }

    @Provides
    @Singleton
    fun provideWebSocketDataSource(client: OkHttpClient): WebSocketDataSource {
        return WebSocketDataSource(client)
    }

    @Provides
    @Singleton
    fun provideMessageSerializer(moshi: Moshi): MessageSerializer {
        return MessageSerializer(moshi)
    }

    @Provides
    @Singleton
    fun provideSequenceGenerator(): SequenceGenerator {
        return SequenceGenerator()
    }

    @Provides
    @Singleton
    fun provideReconnectPolicy(): ReconnectPolicy {
        return ReconnectPolicy()
    }

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideConnectionRepository(
        webSocketDataSource: WebSocketDataSource,
        messageSerializer: MessageSerializer,
        scope: CoroutineScope
    ): ConnectionRepository {
        return ConnectionRepositoryImpl(webSocketDataSource, messageSerializer, scope)
    }

    @Provides
    @Singleton
    fun provideAuthRepository(@ApplicationContext context: Context): AuthRepository {
        return AuthRepositoryImpl(context)
    }

    @Provides
    @Singleton
    fun provideDeviceUpdateEventMapper(): DeviceUpdateEventMapper {
        return DeviceUpdateEventMapper()
    }

    @Provides
    @Singleton
    fun provideDeviceRepository(
        deviceApi: DeviceApi,
        authRepository: AuthRepository,
        connectionRepository: ConnectionRepository,
        eventMapper: DeviceUpdateEventMapper,
        moshi: Moshi,
        @ApplicationContext context: Context,
        scope: CoroutineScope
    ): DeviceRepository {
        return DeviceRepositoryImpl(
            deviceApi, authRepository, connectionRepository,
            eventMapper, moshi, scope, context
        )
    }

    @Provides
    @Singleton
    fun provideSessionPreferences(@ApplicationContext context: Context): SessionPreferences {
        return SessionPreferencesImpl(context)
    }

    @Provides
    @Singleton
    fun provideAudioRepository(
        connectionRepository: ConnectionRepository,
        messageSerializer: MessageSerializer,
        sequenceGenerator: SequenceGenerator
    ): AudioRepository {
        return AudioRepositoryImpl(connectionRepository, messageSerializer, sequenceGenerator)
    }

    @Provides
    @Singleton
    fun provideContactRepository(
        @ApplicationContext context: Context
    ): ContactRepository {
        return ContactRepositoryImpl(context)
    }

    @Provides
    @Singleton
    fun providePttAudioRecorder(
        @ApplicationContext context: Context
    ): com.sentinel.admin.data.audio.PttAudioRecorder {
        return com.sentinel.admin.data.audio.AndroidPttAudioRecorder(context)
    }
}
