package com.cellomusic.app.domain.achievement

/**
 * Static catalog of all achievements the app knows about. IDs are persisted
 * in the `achievements` table; everything else (title, description, icon,
 * tier, unlock predicate) lives here and can evolve across releases without
 * DB migrations.
 *
 * Tiers map to visual styling only (bronze/silver/gold ring + halo).
 */
object AchievementCatalog {

    enum class Tier { BRONZE, SILVER, GOLD }

    data class Def(
        val id: String,
        val title: String,
        val description: String,
        /** Emoji shown in the grid. Keeps us free of bitmap assets for v1. */
        val icon: String,
        val tier: Tier,
        /** Returns true when the player has earned this achievement. */
        val check: (AchievementStats) -> Boolean
    )

    /**
     * Snapshot of everything the catalog's [Def.check] predicates need.
     * Computed once per evaluation pass by [AchievementEvaluator].
     */
    data class AchievementStats(
        val totalSessions: Int,
        val lifetimeMinutes: Int,
        val maxSingleSessionMinutes: Int,
        val longestStreak: Int,
        val metronomeSessions: Int,
        val fiveStarSessions: Int,
        val distinctCategories: Int,
        val intonationMinutes: Int,
        val vibratoMinutes: Int,
        val bowTechniqueMinutes: Int,
        val scalesMinutes: Int,
        val sightReadingMinutes: Int,
        val memorizationMinutes: Int,
        /** Hour-of-day (0-23) of the session just saved, in local time. */
        val lastSessionHour: Int
    )

    val ALL: List<Def> = listOf(
        // ─── Session counts ─────────────────────────────────────────────
        Def("first_draw", "First Draw",
            "Complete your first practice session",
            "🎻", Tier.BRONZE) { it.totalSessions >= 1 },
        Def("bow_warrior", "Bow Warrior",
            "Complete 10 practice sessions",
            "⚔", Tier.BRONZE) { it.totalSessions >= 10 },
        Def("journeyman", "Journeyman",
            "Complete 50 practice sessions",
            "🎭", Tier.SILVER) { it.totalSessions >= 50 },
        Def("centurion", "Centurion",
            "Complete 100 practice sessions",
            "🏛", Tier.GOLD) { it.totalSessions >= 100 },

        // ─── Single-session length ──────────────────────────────────────
        Def("marathoner", "Marathoner",
            "Practice 60+ minutes in a single session",
            "🏃", Tier.SILVER) { it.maxSingleSessionMinutes >= 60 },
        Def("ultra", "Ultra-Focused",
            "Practice 120+ minutes in a single session",
            "🔥", Tier.GOLD) { it.maxSingleSessionMinutes >= 120 },

        // ─── Streaks ────────────────────────────────────────────────────
        Def("streak_3", "Three Days Strong",
            "Reach a 3-day practice streak",
            "🔥", Tier.BRONZE) { it.longestStreak >= 3 },
        Def("streak_7", "Weekly Ritual",
            "Reach a 7-day practice streak",
            "🔥", Tier.SILVER) { it.longestStreak >= 7 },
        Def("streak_30", "Iron Bow",
            "Reach a 30-day practice streak",
            "🔥", Tier.GOLD) { it.longestStreak >= 30 },
        Def("streak_100", "Unbroken",
            "Reach a 100-day practice streak",
            "💎", Tier.GOLD) { it.longestStreak >= 100 },

        // ─── Lifetime minutes ───────────────────────────────────────────
        Def("time_1h", "First Hour",
            "Accumulate 1 hour of lifetime practice",
            "⏱", Tier.BRONZE) { it.lifetimeMinutes >= 60 },
        Def("time_10h", "Ten Hour Club",
            "Accumulate 10 hours of lifetime practice",
            "⏱", Tier.SILVER) { it.lifetimeMinutes >= 600 },
        Def("time_100h", "Hundred-Hour Artisan",
            "Accumulate 100 hours of lifetime practice",
            "⏳", Tier.GOLD) { it.lifetimeMinutes >= 6_000 },

        // ─── Quality / metronome / variety ──────────────────────────────
        Def("metronome_5", "Metronome Friend",
            "Practice with metronome BPM recorded in 5 sessions",
            "⏲", Tier.BRONZE) { it.metronomeSessions >= 5 },
        Def("metronome_25", "Time Lord",
            "Practice with metronome BPM recorded in 25 sessions",
            "⏲", Tier.SILVER) { it.metronomeSessions >= 25 },
        Def("perfectionist", "Perfectionist",
            "Rate 10 sessions as 5/5",
            "⭐", Tier.SILVER) { it.fiveStarSessions >= 10 },
        Def("renaissance", "Renaissance",
            "Practice in at least 6 different categories",
            "🎨", Tier.SILVER) { it.distinctCategories >= 6 },

        // ─── Category mastery ───────────────────────────────────────────
        Def("intonation_master", "Intonation Master",
            "Accumulate 10 hours on intonation",
            "👂", Tier.GOLD) { it.intonationMinutes >= 600 },
        Def("vibrato_virtuoso", "Vibrato Virtuoso",
            "Accumulate 5 hours on vibrato",
            "〰", Tier.SILVER) { it.vibratoMinutes >= 300 },
        Def("bow_technician", "Bow Technician",
            "Accumulate 5 hours on bow technique",
            "🏹", Tier.SILVER) { it.bowTechniqueMinutes >= 300 },
        Def("scale_climber", "Scale Climber",
            "Accumulate 3 hours on scales",
            "🪜", Tier.BRONZE) { it.scalesMinutes >= 180 },
        Def("sight_reader", "Sight Reader",
            "Accumulate 3 hours of sight-reading",
            "👁", Tier.BRONZE) { it.sightReadingMinutes >= 180 },
        Def("memory_keeper", "Memory Keeper",
            "Accumulate 2 hours of memorization",
            "🧠", Tier.BRONZE) { it.memorizationMinutes >= 120 },

        // ─── Time-of-day ────────────────────────────────────────────────
        Def("night_owl", "Night Owl",
            "Save a session started after 22:00",
            "🦉", Tier.BRONZE) { it.lastSessionHour >= 22 },
        Def("early_bird", "Early Bird",
            "Save a session started before 07:00",
            "🐓", Tier.BRONZE) { it.lastSessionHour in 0..6 }
    )

    fun byId(id: String): Def? = ALL.firstOrNull { it.id == id }
}
