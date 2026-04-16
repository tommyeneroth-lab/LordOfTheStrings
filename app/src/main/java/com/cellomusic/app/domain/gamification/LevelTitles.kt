package com.cellomusic.app.domain.gamification

/**
 * Flavour titles for each level tier. Keeps bare "Level 12" from feeling
 * clinical — a short noun tells the player where they are on the arc.
 *
 * Tiers deliberately widen at higher levels so advancement feels
 * significant without stalling. Every tier is cello-flavoured rather than
 * generic RPG (Apprentice → Luthier's Apprentice, etc.) — the goal is
 * making players feel like serious musicians, not game characters.
 */
object LevelTitles {

    /** Plain noun title for this level, e.g. "Journeyman". */
    fun titleFor(level: Int): String = when {
        level <= 0   -> "Beginner"
        level < 5    -> "Novice"
        level < 10   -> "Apprentice"
        level < 20   -> "Journeyman"
        level < 30   -> "Artisan"
        level < 50   -> "Virtuoso"
        level < 75   -> "Maestro"
        level < 100  -> "Grand Maestro"
        else         -> "Legendary"
    }

    /** "Level 12 · Journeyman" — what the header bar shows. */
    fun formatLevelLine(level: Int): String = "Level $level · ${titleFor(level)}"

    /**
     * True when [newLevel] crosses into a new title tier compared to
     * [oldLevel]. Useful for "you earned a new title!" toasts.
     */
    fun isNewTier(oldLevel: Int, newLevel: Int): Boolean =
        titleFor(oldLevel) != titleFor(newLevel)
}
