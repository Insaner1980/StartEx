package com.finnvek.startex.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WalletProfileEntity::class,
        WalletSecretEnvelopeEntity::class,
        ProviderCredentialEntity::class,
        TrustedAddressEntity::class,
        StrategyConfigEntity::class,
        RiskConfigEntity::class,
        BotSessionEntity::class,
        TokenCandidateEntity::class,
        TokenSnapshotEntity::class,
        DecisionEntity::class,
        PositionEntity::class,
        TradeIntentEntity::class,
        BlockchainTransactionEntity::class,
        FeeRecordEntity::class,
        DailyPerformanceEntity::class,
        ProviderHealthEntity::class,
        AppEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class StartExDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao

    abstract fun providerCredentialDao(): ProviderCredentialDao

    abstract fun configDao(): ConfigDao

    abstract fun botSessionDao(): BotSessionDao

    abstract fun candidateDao(): CandidateDao

    abstract fun snapshotDao(): SnapshotDao

    abstract fun decisionDao(): DecisionDao

    abstract fun positionDao(): PositionDao

    abstract fun ledgerDao(): LedgerDao

    abstract fun dailyPerformanceDao(): DailyPerformanceDao

    abstract fun providerHealthDao(): ProviderHealthDao

    abstract fun appEventDao(): AppEventDao

    companion object {
        fun create(context: Context): StartExDatabase =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    StartExDatabase::class.java,
                    "startex.db",
                ).build()
    }
}
