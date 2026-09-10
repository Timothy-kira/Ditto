package kira.ditto.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Shared Aether surfaces use the same vendored Lucide outlines on Android and iOS.
object LucideIcons {
    val Search: ImageVector
        get() {
            if (_search != null) return _search!!

            _search = ImageVector.Builder(
                name = "search",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(21f, 21f)
                    lineToRelative(-4.34f, -4.34f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(19f, 11f)
                    arcTo(8f, 8f, 0f, false, true, 11f, 19f)
                    arcTo(8f, 8f, 0f, false, true, 3f, 11f)
                    arcTo(8f, 8f, 0f, false, true, 19f, 11f)
                    close()
                }
            }.build()

            return _search!!
        }

    val Settings: ImageVector
        get() {
            if (_settings != null) return _settings!!

            _settings = ImageVector.Builder(
                name = "settings",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(9.671f, 4.136f)
                    arcToRelative(2.34f, 2.34f, 0f, false, true, 4.659f, 0f)
                    arcToRelative(2.34f, 2.34f, 0f, false, false, 3.319f, 1.915f)
                    arcToRelative(2.34f, 2.34f, 0f, true, true, 2.33f, 4.033f)
                    arcToRelative(2.34f, 2.34f, 0f, false, false, 0f, 3.831f)
                    arcToRelative(2.34f, 2.34f, 0f, true, true, -2.33f, 4.033f)
                    arcToRelative(2.34f, 2.34f, 0f, false, false, -3.319f, 1.915f)
                    arcToRelative(2.34f, 2.34f, 0f, false, true, -4.659f, 0f)
                    arcToRelative(2.34f, 2.34f, 0f, false, false, -3.32f, -1.915f)
                    arcToRelative(2.34f, 2.34f, 0f, true, true, -2.33f, -4.033f)
                    arcToRelative(2.34f, 2.34f, 0f, false, false, 0f, -3.831f)
                    arcTo(2.34f, 2.34f, 0f, true, true, 6.35f, 6.051f)
                    arcToRelative(2.34f, 2.34f, 0f, false, false, 3.319f, -1.915f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(15f, 12f)
                    arcTo(3f, 3f, 0f, false, true, 12f, 15f)
                    arcTo(3f, 3f, 0f, false, true, 9f, 12f)
                    arcTo(3f, 3f, 0f, false, true, 15f, 12f)
                    close()
                }
            }.build()

            return _settings!!
        }

    val SquarePen: ImageVector
        get() {
            if (_squarePen != null) return _squarePen!!

            _squarePen = ImageVector.Builder(
                name = "square-pen",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(12f, 3f)
                    horizontalLineTo(5f)
                    arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
                    verticalLineToRelative(14f)
                    arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
                    horizontalLineToRelative(14f)
                    arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
                    verticalLineToRelative(-7f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(18.375f, 2.625f)
                    arcToRelative(1f, 1f, 0f, false, true, 3f, 3f)
                    lineToRelative(-9.013f, 9.014f)
                    arcToRelative(2f, 2f, 0f, false, true, -0.853f, 0.505f)
                    lineToRelative(-2.873f, 0.84f)
                    arcToRelative(0.5f, 0.5f, 0f, false, true, -0.62f, -0.62f)
                    lineToRelative(0.84f, -2.873f)
                    arcToRelative(2f, 2f, 0f, false, true, 0.506f, -0.852f)
                    close()
                }
            }.build()

            return _squarePen!!
        }

    val X: ImageVector
        get() {
            if (_x != null) return _x!!

            _x = ImageVector.Builder(
                name = "x",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(18f, 6f)
                    lineTo(6f, 18f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(6f, 6f)
                    lineToRelative(12f, 12f)
                }
            }.build()

            return _x!!
        }

    val Copy: ImageVector
        get() {
            if (_copy != null) return _copy!!

            _copy = ImageVector.Builder(
                name = "copy",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(9f, 9f)
                    horizontalLineToRelative(11f)
                    arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                    verticalLineToRelative(9f)
                    arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                    horizontalLineTo(9f)
                    arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                    verticalLineToRelative(-9f)
                    arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                    close()
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(5f, 15f)
                    horizontalLineTo(4f)
                    arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                    verticalLineTo(4f)
                    arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                    horizontalLineToRelative(9f)
                    arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                    verticalLineToRelative(1f)
                }
            }.build()

            return _copy!!
        }

    val Cursor: ImageVector
        get() {
            if (_cursor != null) return _cursor!!

            _cursor = ImageVector.Builder(
                name = "cursor",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(3f, 3f)
                    lineToRelative(7.07f, 16.97f)
                    lineToRelative(2.51f, -7.39f)
                    lineToRelative(7.39f, -2.51f)
                    close()
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(13f, 13f)
                    lineToRelative(6f, 6f)
                }
            }.build()

            return _cursor!!
        }

    val MousePointer2: ImageVector
        get() {
            if (_mousePointer2 != null) return _mousePointer2!!

            _mousePointer2 = ImageVector.Builder(
                name = "mouse-pointer-2",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(4.037f, 4.688f)
                    arcToRelative(0.495f, 0.495f, 0f, false, true, 0.651f, -0.651f)
                    lineToRelative(16f, 6.5f)
                    arcToRelative(0.5f, 0.5f, 0f, false, true, -0.063f, 0.947f)
                    lineToRelative(-6.124f, 1.58f)
                    arcToRelative(2f, 2f, 0f, false, false, -1.438f, 1.435f)
                    lineToRelative(-1.579f, 6.126f)
                    arcToRelative(0.5f, 0.5f, 0f, false, true, -0.947f, 0.063f)
                    close()
                }
            }.build()

            return _mousePointer2!!
        }

    val MousePointer2WhiteFill: ImageVector
        get() {
            if (_mousePointer2WhiteFill != null) return _mousePointer2WhiteFill!!

            _mousePointer2WhiteFill = ImageVector.Builder(
                name = "mouse-pointer-2-white-fill",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.White),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(4.037f, 4.688f)
                    arcToRelative(0.495f, 0.495f, 0f, false, true, 0.651f, -0.651f)
                    lineToRelative(16f, 6.5f)
                    arcToRelative(0.5f, 0.5f, 0f, false, true, -0.063f, 0.947f)
                    lineToRelative(-6.124f, 1.58f)
                    arcToRelative(2f, 2f, 0f, false, false, -1.438f, 1.435f)
                    lineToRelative(-1.579f, 6.126f)
                    arcToRelative(0.5f, 0.5f, 0f, false, true, -0.947f, 0.063f)
                    close()
                }
            }.build()

            return _mousePointer2WhiteFill!!
        }

    val RotateCcw: ImageVector
        get() {
            if (_rotateCcw != null) return _rotateCcw!!

            _rotateCcw = ImageVector.Builder(
                name = "rotate-ccw",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(3f, 12f)
                    arcToRelative(9f, 9f, 0f, true, false, 9f, -9f)
                    arcToRelative(9.75f, 9.75f, 0f, false, false, -6.74f, 2.74f)
                    lineTo(3f, 8f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(3f, 3f)
                    verticalLineToRelative(5f)
                    horizontalLineToRelative(5f)
                }
            }.build()

            return _rotateCcw!!
        }

    val Trash2: ImageVector
        get() {
            if (_trash2 != null) return _trash2!!

            _trash2 = ImageVector.Builder(
                name = "trash-2",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(3f, 6f)
                    horizontalLineToRelative(18f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(8f, 6f)
                    verticalLineTo(4f)
                    arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                    horizontalLineToRelative(4f)
                    arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                    verticalLineToRelative(2f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(19f, 6f)
                    lineToRelative(-1f, 14f)
                    arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                    horizontalLineTo(8f)
                    arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                    lineTo(5f, 6f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(10f, 11f)
                    verticalLineToRelative(6f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(14f, 11f)
                    verticalLineToRelative(6f)
                }
            }.build()

            return _trash2!!
        }

    val ChartNoAxesColumn: ImageVector
        get() {
            if (_chartNoAxesColumn != null) return _chartNoAxesColumn!!

            _chartNoAxesColumn = ImageVector.Builder(
                name = "chart-no-axes-column",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(5f, 21f)
                    verticalLineToRelative(-6f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(12f, 21f)
                    verticalLineTo(9f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(19f, 21f)
                    verticalLineTo(3f)
                }
            }.build()

            return _chartNoAxesColumn!!
        }

    val Zap: ImageVector
        get() {
            if (_zap != null) return _zap!!

            _zap = ImageVector.Builder(
                name = "zap",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(4f, 14f)
                    arcToRelative(1f, 1f, 0f, false, true, -0.78f, -1.63f)
                    lineToRelative(9.9f, -10.2f)
                    arcToRelative(0.5f, 0.5f, 0f, false, true, 0.86f, 0.46f)
                    lineToRelative(-1.92f, 6.02f)
                    arcTo(1f, 1f, 0f, false, false, 13f, 10f)
                    horizontalLineToRelative(7f)
                    arcToRelative(1f, 1f, 0f, false, true, 0.78f, 1.63f)
                    lineToRelative(-9.9f, 10.2f)
                    arcToRelative(0.5f, 0.5f, 0f, false, true, -0.86f, -0.46f)
                    lineToRelative(1.92f, -6.02f)
                    arcTo(1f, 1f, 0f, false, false, 11f, 14f)
                    close()
                }
            }.build()

            return _zap!!
        }

    val Brain: ImageVector
        get() {
            if (_brain != null) return _brain!!

            _brain = ImageVector.Builder(
                name = "brain",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(12f, 5f)
                    arcToRelative(3f, 3f, 0f, true, false, -5.997f, 0.125f)
                    arcToRelative(4f, 4f, 0f, false, false, -2.526f, 5.77f)
                    arcToRelative(4f, 4f, 0f, false, false, 0.556f, 6.588f)
                    arcTo(4f, 4f, 0f, true, false, 12f, 18f)
                    close()
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(12f, 5f)
                    arcToRelative(3f, 3f, 0f, true, true, 5.997f, 0.125f)
                    arcToRelative(4f, 4f, 0f, false, true, 2.526f, 5.77f)
                    arcToRelative(4f, 4f, 0f, false, true, -0.556f, 6.588f)
                    arcTo(4f, 4f, 0f, true, true, 12f, 18f)
                    close()
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(15f, 13f)
                    arcToRelative(4.5f, 4.5f, 0f, false, false, -3f, -4f)
                    arcToRelative(4.5f, 4.5f, 0f, false, false, -3f, 4f)
                }
            }.build()

            return _brain!!
        }

    val VenetianMask: ImageVector
        get() {
            if (_venetianMask != null) return _venetianMask!!
            _venetianMask = ImageVector.Builder(
                name = "persona-mask",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(3f, 11f)
                    curveTo(3f, 6.6f, 7f, 3.5f, 12f, 3.5f)
                    curveTo(17f, 3.5f, 21f, 6.6f, 21f, 11f)
                    curveTo(21f, 13.4f, 19.2f, 15.4f, 16.6f, 16.1f)
                    curveTo(14.8f, 16.7f, 13.2f, 15.8f, 12f, 14.9f)
                    curveTo(10.8f, 15.8f, 9.2f, 16.7f, 7.4f, 16.1f)
                    curveTo(4.8f, 15.4f, 3f, 13.4f, 3f, 11f)
                    close()
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(6.6f, 10.4f)
                    curveTo(7.4f, 8.8f, 10f, 8.8f, 10.8f, 10.4f)
                    curveTo(10f, 11.4f, 7.4f, 11.4f, 6.6f, 10.4f)
                    close()
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(13.2f, 10.4f)
                    curveTo(14f, 8.8f, 16.6f, 8.8f, 17.4f, 10.4f)
                    curveTo(16.6f, 11.4f, 14f, 11.4f, 13.2f, 10.4f)
                    close()
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(9.2f, 13.4f)
                    curveTo(10.2f, 14.7f, 13.8f, 14.7f, 14.8f, 13.4f)
                }
            }.build()
            return _venetianMask!!
        }

    val Users: ImageVector
        get() {
            if (_users != null) return _users!!
            _users = ImageVector.Builder(
                name = "users",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                val fill = SolidColor(Color(0xFF000000))
                path(fill = fill) {
                    moveTo(13.3f, 5.7f)
                    arcToRelative(3.4f, 3.4f, 0f, true, true, -6.8f, 0f)
                    arcToRelative(3.4f, 3.4f, 0f, true, true, 6.8f, 0f)
                    close()
                }
                path(fill = fill) {
                    moveTo(21.5f, 7.4f)
                    arcToRelative(2.9f, 2.9f, 0f, true, true, -5.8f, 0f)
                    arcToRelative(2.9f, 2.9f, 0f, true, true, 5.8f, 0f)
                    close()
                }
                path(fill = fill) {
                    moveTo(8.4f, 10.2f)
                    curveTo(10.6f, 9.8f, 13.0f, 10.6f, 13.5f, 13.4f)
                    curveTo(13.9f, 16.2f, 13.6f, 18.8f, 13.1f, 20.3f)
                    curveTo(12.6f, 21.5f, 10.8f, 21.7f, 8.6f, 21.4f)
                    curveTo(5.8f, 21.1f, 4.2f, 20.2f, 3.7f, 18.0f)
                    curveTo(3.3f, 16.2f, 3.8f, 13.6f, 2.4f, 11.5f)
                    curveTo(1.5f, 10.0f, 1.3f, 8.2f, 2.5f, 7.6f)
                    curveTo(4.0f, 7.0f, 5.6f, 8.0f, 6.4f, 9.4f)
                    curveTo(7.0f, 10.3f, 7.6f, 10.4f, 8.4f, 10.2f)
                    close()
                }
                path(fill = fill) {
                    moveTo(16.2f, 12.4f)
                    curveTo(16.0f, 15.2f, 16.4f, 18.4f, 16.9f, 20.0f)
                    curveTo(17.4f, 21.3f, 19.2f, 21.5f, 20.7f, 20.6f)
                    curveTo(22.4f, 19.5f, 23.1f, 16.4f, 22.4f, 13.6f)
                    curveTo(22.0f, 12.0f, 21.2f, 10.8f, 19.6f, 10.6f)
                    curveTo(18.0f, 10.4f, 16.4f, 11.0f, 16.2f, 12.4f)
                    close()
                }
            }.build()
            return _users!!
        }

    val Slime: ImageVector
        get() {
            if (_slime != null) return _slime!!
            _slime = ImageVector.Builder(
                name = "slime",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(5.2f, 14.2f)
                    curveTo(4.6f, 12.1f, 5.4f, 8.8f, 8.2f, 7.4f)
                    curveTo(9.4f, 4.8f, 12.2f, 4f, 14.4f, 5.6f)
                    curveTo(16.8f, 4.9f, 19.4f, 6.4f, 19.6f, 9.2f)
                    curveTo(21.6f, 10.6f, 21.4f, 14.2f, 19.2f, 15.8f)
                    curveTo(18.4f, 18.8f, 14.8f, 20.4f, 11.6f, 19.4f)
                    curveTo(8.6f, 20.2f, 5.8f, 17.6f, 5.2f, 14.2f)
                    close()
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(9.2f, 12.2f)
                    arcToRelative(0.6f, 0.6f, 0f, true, true, 0f, 0.02f)
                    close()
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(14.6f, 12.2f)
                    arcToRelative(0.6f, 0.6f, 0f, true, true, 0f, 0.02f)
                    close()
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(9.4f, 15.2f)
                    curveTo(10.4f, 16.2f, 13.4f, 16.2f, 14.4f, 15.2f)
                }
            }.build()
            return _slime!!
        }

    val Plug: ImageVector
        get() {
            if (_plug != null) return _plug!!
            _plug = ImageVector.Builder(
                name = "plug",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(12f, 22f)
                    verticalLineToRelative(-5f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(9f, 8f)
                    verticalLineTo(2f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(15f, 8f)
                    verticalLineTo(2f)
                }
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(18f, 8f)
                    verticalLineToRelative(5f)
                    arcToRelative(4f, 4f, 0f, false, true, -4f, 4f)
                    horizontalLineToRelative(-4f)
                    arcToRelative(4f, 4f, 0f, false, true, -4f, -4f)
                    verticalLineTo(8f)
                    close()
                }
            }.build()
            return _plug!!
        }

    /** 手绘 Remote 图标：圆角方块里的终端提示符 `>_`。 */
    val SquareTerminal: ImageVector
        get() {
            if (_squareTerminal != null) return _squareTerminal!!
            _squareTerminal = ImageVector.Builder(
                name = "square_terminal",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                // 圆角外框
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(8f, 3f)
                    horizontalLineTo(16f)
                    arcToRelative(5f, 5f, 0f, false, true, 5f, 5f)
                    verticalLineToRelative(8f)
                    arcToRelative(5f, 5f, 0f, false, true, -5f, 5f)
                    horizontalLineTo(8f)
                    arcToRelative(5f, 5f, 0f, false, true, -5f, -5f)
                    verticalLineTo(8f)
                    arcToRelative(5f, 5f, 0f, false, true, 5f, -5f)
                    close()
                }
                // > 提示符
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(7.5f, 9f)
                    lineTo(10.5f, 12f)
                    lineTo(7.5f, 15f)
                }
                // _ 下划线
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(13f, 15f)
                    horizontalLineTo(17f)
                }
            }.build()
            return _squareTerminal!!
        }

    private var _search: ImageVector? = null
    private var _settings: ImageVector? = null
    private var _squarePen: ImageVector? = null
    private var _x: ImageVector? = null
    private var _copy: ImageVector? = null
    private var _cursor: ImageVector? = null
    private var _mousePointer2: ImageVector? = null
    private var _mousePointer2WhiteFill: ImageVector? = null
    private var _rotateCcw: ImageVector? = null
    private var _trash2: ImageVector? = null
    private var _chartNoAxesColumn: ImageVector? = null
    private var _zap: ImageVector? = null
    private var _brain: ImageVector? = null
    private var _venetianMask: ImageVector? = null
    private var _users: ImageVector? = null
    private var _slime: ImageVector? = null
    private var _plug: ImageVector? = null
    private var _squareTerminal: ImageVector? = null
    private var _play: ImageVector? = null
    private var _square: ImageVector? = null
    private var _globe: ImageVector? = null

    val Play: ImageVector
        get() {
            if (_play != null) return _play!!
            _play = ImageVector.Builder(
                name = "play",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color(0xFF000000)),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(6f, 3f)
                    lineTo(20f, 12f)
                    lineTo(6f, 21f)
                    close()
                }
            }.build()
            return _play!!
        }

    val Square: ImageVector
        get() {
            if (_square != null) return _square!!
            _square = ImageVector.Builder(
                name = "square",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color(0xFF000000)),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(5f, 3f)
                    lineTo(19f, 3f)
                    curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
                    lineTo(21f, 19f)
                    curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
                    lineTo(5f, 21f)
                    curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
                    lineTo(3f, 5f)
                    curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
                    close()
                }
            }.build()
            return _square!!
        }

    val Globe: ImageVector
        get() {
            if (_globe != null) return _globe!!
            _globe = ImageVector.Builder(
                name = "globe",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                val stroke = SolidColor(Color(0xFF000000))
                path(
                    stroke = stroke,
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(22f, 12f)
                    arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
                    arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
                    close()
                }
                path(
                    stroke = stroke,
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(12f, 2f)
                    arcToRelative(14.5f, 14.5f, 0f, false, false, 0f, 20f)
                    arcToRelative(14.5f, 14.5f, 0f, false, false, 0f, -20f)
                }
                path(
                    stroke = stroke,
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(2f, 12f)
                    lineTo(22f, 12f)
                }
            }.build()
            return _globe!!
        }
}
