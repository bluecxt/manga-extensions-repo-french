package eu.kanade.tachiyomi.extension.fr.japscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlin.math.abs

fun decodeBase64ToImage(b64: String): Bitmap {
    val cleanB64 = if (b64.contains(",")) b64.substringAfter(",") else b64
    val bytes = Base64.decode(cleanB64.replace("\\", ""), Base64.DEFAULT)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

// Detect thickness of random rainbow noise border (typically 3-7px)
fun detectNoiseBorder(bitmap: Bitmap): Pair<Int, Int> {
    val h = bitmap.height
    val w = bitmap.width

    var topNoise = 0
    for (r in 0 until minOf(10, h - 1)) {
        var diffSum: Long = 0
        for (x in 0 until w) {
            val p1 = bitmap.getPixel(x, r)
            val p2 = bitmap.getPixel(x, r + 1)
            diffSum += abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF))
            diffSum += abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF))
            diffSum += abs((p1 and 0xFF) - (p2 and 0xFF))
        }
        val avgDiff = diffSum.toDouble() / (w * 3)
        if (avgDiff > 45.0) {
            topNoise = r + 1
        } else {
            break
        }
    }

    var botNoise = 0
    for (r in 0 until minOf(10, h - 1)) {
        var diffSum: Long = 0
        for (x in 0 until w) {
            val p1 = bitmap.getPixel(x, h - 1 - r)
            val p2 = bitmap.getPixel(x, h - 2 - r)
            diffSum += abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF))
            diffSum += abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF))
            diffSum += abs((p1 and 0xFF) - (p2 and 0xFF))
        }
        val avgDiff = diffSum.toDouble() / (w * 3)
        if (avgDiff > 45.0) {
            botNoise = r + 1
        } else {
            break
        }
    }

    return Pair(topNoise.coerceIn(4, 7), botNoise.coerceIn(4, 7))
}

fun edgeDifference(top: Bitmap, bottom: Bitmap): Long {
    val (_, b1) = detectNoiseBorder(top)
    val (t2, _) = detectNoiseBorder(bottom)

    val topY = top.height - 1 - b1
    val bottomY = t2
    val w = minOf(top.width, bottom.width)

    var colorDiff: Long = 0
    val topGrad = LongArray(w - 1)
    val botGrad = LongArray(w - 1)

    var prevTopR = 0
    var prevTopG = 0
    var prevTopB = 0
    var prevBotR = 0
    var prevBotG = 0
    var prevBotB = 0

    for (x in 0 until w) {
        val pxTop = top.getPixel(x, topY)
        val pxBottom = bottom.getPixel(x, bottomY)

        val r1 = (pxTop shr 16) and 0xFF
        val g1 = (pxTop shr 8) and 0xFF
        val b1Val = pxTop and 0xFF

        val r2 = (pxBottom shr 16) and 0xFF
        val g2 = (pxBottom shr 8) and 0xFF
        val b2Val = pxBottom and 0xFF

        colorDiff += abs(r1 - r2) + abs(g1 - g2) + abs(b1Val - b2Val)

        if (x > 0) {
            val gTop = abs(r1 - prevTopR) + abs(g1 - prevTopG) + abs(b1Val - prevTopB)
            val gBot = abs(r2 - prevBotR) + abs(g2 - prevBotG) + abs(b2Val - prevBotB)
            topGrad[x - 1] = gTop.toLong()
            botGrad[x - 1] = gBot.toLong()
        }

        prevTopR = r1
        prevTopG = g1
        prevTopB = b1Val
        prevBotR = r2
        prevBotG = g2
        prevBotB = b2Val
    }

    var gradDiff: Long = 0
    for (x in 0 until w - 1) {
        gradDiff += abs(topGrad[x] - botGrad[x])
    }

    return colorDiff + gradDiff
}

fun computeCostMatrix(segments: List<Bitmap>): Array<LongArray> {
    val n = segments.size
    val inf = Long.MAX_VALUE / 4
    val cost = Array(n) { LongArray(n) { inf } }
    for (i in 0 until n) {
        for (j in 0 until n) {
            if (i == j) continue
            cost[i][j] = edgeDifference(segments[i], segments[j])
        }
    }
    return cost
}

fun generatePermutations(n: Int): List<List<Int>> {
    val results = mutableListOf<List<Int>>()
    val arr = IntArray(n) { it }
    fun swap(i: Int, j: Int) {
        val t = arr[i]
        arr[i] = arr[j]
        arr[j] = t
    }
    fun backtrack(k: Int) {
        if (k == n) {
            results.add(arr.toList())
            return
        }
        for (i in k until n) {
            swap(k, i)
            backtrack(k + 1)
            swap(k, i)
        }
    }
    backtrack(0)
    return results
}

fun findBestVerticalOrder(cost: Array<LongArray>, heights: List<Int>): List<Int> {
    val n = cost.size
    require(n == 4) { "Exactly four segments were expected." }
    val allPerms = generatePermutations(n)

    // Japscan splits 250px into 62, 62, 62 and 64 px (the 64px slice is placed at the bottom in 94% of cases)
    val maxH = heights.maxOrNull() ?: 0
    val maxHIdx = heights.indexOf(maxH)
    val candidatePerms = if (heights.count { it == maxH } == 1) {
        allPerms.filter { it.last() == maxHIdx }
    } else {
        allPerms
    }

    var bestScore = Long.MAX_VALUE
    var bestPerm = candidatePerms.firstOrNull() ?: allPerms.first()
    for (p in candidatePerms) {
        var score: Long = 0
        for (k in 0 until n - 1) {
            score += cost[p[k]][p[k + 1]]
            if (score >= bestScore) break
        }
        if (score < bestScore) {
            bestScore = score
            bestPerm = p
        }
    }
    return bestPerm
}

fun orderUuidsByImageVerticality(items: List<Pair<String, String>>): List<String> {
    require(items.size == 4) { "Exactly 4 Base64 segments are required." }
    val bitmaps = items.map { (_, src) -> decodeBase64ToImage(src) }
    val heights = bitmaps.map { it.height }
    val cost = computeCostMatrix(bitmaps)
    val orderIndex = findBestVerticalOrder(cost, heights)
    return orderIndex.map { items[it].first }
}
