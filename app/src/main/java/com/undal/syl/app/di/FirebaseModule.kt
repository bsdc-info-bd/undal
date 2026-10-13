package com.undal.syl.app.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.perf.FirebasePerformance
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Firebase singletons. Exposing them through Hilt keeps every repository
 * testable and guarantees a single instance per process.
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun auth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides
    @Singleton
    fun realtime(): FirebaseDatabase = FirebaseDatabase.getInstance()

    @Provides
    @Singleton
    fun messaging(): FirebaseMessaging = FirebaseMessaging.getInstance()

    @Provides
    @Singleton
    fun performance(): FirebasePerformance = FirebasePerformance.getInstance()
}
