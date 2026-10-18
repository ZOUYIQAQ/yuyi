package io.github.korenkrita.simulsub

/** 为单次输入受限的识别导出寻找低能量边界，完整覆盖原样本而不重复或遗漏。 */
fun recognitionAudioChunks(samples: FloatArray, maxSamples: Int = 8 * 16_000): List<FloatArray> {
    require(maxSamples >= 1600)
    if (samples.size <= maxSamples) return listOf(samples)
    val result = mutableListOf<FloatArray>()
    var begin = 0
    while (samples.size - begin > maxSamples) {
        val end = begin + maxSamples
        val scanStart = begin + maxSamples * 3 / 4
        val window = 320
        var best = end
        var bestEnergy = Double.POSITIVE_INFINITY
        var candidate = scanStart
        while (candidate + window <= end) {
            var energy = 0.0
            for (index in candidate until candidate + window) energy += samples[index].toDouble() * samples[index]
            if (energy < bestEnergy) { bestEnergy = energy; best = candidate + window / 2 }
            candidate += window
        }
        result += samples.copyOfRange(begin, best)
        begin = best
    }
    if (begin < samples.size) result += samples.copyOfRange(begin, samples.size)
    return result
}
