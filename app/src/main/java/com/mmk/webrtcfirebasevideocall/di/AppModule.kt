package com.mmk.webrtcfirebasevideocall.di

import android.content.Context
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context:Context) : Context = context.applicationContext

    @Provides
    @Singleton
    fun provideGson():Gson = Gson()

    @Provides
    @Singleton
    fun provideDataBaseInstance():FirebaseDatabase = FirebaseDatabase.getInstance("https://webrtcfirebasevideocall-3751e-default-rtdb.asia-southeast1.firebasedatabase.app/")

    @Provides
    @Singleton
    fun provideDatabaseReference(db:FirebaseDatabase): DatabaseReference = db.reference
}