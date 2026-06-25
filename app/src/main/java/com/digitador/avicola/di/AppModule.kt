package com.digitador.avicola.di

import android.content.Context
import com.digitador.avicola.data.db.DigitadorDatabase
import com.digitador.avicola.data.db.dao.PartidaDao
import com.digitador.avicola.data.db.dao.SemanaDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): DigitadorDatabase =
        DigitadorDatabase.getInstance(ctx)

    @Provides
    @Singleton
    fun providePartidaDao(db: DigitadorDatabase): PartidaDao = db.partidaDao()

    @Provides
    @Singleton
    fun provideSemanaDao(db: DigitadorDatabase): SemanaDao = db.semanaDao()
}
