package com.ds.localtaskmanager.sharing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.ds.localtaskmanager.settings.UiPalette
import com.ds.localtaskmanager.ui.result.ResultGroupPresentation
import com.ds.localtaskmanager.ui.result.ResultPresentation
import com.ds.localtaskmanager.ui.result.ResultTaskPresentation
import com.ds.localtaskmanager.ui.result.formatChineseDate
import com.ds.localtaskmanager.ui.result.formatPoints
import kotlin.math.max

data class GeneratedShareImage(
    val bitmap: Bitmap,
    val fileName: String,
)

enum class ResultShareTaskFilter {
    ALL,
    INCOMPLETE_ONLY,
}

class ShareImageTooLargeException : IllegalStateException("内容过多，无法生成图片，请复制结果")

class ShareImageRenderer {
    @Synchronized
    fun renderResult(
        result: ResultPresentation,
        palette: UiPalette = UiPalette.INDIGO,
        taskFilter: ResultShareTaskFilter = ResultShareTaskFilter.ALL,
    ): GeneratedShareImage {
        val progress = result.progressSummary()
        val groups = result.groups.mapNotNull { group ->
            val tasks = when (taskFilter) {
                ResultShareTaskFilter.ALL -> group.tasks
                ResultShareTaskFilter.INCOMPLETE_ONLY -> group.tasks.filter { it.status != STATUS_COMPLETED }
            }
            group.takeIf { taskFilter == ResultShareTaskFilter.ALL || tasks.isNotEmpty() }?.copy(tasks = tasks)
        }
        val measuredGroups = groups.map(::measureGroup)
        val bodyHeight = if (measuredGroups.isEmpty()) EMPTY_BODY_HEIGHT else {
            BODY_TOP_PADDING + measuredGroups.sumOf { it.height } + BODY_BOTTOM_PADDING
        }
        val height = SHEET_TOP + RESULT_HEADER_HEIGHT + bodyHeight + SHEET_BOTTOM
        checkSize(height)

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAGE_BACKGROUND)
        drawSheet(canvas, height, palette, RESULT_HEADER_HEIGHT)
        drawResultHeader(canvas, result, progress, palette)
        if (measuredGroups.isEmpty()) {
            drawEmptyIncompleteState(canvas, palette)
        } else {
            var y = (SHEET_TOP + RESULT_HEADER_HEIGHT + BODY_TOP_PADDING).toFloat()
            measuredGroups.forEachIndexed { index, group ->
                drawGroup(canvas, group, y, palette)
                y += group.height
                if (index != measuredGroups.lastIndex) {
                    dividerPaint.color = DIVIDER
                    canvas.drawRect(CONTENT_LEFT.toFloat(), y - 1, CONTENT_RIGHT.toFloat(), y + 1, dividerPaint)
                }
            }
        }
        return GeneratedShareImage(bitmap, "今日结果-${result.taskDate}.png")
    }

    @Synchronized
    fun renderInformation(
        taskName: String,
        taskDate: String,
        domName: String?,
        body: String,
        palette: UiPalette = UiPalette.INDIGO,
    ): GeneratedShareImage {
        val titleLines = wrap(taskName, heroTitlePaint, CONTENT_WIDTH.toFloat())
        val metadata = listOfNotNull(
            domName?.takeIf(String::isNotBlank)?.let { "来自 $it" },
            formatChineseDate(taskDate),
        ).joinToString("  ·  ")
        val metadataLines = wrap(metadata, metadataPaint, CONTENT_WIDTH.toFloat())
        val headerHeight = INFO_HEADER_TOP_PADDING + LABEL_LINE_HEIGHT + INFO_LABEL_GAP +
            titleLines.size * HERO_TITLE_LINE_HEIGHT + INFO_TITLE_META_GAP +
            metadataLines.size * META_LINE_HEIGHT + INFO_HEADER_BOTTOM_PADDING
        val bodyLines = wrapParagraphs(body, informationBodyPaint, INFO_TEXT_WIDTH.toFloat())
        val bodyHeight = INFO_BODY_TOP_PADDING + max(1, bodyLines.size) * INFO_BODY_LINE_HEIGHT + INFO_BODY_BOTTOM_PADDING
        val height = SHEET_TOP + headerHeight + bodyHeight + SHEET_BOTTOM
        checkSize(height)

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAGE_BACKGROUND)
        drawSheet(canvas, height, palette, headerHeight)

        var y = (SHEET_TOP + INFO_HEADER_TOP_PADDING).toFloat()
        labelPaint.color = palette.sharePrimaryColor
        canvas.drawText("信息告知", CONTENT_LEFT.toFloat(), y - labelPaint.ascent(), labelPaint)
        y += LABEL_LINE_HEIGHT + INFO_LABEL_GAP
        heroTitlePaint.color = TEXT_PRIMARY
        titleLines.forEach { line ->
            canvas.drawText(line, CONTENT_LEFT.toFloat(), y - heroTitlePaint.ascent(), heroTitlePaint)
            y += HERO_TITLE_LINE_HEIGHT
        }
        y += INFO_TITLE_META_GAP
        metadataPaint.color = TEXT_SECONDARY
        metadataLines.forEach { line ->
            canvas.drawText(line, CONTENT_LEFT.toFloat(), y - metadataPaint.ascent(), metadataPaint)
            y += META_LINE_HEIGHT
        }

        val bodyTop = (SHEET_TOP + headerHeight + INFO_BODY_TOP_PADDING).toFloat()
        accentPaint.color = palette.sharePrimaryColor
        canvas.drawRoundRect(
            RectF(CONTENT_LEFT.toFloat(), bodyTop, CONTENT_LEFT + INFO_ACCENT_WIDTH.toFloat(), bodyTop + max(1, bodyLines.size) * INFO_BODY_LINE_HEIGHT),
            INFO_ACCENT_WIDTH / 2f,
            INFO_ACCENT_WIDTH / 2f,
            accentPaint,
        )
        informationBodyPaint.color = TEXT_BODY
        var bodyY = bodyTop
        (bodyLines.ifEmpty { listOf(" ") }).forEach { line ->
            canvas.drawText(line, INFO_TEXT_LEFT.toFloat(), bodyY - informationBodyPaint.ascent(), informationBodyPaint)
            bodyY += INFO_BODY_LINE_HEIGHT
        }
        return GeneratedShareImage(bitmap, "告知-${sanitizeFileName(taskName)}-$taskDate.png")
    }

    private fun drawSheet(canvas: Canvas, height: Int, palette: UiPalette, headerHeight: Int) {
        val sheet = RectF(
            SHEET_LEFT.toFloat(),
            SHEET_TOP.toFloat(),
            (WIDTH - SHEET_LEFT).toFloat(),
            (height - SHEET_BOTTOM).toFloat(),
        )
        shadowPaint.color = SHEET_SHADOW
        canvas.drawRoundRect(RectF(sheet).apply { offset(0f, 12f) }, SHEET_RADIUS, SHEET_RADIUS, shadowPaint)
        sheetPaint.color = Color.WHITE
        canvas.drawRoundRect(sheet, SHEET_RADIUS, SHEET_RADIUS, sheetPaint)

        val path = Path().apply { addRoundRect(sheet, SHEET_RADIUS, SHEET_RADIUS, Path.Direction.CW) }
        val checkpoint = canvas.save()
        canvas.clipPath(path)
        headerPaint.shader = LinearGradient(
            sheet.left,
            sheet.top,
            sheet.right,
            sheet.top + headerHeight,
            blendWithWhite(palette.sharePrimaryColor, 0.70f),
            blendWithWhite(palette.sharePrimaryColor, 0.91f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(sheet.left, sheet.top, sheet.right, sheet.top + headerHeight, headerPaint)
        headerPaint.shader = null
        canvas.restoreToCount(checkpoint)
    }

    private fun drawResultHeader(
        canvas: Canvas,
        result: ResultPresentation,
        progress: ProgressSummary,
        palette: UiPalette,
    ) {
        val top = SHEET_TOP.toFloat()
        labelPaint.color = palette.sharePrimaryColor
        canvas.drawText("今日成绩单", CONTENT_LEFT.toFloat(), top + 60f, labelPaint)

        metadataPaint.color = TEXT_SECONDARY
        metadataPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(result.dateLabel, CONTENT_RIGHT.toFloat(), top + 60f, metadataPaint)
        metadataPaint.textAlign = Paint.Align.LEFT
        result.domName?.let {
            smallPaint.color = TEXT_SECONDARY
            canvas.drawText("来自 $it", CONTENT_LEFT.toFloat(), top + 102f, smallPaint)
        }

        val statusColor = statusColor(result.status, palette)
        drawStatusIcon(canvas, CONTENT_LEFT + STATUS_ICON_RADIUS, top + 151f, result.status, statusColor)
        statusPaint.color = TEXT_PRIMARY
        canvas.drawText(result.status, CONTENT_LEFT + 52f, top + 166f, statusPaint)

        bodyPaint.color = TEXT_SECONDARY
        canvas.drawText(progress.summaryText, CONTENT_LEFT.toFloat(), top + 209f, bodyPaint)

        progressPaint.color = TEXT_PRIMARY
        canvas.drawText("${progress.completed} / ${progress.total}", CONTENT_LEFT.toFloat(), top + 304f, progressPaint)
        smallPaint.color = TEXT_SECONDARY
        canvas.drawText(progress.label, CONTENT_LEFT.toFloat(), top + 345f, smallPaint)
        progress.optionalText?.let {
            canvas.drawText(it, CONTENT_LEFT + 232f, top + 345f, smallPaint)
        }

        val pointsRect = RectF(CONTENT_RIGHT - 246f, top + 244f, CONTENT_RIGHT.toFloat(), top + 354f)
        pillPaint.color = 0xD9FFFFFF.toInt()
        canvas.drawRoundRect(pointsRect, 28f, 28f, pillPaint)
        smallPaint.color = TEXT_SECONDARY
        canvas.drawText("本日积分", pointsRect.left + 24f, pointsRect.top + 35f, smallPaint)
        pointsPaint.color = pointsColor(result.totalPoints, palette)
        canvas.drawText(formatPoints(result.totalPoints), pointsRect.left + 24f, pointsRect.top + 91f, pointsPaint)
    }

    private fun drawGroup(canvas: Canvas, measured: MeasuredGroup, top: Float, palette: UiPalette) {
        var y = top + GROUP_TOP_PADDING
        groupTitlePaint.color = TEXT_PRIMARY
        measured.titleLines.forEach { line ->
            canvas.drawText(line, CONTENT_LEFT.toFloat(), y - groupTitlePaint.ascent(), groupTitlePaint)
            y += GROUP_TITLE_LINE_HEIGHT
        }
        groupMetaPaint.color = statusColor(measured.group.status, palette)
        canvas.drawText(measured.group.status, CONTENT_LEFT.toFloat(), y + 4f - groupMetaPaint.ascent(), groupMetaPaint)
        groupMetaPaint.textAlign = Paint.Align.RIGHT
        groupMetaPaint.color = pointsColor(measured.group.points, palette)
        canvas.drawText("净积分 ${formatPoints(measured.group.points)}", CONTENT_RIGHT.toFloat(), y + 4f - groupMetaPaint.ascent(), groupMetaPaint)
        groupMetaPaint.textAlign = Paint.Align.LEFT
        y += GROUP_META_LINE_HEIGHT + GROUP_HEADER_BOTTOM_GAP

        measured.tasks.forEachIndexed { index, task ->
            drawTask(canvas, task, y, palette)
            y += task.height
            if (index != measured.tasks.lastIndex) {
                dividerPaint.color = TASK_DIVIDER
                canvas.drawRect(TASK_TEXT_LEFT.toFloat(), y - 1f, CONTENT_RIGHT.toFloat(), y + 1f, dividerPaint)
            }
        }
        measured.messageLines?.let { lines ->
            y += MESSAGE_TOP_GAP
            val height = MESSAGE_VERTICAL_PADDING * 2 + lines.size * MESSAGE_LINE_HEIGHT
            messagePaint.color = blendWithWhite(palette.sharePrimaryColor, 0.91f)
            canvas.drawRoundRect(
                RectF(CONTENT_LEFT.toFloat(), y, CONTENT_RIGHT.toFloat(), y + height),
                20f,
                20f,
                messagePaint,
            )
            smallPaint.color = TEXT_SECONDARY
            var textY = y + MESSAGE_VERTICAL_PADDING
            lines.forEach { line ->
                canvas.drawText(line, CONTENT_LEFT + MESSAGE_HORIZONTAL_PADDING.toFloat(), textY - smallPaint.ascent(), smallPaint)
                textY += MESSAGE_LINE_HEIGHT
            }
        }
    }

    private fun drawTask(canvas: Canvas, measured: MeasuredTask, top: Float, palette: UiPalette) {
        val statusColor = statusColor(measured.task.status, palette)
        val iconCenterY = top + TASK_TOP_PADDING + 20f
        drawStatusIcon(canvas, TASK_ICON_CENTER_X.toFloat(), iconCenterY, measured.task.status, statusColor)

        taskTitlePaint.color = TEXT_PRIMARY
        var y = top + TASK_TOP_PADDING
        measured.titleLines.forEach { line ->
            canvas.drawText(line, TASK_TEXT_LEFT.toFloat(), y - taskTitlePaint.ascent(), taskTitlePaint)
            y += TASK_TITLE_LINE_HEIGHT
        }
        taskMetaPaint.color = statusColor
        canvas.drawText("${measured.task.requirement} · ${measured.task.status}", TASK_TEXT_LEFT.toFloat(), y + TASK_META_TOP_GAP - taskMetaPaint.ascent(), taskMetaPaint)

        taskPointsPaint.color = pointsColor(measured.task.points, palette)
        taskPointsPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatPoints(measured.task.points), CONTENT_RIGHT.toFloat(), top + TASK_TOP_PADDING - taskPointsPaint.ascent(), taskPointsPaint)
        taskPointsPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawEmptyIncompleteState(canvas: Canvas, palette: UiPalette) {
        val top = (SHEET_TOP + RESULT_HEADER_HEIGHT).toFloat()
        drawStatusIcon(canvas, WIDTH / 2f, top + 78f, STATUS_COMPLETED, palette.sharePrimaryColor, radius = 28f)
        emptyTitlePaint.color = TEXT_PRIMARY
        emptyTitlePaint.textAlign = Paint.Align.CENTER
        canvas.drawText("没有未完成任务", WIDTH / 2f, top + 142f, emptyTitlePaint)
        smallPaint.color = TEXT_SECONDARY
        smallPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("今天的计划已全部完成", WIDTH / 2f, top + 184f, smallPaint)
        emptyTitlePaint.textAlign = Paint.Align.LEFT
        smallPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawStatusIcon(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        status: String,
        color: Int,
        radius: Float = STATUS_ICON_RADIUS,
    ) {
        iconBackgroundPaint.color = blendWithWhite(color, 0.78f)
        canvas.drawCircle(centerX, centerY, radius, iconBackgroundPaint)
        iconStrokePaint.color = color
        iconStrokePaint.strokeWidth = max(4f, radius * 0.22f)
        when (status) {
            STATUS_COMPLETED, "全部完成" -> {
                val path = Path().apply {
                    moveTo(centerX - radius * 0.48f, centerY)
                    lineTo(centerX - radius * 0.10f, centerY + radius * 0.38f)
                    lineTo(centerX + radius * 0.55f, centerY - radius * 0.42f)
                }
                canvas.drawPath(path, iconStrokePaint)
            }
            STATUS_MISSED, "有任务未完成" -> {
                canvas.drawLine(centerX - radius * 0.38f, centerY - radius * 0.38f, centerX + radius * 0.38f, centerY + radius * 0.38f, iconStrokePaint)
                canvas.drawLine(centerX + radius * 0.38f, centerY - radius * 0.38f, centerX - radius * 0.38f, centerY + radius * 0.38f, iconStrokePaint)
            }
            STATUS_PENDING, "尚未完成" -> {
                canvas.drawCircle(centerX, centerY, radius * 0.58f, iconStrokePaint)
                canvas.drawLine(centerX, centerY, centerX, centerY - radius * 0.34f, iconStrokePaint)
                canvas.drawLine(centerX, centerY, centerX + radius * 0.28f, centerY + radius * 0.12f, iconStrokePaint)
            }
            else -> canvas.drawLine(centerX - radius * 0.38f, centerY, centerX + radius * 0.38f, centerY, iconStrokePaint)
        }
    }

    private fun measureGroup(group: ResultGroupPresentation): MeasuredGroup {
        val titleLines = wrap(group.name, groupTitlePaint, GROUP_TITLE_MAX_WIDTH.toFloat())
        val tasks = group.tasks.map(::measureTask)
        val messageLines = group.message?.let { wrapParagraphs(it, smallPaint, MESSAGE_TEXT_WIDTH.toFloat()) }
        val messageHeight = messageLines?.let {
            MESSAGE_TOP_GAP + MESSAGE_VERTICAL_PADDING * 2 + it.size * MESSAGE_LINE_HEIGHT
        } ?: 0
        val height = GROUP_TOP_PADDING + titleLines.size * GROUP_TITLE_LINE_HEIGHT + GROUP_META_LINE_HEIGHT +
            GROUP_HEADER_BOTTOM_GAP + tasks.sumOf { it.height } + messageHeight + GROUP_BOTTOM_PADDING
        return MeasuredGroup(group, titleLines, tasks, messageLines, height)
    }

    private fun measureTask(task: ResultTaskPresentation): MeasuredTask {
        val lines = wrap(task.name, taskTitlePaint, TASK_TITLE_MAX_WIDTH.toFloat())
        val height = TASK_TOP_PADDING + lines.size * TASK_TITLE_LINE_HEIGHT + TASK_META_TOP_GAP +
            TASK_META_LINE_HEIGHT + TASK_BOTTOM_PADDING
        return MeasuredTask(task, lines, height)
    }

    private fun ResultPresentation.progressSummary(): ProgressSummary {
        val tasks = groups.flatMap(ResultGroupPresentation::tasks)
        val required = tasks.filter { it.requirement == "必做" }
        val optional = tasks.filter { it.requirement != "必做" }
        val primary = required.ifEmpty { optional }
        val completed = primary.count { it.status == STATUS_COMPLETED }
        val pending = tasks.count { it.status == STATUS_PENDING }
        val missed = tasks.count { it.status == STATUS_MISSED }
        val summaryText = when {
            missed > 0 -> "今日有 $missed 项未完成"
            pending > 0 -> "还有 $pending 项待完成"
            else -> "今天的计划已全部完成"
        }
        return ProgressSummary(
            completed = completed,
            total = primary.size,
            label = if (required.isEmpty()) "选做完成" else "必做完成",
            optionalText = optional.takeIf { required.isNotEmpty() && it.isNotEmpty() }?.let {
                "选做完成 ${it.count { task -> task.status == STATUS_COMPLETED }} / ${it.size}"
            },
            summaryText = summaryText,
        )
    }

    private fun checkSize(height: Int) {
        if (height > MAX_HEIGHT || WIDTH.toLong() * height * 4 > MAX_BYTES) throw ShareImageTooLargeException()
    }

    private fun wrapParagraphs(text: String, paint: Paint, width: Float): List<String> =
        text.split('\n').flatMap { paragraph -> wrap(paragraph.ifEmpty { " " }, paint, width) }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
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

    private data class ProgressSummary(
        val completed: Int,
        val total: Int,
        val label: String,
        val optionalText: String?,
        val summaryText: String,
    )

    private data class MeasuredGroup(
        val group: ResultGroupPresentation,
        val titleLines: List<String>,
        val tasks: List<MeasuredTask>,
        val messageLines: List<String>?,
        val height: Int,
    )

    private data class MeasuredTask(
        val task: ResultTaskPresentation,
        val titleLines: List<String>,
        val height: Int,
    )

    companion object {
        const val WIDTH = 1080
        const val MAX_HEIGHT = 32_000
        private const val MAX_BYTES = 128L * 1024 * 1024
        private const val PAGE_BACKGROUND = 0xFFF3F6FA.toInt()
        private const val SHEET_SHADOW = 0x140F172A
        private const val TEXT_PRIMARY = 0xFF172033.toInt()
        private const val TEXT_BODY = 0xFF334155.toInt()
        private const val TEXT_SECONDARY = 0xFF64748B.toInt()
        private const val DIVIDER = 0xFFE2E8F0.toInt()
        private const val TASK_DIVIDER = 0xFFF1F5F9.toInt()
        private const val PENDING = 0xFFC27A24.toInt()
        private const val MISSED = 0xFFB9505A.toInt()
        private const val NEUTRAL = 0xFF64748B.toInt()
        private const val ZERO = 0xFF94A3B8.toInt()

        private const val STATUS_COMPLETED = "已完成"
        private const val STATUS_PENDING = "待完成"
        private const val STATUS_MISSED = "未完成"

        private const val SHEET_LEFT = 48
        private const val SHEET_TOP = 48
        private const val SHEET_BOTTOM = 60
        private const val SHEET_RADIUS = 40f
        private const val CONTENT_LEFT = 92
        private const val CONTENT_RIGHT = WIDTH - 92
        private const val CONTENT_WIDTH = CONTENT_RIGHT - CONTENT_LEFT
        private const val RESULT_HEADER_HEIGHT = 390
        private const val BODY_TOP_PADDING = 22
        private const val BODY_BOTTOM_PADDING = 38
        private const val EMPTY_BODY_HEIGHT = 234

        private const val STATUS_ICON_RADIUS = 20f
        private const val LABEL_LINE_HEIGHT = 36
        private const val HERO_TITLE_LINE_HEIGHT = 68
        private const val META_LINE_HEIGHT = 39
        private const val INFO_HEADER_TOP_PADDING = 48
        private const val INFO_LABEL_GAP = 24
        private const val INFO_TITLE_META_GAP = 24
        private const val INFO_HEADER_BOTTOM_PADDING = 42
        private const val INFO_BODY_TOP_PADDING = 54
        private const val INFO_BODY_BOTTOM_PADDING = 62
        private const val INFO_BODY_LINE_HEIGHT = 56
        private const val INFO_ACCENT_WIDTH = 8
        private const val INFO_TEXT_LEFT = CONTENT_LEFT + 34
        private const val INFO_TEXT_WIDTH = CONTENT_RIGHT - INFO_TEXT_LEFT

        private const val GROUP_TOP_PADDING = 32
        private const val GROUP_BOTTOM_PADDING = 34
        private const val GROUP_TITLE_LINE_HEIGHT = 54
        private const val GROUP_META_LINE_HEIGHT = 42
        private const val GROUP_HEADER_BOTTOM_GAP = 18
        private const val GROUP_TITLE_MAX_WIDTH = 650
        private const val TASK_TOP_PADDING = 22
        private const val TASK_BOTTOM_PADDING = 20
        private const val TASK_TITLE_LINE_HEIGHT = 46
        private const val TASK_META_TOP_GAP = 8
        private const val TASK_META_LINE_HEIGHT = 36
        private const val TASK_ICON_CENTER_X = 112
        private const val TASK_TEXT_LEFT = 150
        private const val TASK_TITLE_MAX_WIDTH = CONTENT_RIGHT - TASK_TEXT_LEFT - 110
        private const val MESSAGE_TOP_GAP = 20
        private const val MESSAGE_VERTICAL_PADDING = 18
        private const val MESSAGE_HORIZONTAL_PADDING = 22
        private const val MESSAGE_LINE_HEIGHT = 38
        private const val MESSAGE_TEXT_WIDTH = CONTENT_WIDTH - MESSAGE_HORIZONTAL_PADDING * 2

        private fun paint(size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

        fun sanitizeFileName(value: String): String = value
            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
            .trim().trim('.')
            .take(60)
            .ifBlank { "任务" }

        private fun blendWithWhite(color: Int, whiteFraction: Float): Int {
            val keep = 1f - whiteFraction
            return Color.rgb(
                (Color.red(color) * keep + 255 * whiteFraction).toInt(),
                (Color.green(color) * keep + 255 * whiteFraction).toInt(),
                (Color.blue(color) * keep + 255 * whiteFraction).toInt(),
            )
        }
    }

    private val labelPaint = paint(30f, bold = true)
    private val metadataPaint = paint(28f)
    private val smallPaint = paint(28f)
    private val statusPaint = paint(42f, bold = true)
    private val bodyPaint = paint(30f)
    private val progressPaint = paint(76f, bold = true)
    private val pointsPaint = paint(44f, bold = true)
    private val heroTitlePaint = paint(54f, bold = true)
    private val informationBodyPaint = paint(36f)
    private val groupTitlePaint = paint(42f, bold = true)
    private val groupMetaPaint = paint(29f)
    private val taskTitlePaint = paint(35f, bold = true)
    private val taskMetaPaint = paint(27f)
    private val taskPointsPaint = paint(32f, bold = true)
    private val emptyTitlePaint = paint(38f, bold = true)
    private val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun statusColor(status: String, palette: UiPalette): Int = when (status) {
        STATUS_COMPLETED, "全部完成" -> palette.sharePrimaryColor
        STATUS_PENDING, "尚未完成" -> PENDING
        STATUS_MISSED, "有任务未完成" -> MISSED
        else -> NEUTRAL
    }

    private fun pointsColor(points: Int, palette: UiPalette): Int = when {
        points > 0 -> palette.sharePrimaryColor
        points < 0 -> MISSED
        else -> ZERO
    }
}

internal val UiPalette.sharePrimaryColor: Int
    get() = when (this) {
        UiPalette.INDIGO -> 0xFF818CF8.toInt()
        UiPalette.SKY -> 0xFF78A4CB.toInt()
    }
