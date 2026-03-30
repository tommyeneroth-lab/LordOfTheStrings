package com.cellomusic.app.musicxml

import android.util.Xml
import com.cellomusic.app.domain.model.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Parses MusicXML files into the Score domain model using XmlPullParser
 * (streaming, memory-efficient). Handles MusicXML 3.1 and 4.0.
 */
class MusicXmlParser {

    fun parse(file: File): Score = file.inputStream().use { parse(it, file.nameWithoutExtension) }

    fun parse(inputStream: InputStream, defaultTitle: String = "Unknown"): Score {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        var title = defaultTitle
        var composer: String? = null
        var arranger: String? = null
        var copyright: String? = null
        var workNumber: String? = null
        val parts = mutableListOf<Part>()
        val partMap = mutableMapOf<String, PartBuilder>()
        val globalTempos = mutableListOf<TempoMark>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "work-title" -> title = parser.nextText()
                    "work-number" -> workNumber = parser.nextText()
                    "creator" -> {
                        val type = parser.getAttributeValue(null, "type")
                        val name = parser.nextText()
                        when (type) {
                            "composer" -> composer = name
                            "arranger" -> arranger = name
                        }
                    }
                    "rights" -> copyright = parser.nextText()
                    "score-part" -> {
                        val id = parser.getAttributeValue(null, "id") ?: "P1"
                        partMap[id] = PartBuilder(id)
                    }
                    "part-name" -> {
                        val id = partMap.keys.lastOrNull()
                        if (id != null) partMap[id]?.name = parser.nextText()
                    }
                    "part" -> {
                        val id = parser.getAttributeValue(null, "id") ?: "P1"
                        val builder = partMap.getOrPut(id) { PartBuilder(id) }
                        parsePart(parser, builder)
                        parts.add(builder.build())
                    }
                }
            }
            eventType = parser.next()
        }

        return Score(
            id = UUID.randomUUID().toString(),
            title = title,
            composer = composer,
            arranger = arranger,
            copyright = copyright,
            workNumber = workNumber,
            parts = parts,
            globalTempos = globalTempos
        )
    }

    private fun parsePart(parser: XmlPullParser, builder: PartBuilder) {
        val ns = null
        var currentTimeSignature: TimeSignature? = null
        var currentKeySignature: KeySignature? = null
        var currentClef: Clef? = null
        var currentTempo: TempoMark? = null
        var divisions = 1

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "part")) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "measure") {
                val measureNumber = parser.getAttributeValue(ns, "number")?.toIntOrNull() ?: 1
                val measure = parseMeasure(
                    parser, measureNumber, divisions,
                    currentTimeSignature, currentKeySignature, currentClef, currentTempo
                )

                // carry over state for next measure
                measure.timeSignature?.let { currentTimeSignature = it }
                measure.keySignature?.let { currentKeySignature = it }
                measure.clef?.let { currentClef = it }
                measure.tempo?.let { currentTempo = it }
                measure.elements.filterIsInstance<Note>().firstOrNull()

                builder.measures.add(measure)
            }
            parser.next()
        }
    }

    private fun parseMeasure(
        parser: XmlPullParser,
        number: Int,
        divisions: Int,
        inheritedTime: TimeSignature?,
        inheritedKey: KeySignature?,
        inheritedClef: Clef?,
        inheritedTempo: TempoMark?
    ): Measure {
        var localDivisions = divisions
        var timeSignature: TimeSignature? = null
        var keySignature: KeySignature? = null
        var clef: Clef? = null
        var tempo: TempoMark? = null
        val elements = mutableListOf<MusicElement>()
        val directions = mutableListOf<Direction>()
        var barlineLeft = Barline.REGULAR
        var barlineRight = Barline.REGULAR
        var repeatInfo: RepeatInfo? = null
        var currentTick = 0
        var activePizzicato = false
        val openSlurs = mutableMapOf<Int, String>()

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "measure")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "divisions" -> localDivisions = parser.nextText().toIntOrNull() ?: 1

                    "time" -> {
                        timeSignature = parseTimeSignature(parser)
                    }

                    "key" -> {
                        keySignature = parseKeySignature(parser)
                    }

                    "clef" -> {
                        clef = parseClef(parser)
                    }

                    "direction" -> {
                        val (dir, newTempo) = parseDirection(parser, number, currentTick)
                        dir?.let { directions.add(it) }
                        newTempo?.let { tempo = it }
                    }

                    "note" -> {
                        val (elem, tickAdvance, slurActions) = parseNote(
                            parser, localDivisions, currentTick,
                            activePizzicato, openSlurs
                        )
                        elem?.let { elements.add(it) }
                        if (elem is Note && elem.technicalMarks.contains(TechnicalMark.PIZZICATO)) activePizzicato = true
                        if (elem is Note && elem.technicalMarks.contains(TechnicalMark.ARCO)) activePizzicato = false
                        currentTick += tickAdvance
                    }

                    "backup" -> {
                        val dur = parseTaggedInt(parser, "duration")
                        val ticks = durationToTicks(dur, localDivisions)
                        currentTick -= ticks
                        if (currentTick < 0) currentTick = 0
                    }

                    "forward" -> {
                        val dur = parseTaggedInt(parser, "duration")
                        val ticks = durationToTicks(dur, localDivisions)
                        val rest = Rest(
                            id = UUID.randomUUID().toString(),
                            startTick = currentTick,
                            duration = ticksToDuration(ticks),
                            isFullMeasure = false
                        )
                        elements.add(rest)
                        currentTick += ticks
                    }

                    "barline" -> {
                        val location = parser.getAttributeValue(null, "location") ?: "right"
                        val barlineInfo = parseBarline(parser)
                        if (location == "left") barlineLeft = barlineInfo.first
                        else barlineRight = barlineInfo.first
                        barlineInfo.second?.let { repeatInfo = it }
                    }
                }
            }
            parser.next()
        }

        return Measure(
            number = number,
            timeSignature = timeSignature,
            keySignature = keySignature,
            clef = clef,
            tempo = tempo,
            elements = elements,
            directions = directions,
            barlineLeft = barlineLeft,
            barlineRight = barlineRight,
            repeatInfo = repeatInfo
        )
    }

    private fun parseNote(
        parser: XmlPullParser,
        divisions: Int,
        currentTick: Int,
        activePizzicato: Boolean,
        openSlurs: MutableMap<Int, String>
    ): Triple<MusicElement?, Int, List<Pair<String, String>>> {
        var isChord = false
        var isRest = false
        var isGrace = false
        var pitch: Pitch? = null
        var durationXml = 0
        var type = DurationType.QUARTER
        var dots = 0
        var tie = TieType.NONE
        val articulations = mutableListOf<Articulation>()
        var bowingMark: BowingMark? = null
        var fingering: Fingering? = null
        var ornament: Ornament? = null
        var dynamicLevel: DynamicLevel? = null
        var beamGroup: Int? = null
        var stem = StemDirection.AUTO
        val technicalMarks = mutableListOf<TechnicalMark>()
        val graceNotes = mutableListOf<GraceNote>()
        var staff = 1
        var voice = 1
        val slurIds = mutableListOf<String>()
        var actualNotes = 1
        var normalNotes = 1
        var normalType: DurationType? = null
        var dotCount = 0
        val slurActions = mutableListOf<Pair<String, String>>()

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "note")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "chord" -> isChord = true
                    "rest" -> isRest = true
                    "grace" -> isGrace = true
                    "pitch" -> pitch = parsePitch(parser)
                    "duration" -> durationXml = parser.nextText().toIntOrNull() ?: 1
                    "type" -> type = parseDurationType(parser.nextText())
                    "dot" -> dotCount++
                    "stem" -> stem = if (parser.nextText() == "up") StemDirection.UP else StemDirection.DOWN
                    "staff" -> staff = parser.nextText().toIntOrNull() ?: 1
                    "voice" -> voice = parser.nextText().toIntOrNull() ?: 1
                    "beam" -> {
                        val num = parser.getAttributeValue(null, "number")?.toIntOrNull() ?: 1
                        beamGroup = if (num == 1) currentTick else beamGroup
                        parser.nextText()
                    }
                    "tie" -> {
                        val tieType = parser.getAttributeValue(null, "type")
                        tie = when {
                            tieType == "start" && tie == TieType.STOP -> TieType.CONTINUE
                            tieType == "start" -> TieType.START
                            tieType == "stop" && tie == TieType.START -> TieType.CONTINUE
                            else -> TieType.STOP
                        }
                    }
                    "notations" -> parseNotations(
                        parser, articulations, openSlurs, slurIds,
                        slurActions
                    ).also { results ->
                        bowingMark = results.bowingMark ?: bowingMark
                        ornament = results.ornament ?: ornament
                        technicalMarks.addAll(results.technicalMarks)
                    }
                    "fingering" -> {
                        val f = parser.nextText().toIntOrNull() ?: 0
                        fingering = Fingering(f)
                    }
                    "time-modification" -> {
                        // tuplet handling
                        parseTimeModification(parser).let { (a, n, nt) ->
                            actualNotes = a
                            normalNotes = n
                            normalType = nt
                        }
                    }
                    "dynamics" -> dynamicLevel = parseDynamicFromNotations(parser)
                    "lyric" -> skipElement(parser, "lyric")
                }
            }
            parser.next()
        }

        val noteDuration = NoteDuration(type, actualNotes, normalNotes, normalType)
        val ticks = durationToTicks(durationXml, divisions)
        val tickAdvance = if (isChord) 0 else ticks

        if (isRest) {
            val rest = Rest(
                id = UUID.randomUUID().toString(),
                startTick = currentTick,
                duration = noteDuration,
                dotCount = dotCount,
                staff = staff,
                voice = voice
            )
            return Triple(rest, tickAdvance, slurActions)
        }

        pitch ?: return Triple(null, tickAdvance, slurActions)

        // Apply pizzicato state from active technique
        if (activePizzicato && !technicalMarks.contains(TechnicalMark.ARCO)) {
            if (!technicalMarks.contains(TechnicalMark.PIZZICATO)) {
                technicalMarks.add(TechnicalMark.PIZZICATO)
            }
        }

        val note = Note(
            id = UUID.randomUUID().toString(),
            startTick = currentTick,
            duration = noteDuration,
            dotCount = dotCount,
            pitch = pitch,
            tie = tie,
            articulations = articulations,
            bowingMark = bowingMark,
            fingering = fingering,
            ornament = ornament,
            dynamicLevel = dynamicLevel,
            beamGroup = beamGroup,
            slurIds = slurIds,
            technicalMarks = technicalMarks,
            stemDirection = stem,
            staff = staff,
            voice = voice
        )

        return Triple(note, tickAdvance, slurActions)
    }

    private fun parsePitch(parser: XmlPullParser): Pitch {
        var step = PitchStep.C
        var octave = 4
        var alter = Alter.NATURAL

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "pitch")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "step" -> step = PitchStep.valueOf(parser.nextText())
                    "octave" -> octave = parser.nextText().toIntOrNull() ?: 4
                    "alter" -> {
                        val v = parser.nextText().toFloatOrNull() ?: 0f
                        alter = when {
                            v <= -2f -> Alter.DOUBLE_FLAT
                            v <= -1f -> Alter.FLAT
                            v >= 2f -> Alter.DOUBLE_SHARP
                            v >= 1f -> Alter.SHARP
                            else -> Alter.NATURAL
                        }
                    }
                }
            }
            parser.next()
        }
        return Pitch(step, octave, alter)
    }

    private fun parseTimeSignature(parser: XmlPullParser): TimeSignature {
        var num = 4; var den = 4
        var isCommon = parser.getAttributeValue(null, "symbol") == "common"
        var isCut = parser.getAttributeValue(null, "symbol") == "cut"

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "time")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "beats" -> num = parser.nextText().toIntOrNull() ?: 4
                    "beat-type" -> den = parser.nextText().toIntOrNull() ?: 4
                }
            }
            parser.next()
        }
        return TimeSignature(num, den, isCut, isCommon)
    }

    private fun parseKeySignature(parser: XmlPullParser): KeySignature {
        var fifths = 0; var mode = KeyMode.MAJOR
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "key")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "fifths" -> fifths = parser.nextText().toIntOrNull() ?: 0
                    "mode" -> mode = if (parser.nextText() == "minor") KeyMode.MINOR else KeyMode.MAJOR
                }
            }
            parser.next()
        }
        return KeySignature(fifths, mode)
    }

    private fun parseClef(parser: XmlPullParser): Clef {
        var sign = "F"; var line: Int? = null; var octave = 0
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "clef")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "sign" -> sign = parser.nextText()
                    "line" -> line = parser.nextText().toIntOrNull()
                    "clef-octave-change" -> octave = parser.nextText().toIntOrNull() ?: 0
                }
            }
            parser.next()
        }
        val type = when (sign) {
            "G" -> ClefType.TREBLE
            "C" -> if (line == 4) ClefType.TENOR else ClefType.ALTO
            "F" -> ClefType.BASS
            "percussion" -> ClefType.PERCUSSION
            else -> ClefType.BASS
        }
        return Clef(type, line, octave)
    }

    private data class NotationResult(
        val bowingMark: BowingMark?,
        val ornament: Ornament?,
        val technicalMarks: List<TechnicalMark>
    )

    private fun parseNotations(
        parser: XmlPullParser,
        articulations: MutableList<Articulation>,
        openSlurs: MutableMap<Int, String>,
        slurIds: MutableList<String>,
        slurActions: MutableList<Pair<String, String>>
    ): NotationResult {
        var bowingMark: BowingMark? = null
        var ornament: Ornament? = null
        val techMarks = mutableListOf<TechnicalMark>()

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "notations")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "slur" -> {
                        val type = parser.getAttributeValue(null, "type")
                        val num = parser.getAttributeValue(null, "number")?.toIntOrNull() ?: 1
                        val slurId = UUID.randomUUID().toString()
                        if (type == "start") {
                            openSlurs[num] = slurId
                            slurIds.add(slurId)
                        } else if (type == "stop") {
                            openSlurs[num]?.let { slurIds.add(it) }
                            openSlurs.remove(num)
                        }
                    }
                    "articulations" -> parseArticulations(parser, articulations)
                    "technical" -> {
                        val techResult = parseTechnical(parser)
                        bowingMark = techResult.first ?: bowingMark
                        techMarks.addAll(techResult.second)
                    }
                    "ornaments" -> ornament = parseOrnamentElement(parser)
                    "fermata" -> articulations.add(Articulation.FERMATA)
                    "breath-mark" -> articulations.add(Articulation.BREATH_MARK)
                    "caesura" -> articulations.add(Articulation.CAESURA)
                    "tied" -> {} // handled in note via <tie> element
                }
            }
            parser.next()
        }
        return NotationResult(bowingMark, ornament, techMarks)
    }

    private fun parseArticulations(parser: XmlPullParser, list: MutableList<Articulation>) {
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "articulations")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "staccato" -> list.add(Articulation.STACCATO)
                    "staccatissimo" -> list.add(Articulation.STACCATISSIMO)
                    "tenuto" -> list.add(Articulation.TENUTO)
                    "accent" -> list.add(Articulation.ACCENT)
                    "strong-accent" -> list.add(Articulation.STRONG_ACCENT)
                    "detached-legato" -> list.add(Articulation.PORTATO)
                    "tremolo" -> {
                        val marks = parser.nextText().toIntOrNull() ?: 1
                        list.add(when (marks) {
                            1 -> Articulation.TREMOLO_1
                            2 -> Articulation.TREMOLO_2
                            else -> Articulation.TREMOLO_3
                        })
                    }
                }
            }
            parser.next()
        }
    }

    private fun parseTechnical(parser: XmlPullParser): Pair<BowingMark?, List<TechnicalMark>> {
        var bowing: BowingMark? = null
        val marks = mutableListOf<TechnicalMark>()

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "technical")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "up-bow" -> bowing = BowingMark.UP_BOW
                    "down-bow" -> bowing = BowingMark.DOWN_BOW
                    "harmonic" -> marks.add(TechnicalMark.NATURAL_HARMONIC)
                    "thumb-position" -> marks.add(TechnicalMark.THUMB_POSITION)
                    "snap-pizzicato" -> marks.add(TechnicalMark.SNAP_PIZZICATO)
                    "string" -> {
                        when (parser.nextText()) {
                            "1" -> marks.add(TechnicalMark.STRING_I)
                            "2" -> marks.add(TechnicalMark.STRING_II)
                            "3" -> marks.add(TechnicalMark.STRING_III)
                            "4" -> marks.add(TechnicalMark.STRING_IV)
                        }
                    }
                    "fingering" -> parser.nextText() // consumed by parent note parser
                }
            }
            parser.next()
        }
        return Pair(bowing, marks)
    }

    private fun parseOrnamentElement(parser: XmlPullParser): Ornament? {
        var result: Ornament? = null
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "ornaments")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "trill-mark" -> result = Ornament.Trill()
                    "turn" -> result = Ornament.Turn(false)
                    "inverted-turn" -> result = Ornament.Turn(true)
                    "mordent" -> result = Ornament.Mordent(false)
                    "inverted-mordent" -> result = Ornament.Mordent(true)
                    "tremolo" -> {
                        val marks = parser.nextText().toIntOrNull() ?: 1
                    }
                }
            }
            parser.next()
        }
        return result
    }

    private fun parseDirection(parser: XmlPullParser, measure: Int, tick: Int): Pair<Direction?, TempoMark?> {
        val placement = parser.getAttributeValue(null, "placement") ?: "below"
        var dynamicLevel: DynamicLevel? = null
        var hairpinType: HairpinType? = null
        var words: String? = null
        var rehearsal: String? = null
        var tempo: TempoMark? = null
        var bpm: Int? = null
        var beatUnit = DurationType.QUARTER

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "direction")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "dynamics" -> dynamicLevel = parseDynamicFromNotations(parser)
                    "wedge" -> {
                        hairpinType = when (parser.getAttributeValue(null, "type")) {
                            "crescendo" -> HairpinType.CRESCENDO
                            "diminuendo", "decrescendo" -> HairpinType.DECRESCENDO
                            else -> null
                        }
                    }
                    "words" -> words = parser.nextText()
                    "rehearsal" -> rehearsal = parser.nextText()
                    "metronome" -> {
                        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "metronome")) {
                            if (parser.eventType == XmlPullParser.START_TAG) {
                                when (parser.name) {
                                    "beat-unit" -> beatUnit = parseDurationType(parser.nextText())
                                    "per-minute" -> bpm = parser.nextText().toIntOrNull()
                                }
                            }
                            parser.next()
                        }
                        bpm?.let { tempo = TempoMark(it, beatUnit, words, measure, tick) }
                    }
                    "sound" -> {
                        parser.getAttributeValue(null, "tempo")?.toFloatOrNull()?.let {
                            bpm = it.toInt()
                            tempo = TempoMark(bpm!!, DurationType.QUARTER, null, measure, tick)
                        }
                    }
                }
            }
            parser.next()
        }

        // Interpret text directions for cello techniques
        words?.let { w ->
            val lower = w.lowercase()
            val techMark = when {
                lower.contains("pizz") -> TechnicalMark.PIZZICATO
                lower.contains("arco") -> TechnicalMark.ARCO
                lower.contains("col legno") -> TechnicalMark.COL_LEGNO_TRATTO
                lower.contains("sul pont") -> TechnicalMark.SUL_PONTICELLO
                lower.contains("sul tasto") -> TechnicalMark.SUL_TASTO
                lower.contains("flaut") -> TechnicalMark.FLAUTANDO
                else -> null
            }
        }

        val dir = Direction(
            measure = measure,
            tick = tick,
            type = when {
                dynamicLevel != null -> DirectionType.DYNAMIC
                hairpinType != null -> DirectionType.HAIRPIN
                rehearsal != null -> DirectionType.REHEARSAL_MARK
                tempo != null -> DirectionType.TEMPO
                words != null -> DirectionType.WORDS
                else -> DirectionType.TEXT
            },
            text = words ?: rehearsal,
            dynamicLevel = dynamicLevel,
            hairpinType = hairpinType,
            rehearsalMark = rehearsal,
            tempoMark = tempo,
            placement = if (placement == "above") Placement.ABOVE else Placement.BELOW
        )

        return Pair(dir, tempo)
    }

    private fun parseDynamicFromNotations(parser: XmlPullParser): DynamicLevel? {
        var result: DynamicLevel? = null
        val endTag = parser.name
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == endTag)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                result = when (parser.name) {
                    "pppp" -> DynamicLevel.PPPP
                    "ppp" -> DynamicLevel.PPP
                    "pp" -> DynamicLevel.PP
                    "p" -> DynamicLevel.P
                    "mp" -> DynamicLevel.MP
                    "mf" -> DynamicLevel.MF
                    "f" -> DynamicLevel.F
                    "ff" -> DynamicLevel.FF
                    "fff" -> DynamicLevel.FFF
                    "ffff" -> DynamicLevel.FFFF
                    "sf" -> DynamicLevel.SF
                    "sfz" -> DynamicLevel.SFZ
                    "rfz" -> DynamicLevel.RFZ
                    "fp" -> DynamicLevel.FP
                    else -> result
                }
            }
            parser.next()
        }
        return result
    }

    private fun parseBarline(parser: XmlPullParser): Pair<Barline, RepeatInfo?> {
        var style = Barline.REGULAR
        var repeatInfo: RepeatInfo? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "barline")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "bar-style" -> {
                        style = when (parser.nextText()) {
                            "double" -> Barline.DOUBLE
                            "final" -> Barline.FINAL
                            "light-heavy" -> Barline.FINAL
                            "heavy-light" -> Barline.REPEAT_START
                            "light-light" -> Barline.DOUBLE
                            else -> Barline.REGULAR
                        }
                    }
                    "repeat" -> {
                        val dir = parser.getAttributeValue(null, "direction")
                        repeatInfo = RepeatInfo(
                            type = if (dir == "forward") RepeatType.START else RepeatType.END
                        )
                        style = if (dir == "forward") Barline.REPEAT_START else Barline.REPEAT_END
                    }
                    "ending" -> {
                        val num = parser.getAttributeValue(null, "number")?.toIntOrNull() ?: 1
                        val type = parser.getAttributeValue(null, "type")
                        repeatInfo = RepeatInfo(
                            type = if (type == "start") RepeatType.VOLTA_START else RepeatType.VOLTA_END,
                            voltaNumber = num
                        )
                    }
                }
            }
            parser.next()
        }
        return Pair(style, repeatInfo)
    }

    private fun parseTimeModification(parser: XmlPullParser): Triple<Int, Int, DurationType?> {
        var actual = 1; var normal = 1; var normalType: DurationType? = null
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "time-modification")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "actual-notes" -> actual = parser.nextText().toIntOrNull() ?: 1
                    "normal-notes" -> normal = parser.nextText().toIntOrNull() ?: 1
                    "normal-type" -> normalType = parseDurationType(parser.nextText())
                }
            }
            parser.next()
        }
        return Triple(actual, normal, normalType)
    }

    private fun parseDurationType(text: String): DurationType = when (text.trim().lowercase()) {
        "maxima" -> DurationType.MAXIMA
        "long" -> DurationType.LONG
        "breve" -> DurationType.BREVE
        "whole" -> DurationType.WHOLE
        "half" -> DurationType.HALF
        "quarter" -> DurationType.QUARTER
        "eighth" -> DurationType.EIGHTH
        "16th" -> DurationType.SIXTEENTH
        "32nd" -> DurationType.THIRTY_SECOND
        "64th" -> DurationType.SIXTY_FOURTH
        "128th" -> DurationType.HUNDRED_TWENTY_EIGHTH
        else -> DurationType.QUARTER
    }

    private fun durationToTicks(xmlDuration: Int, divisions: Int): Int {
        if (divisions <= 0) return 480
        return (xmlDuration * 480) / divisions
    }

    private fun ticksToDuration(ticks: Int): NoteDuration {
        return when {
            ticks >= 480 * 4 -> NoteDuration(DurationType.WHOLE)
            ticks >= 480 * 2 -> NoteDuration(DurationType.HALF)
            ticks >= 480 -> NoteDuration(DurationType.QUARTER)
            ticks >= 240 -> NoteDuration(DurationType.EIGHTH)
            ticks >= 120 -> NoteDuration(DurationType.SIXTEENTH)
            else -> NoteDuration(DurationType.THIRTY_SECOND)
        }
    }

    private fun parseTaggedInt(parser: XmlPullParser, tag: String): Int {
        var result = 0
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == tag)) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == tag) {
                result = parser.nextText().toIntOrNull() ?: 0
            }
            parser.next()
        }
        return result
    }

    private fun skipElement(parser: XmlPullParser, tag: String) {
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == tag)) {
            parser.next()
        }
    }
}

private class PartBuilder(val id: String) {
    var name: String = "Cello"
    var abbreviation: String = "Vc."
    val measures = mutableListOf<Measure>()

    fun build() = Part(id, name, abbreviation, 42, 0, measures)
}
