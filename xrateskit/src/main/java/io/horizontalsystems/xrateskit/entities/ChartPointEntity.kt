package io.horizontalsystems.xrateskit.entities

import androidx.room.Entity
import java.math.BigDecimal

@Entity(primaryKeys = ["type", "coin", "currency", "timestamp"])
class ChartPointEntity(
        val type: ChartType,
        val coin: String,
        val currency: String,
        val value: BigDecimal,
        val volume: BigDecimal,
        val timestamp: Long
)
