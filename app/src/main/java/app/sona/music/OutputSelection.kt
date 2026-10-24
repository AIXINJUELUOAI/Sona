package app.sona.music

/** A requested output is never treated as an observed audio route. */
internal data class OutputSelection(val actualId: Int? = null, val targetId: Int? = null) {
    // A default may select the card without claiming that audio was observed there.
    fun displayId(defaultId: Int?): Int? = targetId ?: actualId ?: defaultId
    fun request(id: Int) = copy(targetId = id)
    fun observe(id: Int) = copy(actualId = id, targetId = targetId.takeUnless { it == id })
    fun cancel() = copy(targetId = null)
    fun retain(ids: Set<Int>) = OutputSelection(actualId?.takeIf { it in ids }, targetId?.takeIf { it in ids })
    fun canAdjustVolume(id: Int, systemVolumeId: Int?): Boolean =
        actualId == id && targetId == null && systemVolumeId == id
}

internal enum class OutputKind { Phone, Bluetooth, Headphones, Speaker }
internal data class OutputDevice(val id: Int, val name: String, val kind: OutputKind)
