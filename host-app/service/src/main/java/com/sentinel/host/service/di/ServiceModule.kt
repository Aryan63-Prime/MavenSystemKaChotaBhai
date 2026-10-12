package com.sentinel.host.service.di

import android.content.Context
import com.sentinel.host.data.audio.AudioFrameBuilder
import com.sentinel.host.data.audio.AudioPipeline
import com.sentinel.host.data.di.ApplicationScope
import com.sentinel.host.data.motion.AndroidMotionDetector
import com.sentinel.host.data.remote.ReconnectPolicy
import com.sentinel.host.data.remote.SequenceGenerator
import com.sentinel.host.data.remote.protocol.MessageSerializer
import com.sentinel.host.data.repository.AudioRepositoryImpl
import com.sentinel.host.domain.audio.AudioRecorder
import com.sentinel.host.domain.audio.OpusEncoder
import com.sentinel.host.domain.location.LocationProvider
import com.sentinel.host.domain.motion.MotionDetector
import com.sentinel.host.domain.network.NetworkObserver
import com.sentinel.host.domain.repository.AuthRepository
import com.sentinel.host.domain.repository.ConnectionRepository
import com.sentinel.host.domain.repository.DeviceRepository
import com.sentinel.host.domain.repository.FileRepository
import com.sentinel.host.domain.repository.LocationRepository
import com.sentinel.host.domain.session.SessionManager
import com.sentinel.host.service.AudioStreamer
import com.sentinel.host.service.ConnectionSupervisor
import com.sentinel.host.service.FileStreamer
import com.sentinel.host.service.HeartbeatScheduler
import com.sentinel.host.service.LocationStreamer
import com.sentinel.host.service.RamTelemetryBuffer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideHeartbeatScheduler(
        connectionRepository: ConnectionRepository,
        messageSerializer: MessageSerializer,
        sequenceGenerator: SequenceGenerator,
        @ApplicationScope scope: CoroutineScope
    ): HeartbeatScheduler {
        return HeartbeatScheduler(
            connectionRepository, messageSerializer, sequenceGenerator, scope
        )
    }

    @Provides
    @Singleton
    fun provideMotionDetector(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope
    ): MotionDetector {
        return AndroidMotionDetector(context, scope)
    }

    @Provides
    @Singleton
    fun provideRamTelemetryBuffer(): RamTelemetryBuffer {
        return RamTelemetryBuffer()
    }

    @Provides
    @Singleton
    fun provideLocationStreamer(
        locationProvider: LocationProvider,
        locationRepository: LocationRepository,
        motionDetector: MotionDetector,
        ramBuffer: RamTelemetryBuffer,
        deadReckoningEngine: com.sentinel.host.data.location.DeadReckoningEngine,
        @ApplicationScope scope: CoroutineScope
    ): LocationStreamer {
        return LocationStreamer(
            locationProvider = locationProvider,
            locationRepository = locationRepository,
            scope = scope,
            motionDetector = motionDetector,
            ramBuffer = ramBuffer,
            deadReckoningEngine = deadReckoningEngine
        )
    }

    @Provides
    @Singleton
    fun provideAudioPipeline(
        recorder: AudioRecorder,
        encoder: OpusEncoder
    ): AudioPipeline {
        return AudioPipeline(recorder, encoder)
    }

    @Provides
    @Singleton
    fun provideAudioRepositoryImpl(
        pipeline: AudioPipeline,
        connectionRepository: ConnectionRepository
    ): AudioRepositoryImpl {
        return AudioRepositoryImpl(pipeline, connectionRepository)
    }

    @Provides
    @Singleton
    fun provideAudioStreamer(
        audioRepository: AudioRepositoryImpl,
        pipeline: AudioPipeline,
        sessionManager: SessionManager,
        @ApplicationScope scope: CoroutineScope,
        @ApplicationContext context: Context
    ): AudioStreamer {
        return AudioStreamer(audioRepository, pipeline, scope, context, sessionManager = sessionManager)
    }

    @Provides
    @Singleton
    fun provideFileStreamer(
        fileRepository: FileRepository,
        connectionRepository: ConnectionRepository,
        messageSerializer: MessageSerializer,
        @ApplicationScope scope: CoroutineScope
    ): FileStreamer {
        return FileStreamer(fileRepository, connectionRepository, messageSerializer, scope)
    }

    @Provides
    @Singleton
    fun provideConnectionSupervisor(
        connectionRepository: ConnectionRepository,
        sessionManager: SessionManager,
        authRepository: AuthRepository,
        deviceRepository: DeviceRepository,
        networkObserver: NetworkObserver,
        reconnectPolicy: ReconnectPolicy,
        heartbeatScheduler: HeartbeatScheduler,
        locationStreamer: LocationStreamer,
        audioStreamer: AudioStreamer,
        fileStreamer: FileStreamer,
        offlineBuffer: com.sentinel.host.data.location.OfflineTelemetryBuffer,
        @ApplicationScope scope: CoroutineScope
    ): ConnectionSupervisor {
        return ConnectionSupervisor(
            connectionRepository, sessionManager, authRepository,
            deviceRepository, networkObserver, reconnectPolicy,
            heartbeatScheduler, locationStreamer, audioStreamer, fileStreamer, scope,
            offlineBuffer = offlineBuffer
        )
    }
}
