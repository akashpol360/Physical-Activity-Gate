package com.physicalactivitygate

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val GUIDE_PAGES = 6

/**
 * First-launch walkthrough (and re-openable from Settings → "How to use this
 * app"). Swipeable pages, including a looping animated clip that shows how to
 * stand/lie in front of the phone and do the exercise.
 */
@Composable
fun GuideScreen(activity: ComponentActivity, firstRun: Boolean, onDone: () -> Unit) {
    val pager = rememberPagerState { GUIDE_PAGES }
    val scope = rememberCoroutineScope()
    val last = GUIDE_PAGES - 1

    val base = if (firstRun) Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing) else Modifier.fillMaxSize()
    Column(base.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text(if (firstRun) "Skip" else "Close") }
        }

        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (page) {
                    0 -> WelcomePage()
                    1 -> HowItWorksPage()
                    2 -> DemoPage()
                    3 -> SetupTipsPage()
                    4 -> PermissionsPage()
                    else -> ReadyPage()
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // Page dots
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(GUIDE_PAGES) { i ->
                val selected = i == pager.currentPage
                val w by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dot")
                Box(
                    Modifier.padding(horizontal = 3.dp).size(w, 8.dp).clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                )
            }
        }

        if (pager.currentPage < last) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pager.currentPage > 0) {
                    OutlinedButton(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        modifier = Modifier.weight(1f)
                    ) { Text("Back") }
                }
                Button(
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.weight(if (pager.currentPage > 0) 1f else 2f)
                ) { Text("Next") }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = {
                        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        if (firstRun) onDone()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Turn on Accessibility Service") }
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(if (firstRun) "I'll do it later — start the app" else "Done")
                }
            }
        }
    }
}

// ───────────────────────── Pages ─────────────────────────

@Composable
private fun WelcomePage() {
    Spacer(Modifier.height(16.dp))
    WatercolorBadge(Icons.Filled.FitnessCenter)
    Spacer(Modifier.height(20.dp))
    Text("Earn your screen time", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(
        "Physical Activity Gate puts a short exercise between you and the apps that eat your time. " +
            "Do a few squats or take a few steps — then the app opens.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "No account. No server. Everything stays on your phone.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(12.dp))
    Text("Swipe or tap Next for a 1-minute tour.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HowItWorksPage() {
    PageTitle("How it works")
    GuideStep(1, Icons.Filled.Apps, "Pick apps to protect", "Open the Apps tab and switch on the apps that distract you (Instagram, YouTube, games…).")
    GuideStep(2, Icons.Filled.Lock, "Open a protected app", "Before it opens, the Gate screen appears and asks for a quick activity.")
    GuideStep(3, Icons.Filled.FitnessCenter, "Do the activity", "For example 10 squats in front of the camera, or a few hundred steps while carrying your phone.")
    GuideStep(4, Icons.Filled.LockOpen, "The app unlocks", "It stays open for the time you chose — or until you leave the app. Then the gate returns.")
}

@Composable
private fun DemoPage() {
    PageTitle("See it in action")
    Text(
        "This is exactly how to exercise in front of your phone. Pick an exercise to watch.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(14.dp))
    DemoClip()
}

@Composable
private fun SetupTipsPage() {
    PageTitle("Get a perfect count")
    GuideStep(1, Icons.Filled.PhoneAndroid, "Prop the phone up", "Lean it on a shelf, wall or bottle about 2–3 meters (6–10 ft) away, camera facing you.")
    GuideStep(2, Icons.Filled.AccessibilityNew, "Keep your whole body in frame", "Head to feet should be visible. For push-ups, lie side-on to the phone.")
    GuideStep(3, Icons.Filled.WbSunny, "Use good light", "A bright room and a plain background help the camera follow your movement.")
    GuideStep(4, Icons.Filled.DirectionsWalk, "Steps need no camera", "For the Steps activity just carry your phone and walk — the built-in step sensor counts for you.")
    Spacer(Modifier.height(4.dp))
    Text(
        "Prefer no camera? Turn off “Camera verification” in Settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PermissionsPage() {
    PageTitle("Permissions, explained")
    GuideStep(1, Icons.Filled.Accessibility, "Accessibility Service (required)", "Lets Android tell the app when a protected app opens. Nothing you type or see is read or stored.")
    GuideStep(2, Icons.Filled.CameraAlt, "Camera", "Used only while you exercise. Frames are processed on your phone and never uploaded.")
    GuideStep(3, Icons.Filled.DirectionsWalk, "Activity recognition", "Only for counting steps.")
    GuideStep(4, Icons.Filled.Notifications, "Notifications", "A heads-up one minute before your access expires.")
    GuideStep(5, Icons.Filled.BatteryChargingFull, "Battery optimization", "On Xiaomi, Vivo, Oppo and similar phones, exempt this app (Settings tab has a shortcut) or Android may switch protection off.")
}

@Composable
private fun ReadyPage() {
    Spacer(Modifier.height(16.dp))
    WatercolorBadge(Icons.Filled.TouchApp)
    Spacer(Modifier.height(20.dp))
    Text("You're ready!", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(
        "One last step: turn on the Accessibility Service for Physical Activity Gate, then choose your apps in the Apps tab.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "You can replay this guide anytime from Settings → “How to use this app”.",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.primary
    )
}

// ───────────────────────── Building blocks ─────────────────────────

@Composable
private fun PageTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun GuideStep(number: Int, icon: ImageVector, title: String, text: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.8f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("$number. $title", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A hand-painted looking splash of overlapping washes with an icon on top. */
@Composable
private fun WatercolorBadge(icon: ImageVector) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val rose = Color(0xFFE98A9B)
    val lavender = Color(0xFF9D92E0)
    Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            fun blob(c: Color, cx: Float, cy: Float, r: Float) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(c.copy(alpha = 0.75f), c.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(cx, cy), radius = r
                    ),
                    radius = r, center = Offset(cx, cy)
                )
            }
            val w = size.width
            blob(primary, w * 0.40f, w * 0.42f, w * 0.48f)
            blob(rose, w * 0.66f, w * 0.40f, w * 0.38f)
            blob(secondary, w * 0.55f, w * 0.68f, w * 0.36f)
            blob(lavender, w * 0.30f, w * 0.70f, w * 0.32f)
        }
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
    }
}

// ───────────────────────── Animated exercise demo ─────────────────────────

private enum class Demo(val label: String, val caption: String) {
    SQUATS("Squats", "Stand facing the phone, feet shoulder-width. Bend your knees down, then stand tall — one dip is one rep."),
    PUSH_UPS("Push-ups", "Lie side-on to the phone. Lower your chest toward the floor, then press back up."),
    JACKS("Jumping jacks", "Face the phone. Jump your feet apart with arms overhead, then back together.")
}

private const val DEMO_GOAL = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoClip() {
    var demo by remember { mutableStateOf(Demo.SQUATS) }
    var rep by remember { mutableStateOf(0) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(demo) {
        rep = 0
        progress.snapTo(0f)
        while (true) {
            progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            progress.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
            rep += 1
            if (rep >= DEMO_GOAL) {
                delay(1800)
                rep = 0
            }
        }
    }

    val ink = MaterialTheme.colorScheme.onBackground
    val accent = MaterialTheme.colorScheme.primary
    val warm = MaterialTheme.colorScheme.secondary
    val line = MaterialTheme.colorScheme.outline
    val dark = isSystemInDarkTheme()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, line)
    ) {
        Box(Modifier.fillMaxWidth().height(230.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawScene(demo, progress.value, ink, accent, warm, line, dark)
            }
            // "Live clip" tag
            Row(
                Modifier.align(Alignment.TopStart).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                Spacer(Modifier.width(6.dp))
                Text("Demo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Rep counter / unlocked
            val done = rep >= DEMO_GOAL
            Row(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (done) {
                    Icon(Icons.Filled.LockOpen, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Unlocked!", style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.Bold)
                } else {
                    Text("Rep ${rep.coerceAtMost(DEMO_GOAL)} / $DEMO_GOAL", style = MaterialTheme.typography.labelMedium, color = ink)
                }
            }
            Text(
                "← 2–3 m →",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        Demo.entries.forEach { d ->
            FilterChip(selected = demo == d, onClick = { demo = d }, label = { Text(d.label) })
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        demo.caption,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/** Two-bone IK: returns the joint (knee/elbow) between [a] and [b], bending toward [pref]. */
private fun ik(a: Offset, b: Offset, segment: Float, pref: Offset): Offset {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
    val d = len.coerceAtMost(2f * segment - 0.5f)
    val height = sqrt((segment * segment - d * d / 4f).coerceAtLeast(0f))
    val mx = (a.x + b.x) / 2f
    val my = (a.y + b.y) / 2f
    val nx = -dy / len
    val ny = dx / len
    val k1 = Offset(mx + nx * height, my + ny * height)
    val k2 = Offset(mx - nx * height, my - ny * height)
    val s1 = (k1.x - mx) * pref.x + (k1.y - my) * pref.y
    return if (s1 >= 0f) k1 else k2
}

private fun DrawScope.seg(color: Color, a: Offset, b: Offset, width: Float) =
    drawLine(color, a, b, strokeWidth = width, cap = StrokeCap.Round)

private fun rad(deg: Float) = (deg * PI / 180.0).toFloat()

private fun DrawScope.drawScene(
    demo: Demo,
    p: Float,
    ink: Color,
    accent: Color,
    warm: Color,
    line: Color,
    dark: Boolean
) {
    val w = size.width
    val h = size.height
    val g = h * 0.80f // ground line
    val limb = h * 0.030f
    val torsoW = h * 0.050f

    // Floor
    seg(line, Offset(w * 0.03f, g + h * 0.02f), Offset(w * 0.97f, g + h * 0.02f), 3f)

    // Phone propped on the left, with dashed "sight lines" to the person
    val phoneW = w * 0.065f
    val phoneH = h * 0.26f
    val phoneX = w * 0.07f
    val phoneY = g - phoneH
    drawRoundRect(ink.copy(alpha = 0.85f), Offset(phoneX, phoneY), Size(phoneW, phoneH), CornerRadius(8f, 8f))
    drawRoundRect(
        (if (dark) Color(0xFF14222B) else Color(0xFFFBF6EC)),
        Offset(phoneX + phoneW * 0.14f, phoneY + phoneH * 0.08f),
        Size(phoneW * 0.72f, phoneH * 0.78f),
        CornerRadius(5f, 5f)
    )
    drawCircle(warm, radius = phoneW * 0.12f, center = Offset(phoneX + phoneW / 2f, phoneY + phoneH * 0.93f))
    val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 12f))
    val eye = Offset(phoneX + phoneW, phoneY + phoneH * 0.45f)
    drawLine(accent.copy(alpha = 0.55f), eye, Offset(w * 0.86f, g - h * 0.62f), 2.5f, pathEffect = dash)
    drawLine(accent.copy(alpha = 0.55f), eye, Offset(w * 0.86f, g), 2.5f, pathEffect = dash)

    val r = h * 0.05f // head radius
    when (demo) {
        Demo.SQUATS -> {
            val cx = w * 0.62f
            val l = h * 0.17f
            val t = h * 0.17f
            val hip = Offset(cx, g - lerp(2f * l * 0.93f, 2f * l * 0.58f, p))
            val footDx = l * 0.55f
            val fl = Offset(cx - footDx, g)
            val fr = Offset(cx + footDx, g)
            val kl = ik(hip, fl, l, Offset(-1f, 0f))
            val kr = ik(hip, fr, l, Offset(1f, 0f))
            seg(ink, hip, kl, limb); seg(ink, kl, fl, limb)
            seg(ink, hip, kr, limb); seg(ink, kr, fr, limb)
            val sh = Offset(cx, hip.y - t)
            seg(accent, hip, sh, torsoW)
            val sw = t * 0.32f
            val sl = Offset(cx - sw, sh.y)
            val sr = Offset(cx + sw, sh.y)
            seg(accent, sl, sr, limb)
            // Arms come up to chest height as you squat (counterbalance)
            val handY = lerp(sh.y + t * 0.95f, sh.y + t * 0.30f, p)
            val el = Offset(cx - sw * 1.5f, sh.y + t * 0.62f)
            val er = Offset(cx + sw * 1.5f, sh.y + t * 0.62f)
            val hl = Offset(cx - sw * 0.25f, handY)
            val hr = Offset(cx + sw * 0.25f, handY)
            seg(ink, sl, el, limb * 0.85f); seg(ink, el, hl, limb * 0.85f)
            seg(ink, sr, er, limb * 0.85f); seg(ink, er, hr, limb * 0.85f)
            drawCircle(ink, r, Offset(cx, sh.y - r * 1.5f))
        }

        Demo.JACKS -> {
            val cx = w * 0.62f
            val legLen = h * 0.34f * 0.96f
            val t = h * 0.17f
            val theta = rad(lerp(5f, 28f, p))
            val hip = Offset(cx, g - legLen * cos(theta) - h * 0.03f * p)
            val fl = Offset(hip.x - legLen * sin(theta), hip.y + legLen * cos(theta))
            val fr = Offset(hip.x + legLen * sin(theta), hip.y + legLen * cos(theta))
            seg(ink, hip, fl, limb)
            seg(ink, hip, fr, limb)
            val sh = Offset(cx, hip.y - t)
            seg(accent, hip, sh, torsoW)
            val sw = t * 0.32f
            val sl = Offset(cx - sw, sh.y)
            val sr = Offset(cx + sw, sh.y)
            seg(accent, sl, sr, limb)
            val phi = rad(lerp(12f, 165f, p))
            val armLen = t * 1.05f
            val hl = Offset(sl.x - armLen * sin(phi), sl.y + armLen * cos(phi))
            val hr = Offset(sr.x + armLen * sin(phi), sr.y + armLen * cos(phi))
            seg(ink, sl, hl, limb * 0.85f)
            seg(ink, sr, hr, limb * 0.85f)
            drawCircle(ink, r, Offset(cx, sh.y - r * 1.5f))
        }

        Demo.PUSH_UPS -> {
            // Side view: feet on the left, hands under the shoulders on the right.
            val bodyLen = w * 0.36f
            val sx = w * 0.78f
            val hS = lerp(bodyLen * 0.31f, bodyLen * 0.10f, p) // shoulder height above floor
            val shoulder = Offset(sx, g - hS)
            val feet = Offset(sx - sqrt(bodyLen * bodyLen - hS * hS), g)
            val hip = Offset(lerp(feet.x, shoulder.x, 0.55f), lerp(feet.y, shoulder.y, 0.55f))
            val hand = Offset(sx, g)
            val seg1 = bodyLen * 0.17f
            val elbow = ik(shoulder, hand, seg1, Offset(-1f, 0f))
            seg(ink, feet, hip, limb)
            seg(accent, hip, shoulder, torsoW)
            seg(ink, shoulder, elbow, limb * 0.9f)
            seg(ink, elbow, hand, limb * 0.9f)
            val dx = (shoulder.x - feet.x)
            val dy = (shoulder.y - feet.y)
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            val head = Offset(shoulder.x + dx / len * r * 1.9f, shoulder.y + dy / len * r * 1.9f - r * 0.3f)
            drawCircle(ink, r, head)
        }
    }
}
