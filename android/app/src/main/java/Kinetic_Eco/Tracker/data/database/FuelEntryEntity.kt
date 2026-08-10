package Kinetic_Eco.Tracker.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One fill-up, as the user records it at the pump.
 *
 * This is the input that turns cost from an estimate into a measurement. The app
 * already knows exactly how far the user drove — that is what it does all day — so
 * pairing measured distance with measured litres yields the user's *actual* fuel
 * economy, without ever asking for an odometer reading.
 *
 * A generic calculator has to ask "what is your fuel economy?" and take the answer on
 * faith. This one can work it out, and get better at it every time.
 *
 * Deliberately only two required numbers, [litres] and [amountPaid], both printed on
 * every receipt. Anything more and people stop logging after a fortnight.
 */
@Entity(
    tableName = "fuel_entries",
    // Declared here as well as created in MIGRATION_9_10. Room validates the migrated
    // schema against the entity, so an index or default that exists in the migration
    // but not the entity makes the two disagree — and a mismatch is not a warning, it
    // throws on open. Every reader of this table filters by user and orders by time.
    indices = [Index(value = ["userId", "filledAtMs"])]
)
data class FuelEntryEntity(
    @PrimaryKey val id: String,
    val userId: String,
    /** When the tank was filled. Drives which tracked driving falls in the interval. */
    val filledAtMs: Long,
    val litres: Double,
    /** Total paid, in [currencyCode]. Divided by litres this also yields a real price. */
    val amountPaid: Double,
    val currencyCode: String,
    /**
     * Whether the tank was filled to the brim.
     *
     * Only full-to-full intervals give a valid economy: a partial fill leaves an
     * unknown amount already in the tank, so the litres bought do not correspond to
     * the distance driven. Partial entries are still stored — they are real spending,
     * and they still reveal the local price — but [FuelEconomyCalculator] ignores them.
     */
    @ColumnInfo(defaultValue = "1") val isFullTank: Boolean = true,
    val createdAt: Long,
    /**
     * True once this entry is confirmed written to Firestore.
     *
     * Fuel entries are hand-typed and unrecoverable — unlike a session, nobody can
     * re-walk a fill-up. New rows start false and a retry pass picks them up, so an
     * entry logged at a pump with no signal is not lost.
     */
    @ColumnInfo(defaultValue = "0") val synced: Boolean = false
)