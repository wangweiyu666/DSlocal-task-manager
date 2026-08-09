package com.ds.localtaskmanager.sharing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.ds.localtaskmanager.ui.result.ResultPresentation
import com.ds.localtaskmanager.ui.result.formatChineseDate
import com.ds.localtaskmanager.ui.result.formatPoints
import com.ds.localtaskmanager.settings.UiPalette

data class GeneratedShareImage(
    val bitmap: Bitmap,
    val fileName: String,
)

class ShareImageTooLargeException : IllegalStateException("内容过多，无法生成图片，请复制结果")

class ShareImageRenderer {
    fun renderResult(
        result: ResultPresentation,
        palette: UiPalette = UiPalette.INDIGO,
    ): GeneratedShareImage {
        val blocks = buildList {
            add(Block("今日结果", listOfNotNull(result.domName?.let { "来自 $it" }, result.dateLabel)))
            add(Block(result.status, listOf("本日总积分 ${formatPoints(result.totalPoints)}"), accent = statusColor(result.status, palette)))
            result.groups.forEach { group ->
                add(
                    Block(
                        title = group.name,
                        lines = buildList {
                            add("${group.status} · 净积分 ${formatPoints(group.points)}")
                            group.tasks.forEach { task ->
                                add("${task.name}｜${task.requirement} · ${task.status} · ${formatPoints(task.points)}")
                            }
                            group.message?.let(::add)
                        },
                        accent = statusColor(group.status, palette),
                    ),
                )
            }
        }
        return GeneratedShareImage(render(blocks, palette), "今日结果-${result.taskDate}.png")
    }

    fun renderInformation(
        taskName: String,
        taskDate: String,
        domName: String?,
        body: String,
        palette: UiPalette = UiPalette.INDIGO,
    ): GeneratedShareImage {
        val blocks = listOf(
            Block("信息告知", listOfNotNull(domName?.takeIf(String::isNotBlank)?.let { "来自 $it" }, formatChineseDate(taskDate))),
            Block(taskName, listOf(body), accent = palette.sharePrimaryColor),
        )
        return GeneratedShareImage(render(blocks, palette), "告知-${sanitizeFileName(taskName)}-$taskDate.png")
    }

    private fun render(blocks: List<Block>, palette: UiPalette): Bitmap {
        val measured = blocks.map(::measureBlock)
        val height = TOP_BOTTOM_PADDING * 2 + measured.sumOf { it.height } + BLOCK_GAP * (measured.size - 1).coerceAtLeast(0)
        if (height > MAX_HEIGHT || WIDTH.toLong() * height * 4 > MAX_BYTES) throw ShareImageTooLargeException()
        val bitmap = Bitmap.createBitmap(WIDTH, height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        var y = TOP_BOTTOM_PADDING.toFloat()
        measured.forEachIndexed { index, block ->
            drawBlock(canvas, block, y, palette.sharePrimaryColor)
            y += block.height
            if (index != measured.lastIndex) y += BLOCK_GAP
        }
        return bitmap
    }

    private fun measureBlock(block: Block): MeasuredBlock {
        val titleLines = wrap(block.title, titlePaint)
        val bodyLines = block.lines.flatMap { line ->
            line.split('\n').flatMap { wrap(it.ifEmpty { " " }, bodyPaint) }
        }
        val height = CARD_PADDING * 2 + titleLines.size * TITLE_LINE_HEIGHT +
            (if (bodyLines.isEmpty()) 0 else TITLE_BODY_GAP + bodyLines.size * BODY_LINE_HEIGHT)
        return MeasuredBlock(block, titleLines, bodyLines, height)
    }

    private fun drawBlock(canvas: Canvas, measured: MeasuredBlock, top: Float, primaryColor: Int) {
        val rect = RectF(MARGIN.toFloat(), top, (WIDTH - MARGIN).toFloat(), top + measured.height)
        cardPaint.color = CARD
        canvas.drawRoundRect(rect, 28f, 28f, cardPaint)
        accentPaint.color = measured.block.accent ?: primaryColor
        canvas.drawRoundRect(RectF(rect.left, rect.top, rect.left + 12, rect.bottom), 12f, 12f, accentPaint)
        var y = top + CARD_PADDING - titlePaint.ascent()
        measured.titleLines.forEach { line ->
            canvas.drawText(line, (MARGIN + CARD_PADDING).toFloat(), y, titlePaint)
            y += TITLE_LINE_HEIGHT
        }
        if (measured.bodyLines.isNotEmpty()) y += TITLE_BODY_GAP
        measured.bodyLines.forEach { line ->
            canvas.drawText(line, (MARGIN + CARD_PADDING).toFloat(), y - bodyPaint.ascent(), bodyPaint)
            y += BODY_LINE_HEIGHT
        }
    }

    private fun wrap(text: String, paint: Paint): List<String> {
        val width = (WIDTH - MARGIN * 2 - CARD_PADDING * 2).toFloat()
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            val count = paint.breakText(remaining, true, width, null).coerceAtLeast(1)
            result += remaining.take(count)
            remaining = remaining.drop(count)
        }
        return result
    }

    private data class Block(val title: String, val lines: List<String>, val accent: Int? = null)
    private data class MeasuredBlock(
        val block: Block,
        val titleLines: List<String>,
        val bodyLines: List<String>,
        val height: Int,
    )

    companion object {
        const val WIDTH = 1080
        const val MAX_HEIGHT = 32_000
        private const val MAX_BYTES = 128L * 1024 * 1024
        private const val MARGIN = 56
        private const val CARD_PADDING = 42
        private const val TOP_BOTTOM_PADDING = 64
        private const val BLOCK_GAP = 28
        private const val TITLE_LINE_HEIGHT = 66
        private const val BODY_LINE_HEIGHT = 52
        private const val TITLE_BODY_GAP = 18
        private const val BACKGROUND = 0xFFF8FAFC.toInt()
        private const val CARD = Color.WHITE

        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E293B.toInt()
            textSize = 48f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF475569.toInt()
            textSize = 34f
            typeface = Typeface.DEFAULT
        }
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun sanitizeFileName(value: String): String = value
            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
            .trim().trim('.')
            .take(60)
            .ifBlank { "任务" }

    }

    private val UiPalette.secondaryColor: Int
        get() = when (this) {
            UiPalette.INDIGO -> 0xFF818CF8.toInt()
            UiPalette.SKY -> 0xFF95BDD7.toInt()
        }

    private fun statusColor(status: String, palette: UiPalette): Int = when (status) {
        "全部完成", "已完成" -> palette.sharePrimaryColor
        "尚未完成", "待完成" -> palette.secondaryColor
        "有任务未完成", "未完成" -> 0xFFB9505A.toInt()
        else -> 0xFF62676F.toInt()
    }
}

internal val UiPalette.sharePrimaryColor: Int
    get() = when (this) {
        UiPalette.INDIGO -> 0xFF818CF8.toInt()
        UiPalette.SKY -> 0xFF78A4CB.toInt()
    }
