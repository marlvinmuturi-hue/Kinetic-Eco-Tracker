package Kinetic_Eco.Tracker.data

/**
 * User vehicle / mode choices for finer CO₂ estimates when driving, taking the train, or flying.
 *
 * The profile UX is fuel-type-first: the user picks **Petrol**, **Diesel**, or
 * **Electric** as their primary road fuel, and the rest of the form unfolds
 * conditionally:
 *  - **Petrol / Diesel** → CC band + body type (sedan / SUV / pickup / …)
 *  - **Electric**        → vehicle class (2-/3-wheeler / car) + motor power
 *
 * Trains and aircraft remain orthogonal — they're separate activities the
 * user might use *in addition* to their road vehicle.
 *
 * Per-km factors are illustrative and tune [CO2Factors.getFactor]; they are
 * **not** scientifically calibrated for any particular jurisdiction.
 */

/**
 * Top-level "what's your road vehicle?" choice that drives the conditional UI.
 *
 * Petrol/Diesel route through the [DrivingEngineCcBand] × [VehicleBodyType]
 * × [IceFuel] math; Electric routes through
 * [ElectricVehicleClass] × [ElectricMotorPowerBand].
 */
enum class PrimaryFuelType {
    PETROL,
    DIESEL,
    ELECTRIC;

    companion object {
        val DEFAULT = PETROL

        fun fromStoredName(name: String?): PrimaryFuelType {
            if (name.isNullOrBlank()) return DEFAULT
            return try {
                valueOf(name)
            } catch (_: Exception) {
                DEFAULT
            }
        }

        /** Migration helper: surface a legacy [IceFuel] choice as the new
         *  [PrimaryFuelType] when only the old value is present on disk. */
        fun fromLegacyIceFuel(iceFuel: IceFuel): PrimaryFuelType = when (iceFuel) {
            IceFuel.PETROL -> PETROL
            IceFuel.DIESEL -> DIESEL
        }
    }
}

enum class DrivingEngineCcBand(val co2KgPerKm: Double) {
    UP_TO_1000(0.13),
    CC_1001_1400(0.15),
    CC_1401_1800(0.17),
    CC_1801_2500(0.21),   // Standard car reference (IPCC/EEA 0.21 kg CO₂/km)
    OVER_2500(0.26);

    companion object {
        fun fromStoredName(name: String?): DrivingEngineCcBand {
            if (name.isNullOrBlank()) return DEFAULT
            return try {
                valueOf(name)
            } catch (_: Exception) {
                DEFAULT
            }
        }

        val DEFAULT = CC_1801_2500
    }
}

/**
 * Body-type multiplier on top of the CC band. Sedan is the reference (1.0×);
 * other shapes are heavier, taller, or worse for aerodynamics.
 *
 * Multipliers are loose averages — a real-world LCA would also factor in
 * weight, drag coefficient, transmission losses — but they're enough to
 * differentiate "I drive a hatch" from "I drive a pickup" in the dashboard
 * comparisons that drive this app.
 */
enum class VehicleBodyType(val co2Multiplier: Double) {
    HATCHBACK(0.92),
    SEDAN(1.00),
    SUV_CROSSOVER(1.20),
    PICKUP(1.30),
    MINIVAN_MPV(1.15);

    companion object {
        val DEFAULT = SEDAN

        fun fromStoredName(name: String?): VehicleBodyType {
            if (name.isNullOrBlank()) return DEFAULT
            return try {
                valueOf(name)
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}

/** Petrol vs diesel for **combustion** driving ([ActivityType.DRIVING]); CC bands are petrol-car-typical; diesel applies a higher factor. */
enum class IceFuel {
    PETROL,
    DIESEL;

    companion object {
        private const val DIESEL_VS_PETROL_SAME_CC = 1.10

        fun fromStoredName(name: String?): IceFuel {
            if (name.isNullOrBlank()) return DEFAULT
            return try {
                valueOf(name)
            } catch (_: Exception) {
                DEFAULT
            }
        }

        val DEFAULT = PETROL

        /**
         * CO₂ kg/km for a combustion vehicle, blending engine displacement,
         * body type, and fuel.
         *
         * Reference order: `cc-band × body-type × diesel-bump`. The
         * diesel multiplier is applied last so that, for a given displacement
         * and body, diesel reads ~10 % higher than petrol.
         */
        fun co2FromCcBandAndBody(
            ccBand: DrivingEngineCcBand,
            bodyType: VehicleBodyType,
            fuel: IceFuel
        ): Double {
            val base = ccBand.co2KgPerKm * bodyType.co2Multiplier
            return when (fuel) {
                PETROL -> base
                DIESEL -> base * DIESEL_VS_PETROL_SAME_CC
            }
        }

        /** Backwards-compatible variant for callers that haven't migrated to
         *  body-type aware estimates yet. Treats the vehicle as a sedan. */
        fun co2FromCcBand(ccBand: DrivingEngineCcBand, fuel: IceFuel): Double =
            co2FromCcBandAndBody(ccBand, VehicleBodyType.SEDAN, fuel)
    }
}

/**
 * Electric **road** vehicle class. Replaces the old `ElectricRoadVehicle`
 * (Car / Motorcycle) with three buckets that match how electric mobility
 * actually splits out across markets:
 *
 *  - **2-wheeler**: e-bikes, e-scooters, e-motorcycles. Tiny battery,
 *    fraction of a sedan's per-km CO₂.
 *  - **3-wheeler**: tuk-tuks / auto-rickshaws / cargo trikes. Bigger than
 *    a 2-wheeler but still well below a passenger car.
 *  - **Car**: passenger EV. Reference baseline (well-to-wheel grid mix,
 *    illustrative).
 *
 * The numbers are reference values for an **average** motor in each class;
 * the actual per-km factor combines them with [ElectricMotorPowerBand].
 */
enum class ElectricVehicleClass(val baseCo2KgPerKm: Double) {
    TWO_WHEELER(0.018),
    THREE_WHEELER(0.035),
    CAR(0.053);

    companion object {
        val DEFAULT = CAR

        fun fromStoredName(name: String?): ElectricVehicleClass {
            if (name.isNullOrBlank()) return DEFAULT
            return try {
                valueOf(name)
            } catch (_: Exception) {
                DEFAULT
            }
        }

        /**
         * Migration helper: map the legacy `ElectricRoadVehicle` enum (only
         * Car or Motorcycle were stored) onto the new three-class enum.
         * `ELECTRIC_MOTORCYCLE` lands on `TWO_WHEELER` because that's what
         * the original label actually meant in practice.
         */
        fun fromLegacyElectricRoadVehicle(name: String?): ElectricVehicleClass {
            if (name.isNullOrBlank()) return DEFAULT
            return when (name) {
                "ELECTRIC_CAR" -> CAR
                "ELECTRIC_MOTORCYCLE" -> TWO_WHEELER
                else -> DEFAULT
            }
        }
    }
}

/**
 * Motor power band for an electric road vehicle. Multiplies the base
 * [ElectricVehicleClass.baseCo2KgPerKm] to capture the (well-known) effect
 * that a more powerful motor draws more energy per km on average.
 *
 * Bands are picked to span the realistic range of actual EVs:
 *  - **≤ 3 kW**: kick-scooters, micro e-bikes
 *  - **3 – 10 kW**: heavier scooters, electric mopeds, some 3-wheelers
 *  - **10 – 60 kW**: typical city EV, larger 3-wheelers (reference 1.0×)
 *  - **60 – 150 kW**: mid-range passenger EV (Model 3, ID.4, etc.)
 *  - **> 150 kW**: performance / luxury EVs (high-spec dual-motor)
 */
enum class ElectricMotorPowerBand(val multiplier: Double) {
    UP_TO_3_KW(0.55),
    KW_3_TO_10(0.75),
    KW_10_TO_60(1.0),
    KW_60_TO_150(1.4),
    OVER_150_KW(1.8);

    companion object {
        val DEFAULT = KW_10_TO_60

        fun fromStoredName(name: String?): ElectricMotorPowerBand {
            if (name.isNullOrBlank()) return DEFAULT
            return try {
                valueOf(name)
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}

/**
 * CO₂ factors follow the same "savings vs. car baseline" convention as
 * [CO2Factors.WALKING] and [CO2Factors.CYCLING]: negative = saves vs. the
 * 0.21 kg/km average petrol-car trip that the train journey replaces.
 *
 *  Electric rail:       0.04 kg/km direct → saves 0.21 − 0.04 = 0.17 kg/km
 *  Diesel-electric:    0.078 kg/km direct → saves 0.21 − 0.078 = 0.132 kg/km
 */
enum class TrainPropulsion(val co2KgPerKm: Double) {
    ELECTRIC(-0.17),
    DIESEL_ELECTRIC(-0.132);

    companion object {
        fun fromStoredName(name: String?): TrainPropulsion {
            if (name.isNullOrBlank()) return DEFAULT
            return try {
                valueOf(name)
            } catch (_: Exception) {
                DEFAULT
            }
        }

        val DEFAULT = ELECTRIC
    }
}

/**
 * Aircraft category (typical jet/turboprop mix — not electric propulsion, which is still uncommon).
 */
enum class AircraftCategory(val co2KgPerKm: Double) {
    REGIONAL_TURBOPROP(0.16),
    NARROW_BODY_JET(0.255),
    WIDE_BODY_LONG_HAUL(0.31);

    companion object {
        fun fromStoredName(name: String?): AircraftCategory {
            if (name.isNullOrBlank()) return DEFAULT
            return try {
                valueOf(name)
            } catch (_: Exception) {
                DEFAULT
            }
        }

        val DEFAULT = NARROW_BODY_JET
    }
}

/**
 * User's vehicle config. The road-vehicle half is *both* combustion and
 * electric data — even though the UX makes the user pick a primary fuel
 * type, both branches are persisted so that:
 *  - the activity classifier can still attribute a session to either
 *    [ActivityType.DRIVING] or [ActivityType.ELECTRIC_VEHICLE] without
 *    losing info, and
 *  - users can flip their primary fuel without re-entering everything.
 *
 * [primaryFuelType] is the source of truth for which sub-set of fields
 * the UI surfaces; the others retain sensible defaults.
 */
data class VehicleProfile(
    val primaryFuelType: PrimaryFuelType,
    val iceFuel: IceFuel,
    val drivingCcBand: DrivingEngineCcBand,
    val bodyType: VehicleBodyType,
    val electricVehicleClass: ElectricVehicleClass,
    val electricMotorPower: ElectricMotorPowerBand,
    val trainPropulsion: TrainPropulsion,
    val aircraftCategory: AircraftCategory
) {
    companion object {
        val DEFAULT = VehicleProfile(
            primaryFuelType = PrimaryFuelType.DEFAULT,
            iceFuel = IceFuel.DEFAULT,
            drivingCcBand = DrivingEngineCcBand.DEFAULT,
            bodyType = VehicleBodyType.DEFAULT,
            electricVehicleClass = ElectricVehicleClass.DEFAULT,
            electricMotorPower = ElectricMotorPowerBand.DEFAULT,
            trainPropulsion = TrainPropulsion.DEFAULT,
            aircraftCategory = AircraftCategory.DEFAULT
        )
    }
}
