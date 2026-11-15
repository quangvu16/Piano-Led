package com.ledpiano.app.midi

/**
 * Classifies notes into left/right hand.
 *
 * Strategy (matching the original app's behaviour):
 *  - Two sounding tracks: the track with the lower average pitch plays the left hand
 *    (bass clef), the higher one the right hand (treble clef).
 *  - Any other case: split by pitch at [splitPitch] (default middle C); the
 *    learner can move the split point in the practice options.
 */
object HandAssigner {

    fun assign(song: MidiSong, splitPitch: Int = MidiSong.MIDDLE_C) {
        val notesByTrack = song.notes.groupBy { it.track }
        if (notesByTrack.size == 2) {
            val tracks = notesByTrack.keys.sorted()
            val avgPitch = tracks.associateWith { t ->
                notesByTrack.getValue(t).map { it.pitch }.average()
            }
            val lowerTrack = tracks.minBy { avgPitch.getValue(it) }
            val higherTrack = tracks.first { it != lowerTrack }
            for (n in notesByTrack.getValue(lowerTrack)) n.hand = Hand.LEFT
            for (n in notesByTrack.getValue(higherTrack)) n.hand = Hand.RIGHT
        } else {
            for (n in song.notes) {
                n.hand = if (n.pitch < splitPitch) Hand.LEFT else Hand.RIGHT
            }
        }
    }

    /** True when hands come from the pitch split (so moving the split point changes them). */
    fun usesPitchSplit(song: MidiSong): Boolean = song.notes.map { it.track }.distinct().size != 2
}
