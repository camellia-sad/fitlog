package dev.lzk.fitlog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.floor

enum class Symbol { HOME, DUMBBELL, BACK, PLUS, CHECK, SEARCH, ARROW, MINUS, CLOCK, LIST, SETTINGS, FRIENDS, PERSON, PLAY, SPARK, DOWN, CLOSE, PAUSE,
    STAR, STAR_FILLED, ARCHIVE, UNARCHIVE, EDIT, BARBELL, MACHINE, CABLE, SMITH, BODYWEIGHT, IMAGE, PIN, TRASH, UP, TUNE,
    CLOUD, EXPORT, IMPORT, SUN, MOON, VIBRATE, CHAT, MEDAL, SWAP, COPY,
    MORE, SEND, REPLY, FORWARD, SELECT, UNDO, FEED, BELL }

/** Solid 12×12 pixel glyphs drawn to match the puppy's outline weight. `#` marks a filled cell. */
private val Glyphs: Map<Symbol, List<String>> = mapOf(
    Symbol.HOME to listOf("....####....", "...##..##...", "..##....##..", ".##......##.", "##........##", ".#........#.", ".#..####..#.", ".#..#..#..#.", ".#..#..#..#.", ".####..####."),
    Symbol.DUMBBELL to listOf(".##......##.", ".##......##.", "###......###", "############", "###......###", ".##......##.", ".##......##."),
    Symbol.FRIENDS to listOf("..##....##..", ".#..#..#..#.", ".#..#..#..#.", "..##....##..", "............", ".####..####.", "#....##....#", "#....##....#", "############"),
    Symbol.PERSON to listOf("....####....", "...#....#...", "...#....#...", "....####....", "............", "..########..", ".#........#.", "#..........#", "############"),
    Symbol.CLOCK to listOf("...######...", "..#......#..", ".#...#....#.", "#....#.....#", "#....#.....#", "#....####..#", "#..........#", ".#........#.", "..#......#..", "...######..."),
    Symbol.PLUS to listOf("....##....", "....##....", "....##....", "##########", "##########", "....##....", "....##....", "....##...."),
    Symbol.MINUS to listOf("##########", "##########"),
    Symbol.CHECK to listOf("..........#", ".........##", "........##.", "#......##..", "##....##...", ".##..##....", "..####.....", "...##......"),
    Symbol.BACK to listOf("...##", "..##.", ".##..", "##...", ".##..", "..##.", "...##"),
    Symbol.ARROW to listOf("##...", ".##..", "..##.", "...##", "..##.", ".##..", "##..."),
    Symbol.DOWN to listOf("##.....##", ".##...##.", "..##.##..", "...###...", "....#...."),
    Symbol.UP to listOf("....#....", "...###...", "..##.##..", ".##...##.", "##.....##"),
    // Template board: a push pin, a bin with ribs, and two sliders for target tuning.
    Symbol.PIN to listOf("...####...", "..######..", ".########.", ".########.", "..######..", "...####...", ".########.", "##########", "....##....", "....##....", "....##....", "....#....."),
    Symbol.TRASH to listOf("....####....", "############", "............", ".##########.", ".#..#..#..#.", ".#..#..#..#.", ".#..#..#..#.", ".#..#..#..#.", ".#..#..#..#.", "..########.."),
    // Settings: cloud backup, export / import trays, sun and moon for themes, a buzzing phone, a chat bubble.
    Symbol.CLOUD to listOf("....####....", "...######...", ".#########..", "###########.", "############", "############", ".##########."),
    Symbol.EXPORT to listOf(".....##.....", "....####....", "...######...", ".....##.....", ".....##.....", ".....##.....", "##........##", "##........##", "############"),
    Symbol.IMPORT to listOf(".....##.....", ".....##.....", ".....##.....", "...######...", "....####....", ".....##.....", "##........##", "##........##", "############"),
    Symbol.SUN to listOf("#....##....#", ".#........#.", "....####....", "...######...", "##.######.##", "...######...", "....####....", ".#........#.", "#....##....#"),
    Symbol.MOON to listOf("...#####", ".####...", "####....", "###.....", "###.....", "####....", ".####...", "...#####"),
    Symbol.VIBRATE to listOf("#..######..#", "#..#....#..#", ".#.#....#.#.", ".#.#....#.#.", "#..#....#..#", "#..#....#..#", "...######..."),
    Symbol.CHAT to listOf("############", "#..........#", "#.##.##.##.#", "#..........#", "############", "..##........", ".#.........."),
    // Record medal: a ribbon over a round medal with a square boss.
    Symbol.MORE to listOf("##..##..##", "##..##..##"),
    Symbol.SEND to listOf("##........", "####......", "######....", "########..", "##########", "########..", "######....", "####......", "##........"),
    Symbol.REPLY to listOf("...#......", "..##......", ".#######..", "##......#.", ".#######.#", "..##.....#", "...#.....#", "........#."),
    Symbol.FORWARD to listOf("......#...", "......##..", "..#######.", ".#......##", "#.#######.", "#.....##..", "#.....#...", ".#........"),
    Symbol.SELECT to listOf("####..####", "#..#..#..#", "#..#..####", "####......", "..........", "####..####", "####..#..#", "####..####"),
    Symbol.UNDO to listOf("..#.......", ".##.......", "#######...", ".##....##.", "..#.....##", "........##", "....######", "....#####."),
    Symbol.FEED to listOf("##########", "#........#", "#.######.#", "#........#", "#.####...#", "#........#", "##########"),
    Symbol.COPY to listOf("...#######", "...#.....#", "#######..#", "#.....#..#", "#.....#..#", "#.....#..#", "#.....####", "#.....#...", "#.....#...", "#######..."),
    Symbol.SWAP to listOf("......#...", "......##..", "#########.", "......##..", "......#...", "...#......", "..##......", ".#########", "..##......", "...#......"),
    Symbol.MEDAL to listOf(".##....##.", "..##..##..", "...####...", "..######..", ".##....##.", ".#..##..#.", ".#..##..#.", ".##....##.", "..######..", "...####..."),
    Symbol.TUNE to listOf(".#.....#.", ".#.....#.", "###....#.", "###....#.", ".#.....#.", ".#....###", ".#....###", ".#.....#.", ".#.....#."),
    Symbol.SEARCH to listOf("..####.....", ".#....#....", "#......#...", "#......#...", "#......#...", ".#....#....", "..####.#...", ".......##..", "........##.", ".........##"),
    Symbol.PLAY to listOf("##.....", "####...", "######.", "#######", "######.", "####...", "##....."),
    Symbol.SPARK to listOf("....#....", "....#....", "...###...", "..#####..", "#########", "..#####..", "...###...", "....#....", "....#...."),
    Symbol.LIST to listOf("##.#######", "..........", "##.#######", "..........", "##.#######", "..........", "##.#######"),
    Symbol.PAUSE to listOf("###..###", "###..###", "###..###", "###..###", "###..###", "###..###", "###..###"),
    Symbol.CLOSE to listOf("##....##", ".##..##.", "..####..", "...##...", "..####..", ".##..##.", "##....##"),
    // Favourite: outline star, and the same star filled once saved.
    Symbol.STAR to listOf(".....##.....", "....#..#....", "####....####", "#..........#", ".#........#.", "..#......#..", "..#......#..", ".#...##...#.", ".#..#..#..#.", ".###....###."),
    Symbol.STAR_FILLED to listOf(".....##.....", "....####....", "############", "############", ".##########.", "..########..", "..########..", ".####..####.", ".###....###.", ".##......##."),
    // Archive: a box with a lid and a handle slot; unarchive adds an up arrow above the slot.
    Symbol.ARCHIVE to listOf("############", "#..........#", "############", ".#........#.", ".#..####..#.", ".#........#.", ".#........#.", ".##########."),
    Symbol.UNARCHIVE to listOf(".....##.....", "....####....", "...######...", ".....##.....", "############", ".#........#.", ".#..####..#.", ".#........#.", ".##########."),
    Symbol.EDIT to listOf("........##..", ".......####.", "......####..", ".....####...", "....####....", "...####.....", "..####......", ".####.......", ".###........", "####........"),
    // Equipment: the bar runs past the plates on a barbell; a weight stack for machines; pulley and handle for cables;
    // rails with a hooked bar for the Smith machine; a small figure for bodyweight.
    Symbol.BARBELL to listOf("..#......#..", ".##......##.", ".##......##.", "############", ".##......##.", ".##......##.", "..#......#.."),
    Symbol.MACHINE to listOf(".########.", ".#......#.", ".#.####.#.", ".#......#.", ".#.####.#.", ".#......#.", ".#.####.#.", ".#......#.", "##########"),
    Symbol.CABLE to listOf("..####..", ".#....#.", ".#.##.#.", ".#....#.", "..####..", "....#...", "....#...", "....#...", "..#####.", "..#...#.", "..#####."),
    Symbol.SMITH to listOf("#..........#", "#..........#", "#.#......#.#", "############", "#.#......#.#", "#..........#", "#..........#", "#..........#", "##........##"),
    Symbol.BODYWEIGHT to listOf("....##....", "....##....", "..........", ".########.", "#...##...#", "#...##...#", "....##....", "...#..#...", "...#..#...", "..#....#..", "..#....#.."),
    Symbol.IMAGE to listOf("############", "#..........#", "#.##.......#", "#.##.......#", "#......#...#", "#.....###..#", "#..#.#####.#", "#.##########", "############"),
    Symbol.SETTINGS to listOf("....##....", ".##.##.##.", ".########.", "..##..##..", "####..####", "####..####", "..##..##..", ".########.", ".##.##.##.", "....##...."),
    // Notifications: a bell with its clapper.
    Symbol.BELL to listOf(".....##.....", "....####....", "...#....#...", "..#......#..", "..#......#..", "..#......#..", ".#........#.", "############", ".....##....."),
)

/** Horizontal runs per glyph, centred in the 12-cell grid: (x, y, width). */
private val Runs: Map<Symbol, List<Triple<Float, Float, Float>>> = Glyphs.mapValues { (_, rows) ->
    val width = rows.maxOf { it.length }
    val ox = (12 - width) / 2f
    val oy = (12 - rows.size) / 2f
    buildList {
        rows.forEachIndexed { y, row ->
            var x = 0
            while (x < row.length) {
                if (row[x] == '#') {
                    val start = x
                    while (x < row.length && row[x] == '#') x++
                    add(Triple(ox + start, oy + y, (x - start).toFloat()))
                } else x++
            }
        }
    }
}

/** Pixel icon. The name is kept from the earlier line icons so every call site picks up the new glyphs. */
@Composable
fun LineIcon(symbol: Symbol, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val runs = Runs.getValue(symbol)
    Canvas(modifier) {
        // Snap cells to whole device pixels so edges stay crisp at every density.
        val cell = floor(size.minDimension / 12f).coerceAtLeast(1f)
        val left = floor((size.width - cell * 12) / 2)
        val top = floor((size.height - cell * 12) / 2)
        runs.forEach { (x, y, w) ->
            drawRect(color, Offset(left + floor(x * cell), top + floor(y * cell)), Size(w * cell, cell))
        }
    }
}
