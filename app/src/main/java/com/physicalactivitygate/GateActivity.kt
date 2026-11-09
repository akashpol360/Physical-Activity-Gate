package com.physicalactivitygate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.physicalactivitygate.data.*
import com.physicalactivitygate.service.AppAccessAccessibilityService
import com.physicalactivitygate.exercise.*
import kotlinx.coroutines.*
import androidx.concurrent.futures.await
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.Executors
import kotlin.math.abs

class GateActivity : ComponentActivity() {
    companion object { const val EXTRA_PACKAGE = "protected_package" }
    // True only when we are intentionally handing off to the protected app
    // (exercise just completed, or it turned out to already be authorized).
    // Left false for every other way this screen can lose the foreground —
    // Home button/gesture, opening Recents, the task getting swiped away,
    // etc. onUserLeaveHint() below uses it to tell "the user is walking
    // away without finishing the gate" apart from "we ourselves just
    // launched the target app".
    var handedOff = false
    // True while we're waiting for a system permission-request screen
    // (camera or activity-recognition) that we launched to return a
    // result. onUserLeaveHint() below fires for that too, not just
    // Home/Recents — so without this guard, tapping "Allow Camera" (or
    // the activity-recognition prompt for step-based exercises) launched
    // the system permission screen, which immediately fired
    // onUserLeaveHint(), which saw handedOff == false and called
    // goHome() — closing this screen the instant the permission prompt
    // tried to appear, before the user could ever grant it. That's the
    // "clicking Allow Camera closes the app" bug.
    private var awaitingPermissionResult = false
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        awaitingPermissionResult = false
        setContent { GateTheme { GateScreen(this, targetPackage(), cameraDenied = !granted) } }
    }
    private val activityPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        awaitingPermissionResult = false
        setContent { GateTheme { GateScreen(this, targetPackage(), stepPermissionDenied = !granted) } }
    }
    private fun targetPackage() = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
    override fun onBackPressed() { goHome() }

    // Called for an explicit user navigation away from this screen —
    // pressing/gesturing Home, or opening Recents — but, in practice,
    // also for any new activity (including a system permission prompt)
    // being launched on top of us, which is why awaitingPermissionResult
    // is checked here too.
    //
    // Without this: swiping/going Home while the gate is showing just
    // paused the activity instead of finishing it. If that background task
    // was later dismissed (from Recents, or reclaimed by the OS) while the
    // protected app's own task still existed underneath — never having
    // been authorized — nothing forced a fresh check, so the protected app
    // could resurface with no gate at all. Now, the moment the user tries
    // to leave before the gate has actually handed off to the target app,
    // we finish this task and send everything home ourselves, so there is
    // never a background window where the unauthorized app could be left
    // exposed.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!handedOff && !awaitingPermissionResult) goHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        presentGate()
    }

    fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addCategory(Intent.CATEGORY_DEFAULT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(home) }
        finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presentGate()
    }

    private fun presentGate() {
        val pkg = targetPackage(); val repo = Repository(AppDatabase.get(this))
        lifecycleScope.launch {
            val app = repo.app(pkg); val rule = app?.let { repo.rule(it.id) }
            if (app == null || rule == null || !app.isProtected) { finish(); return@launch }
            if (repo.authorized(pkg)) {
                // Already authorized — this trigger is a duplicate/late one
                // (e.g. a second window-state event firing during the app's
                // own launch transition right after Continue). Never force a
                // redo of the activity in that case; just hand off silently.
                handedOff = true
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                finish()
                return@launch
            }
            val settings = repo.getSettings()
            if (rule.exerciseType != "STEPS" && settings.cameraVerification && ContextCompat.checkSelfPermission(this@GateActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                setContent {
                    GateTheme {
                        CameraDisclosure(
                            onAllow = { awaitingPermissionResult = true; cameraPermission.launch(Manifest.permission.CAMERA) },
                            onNotNow = { setContent { GateTheme { GateScreen(this@GateActivity, pkg, cameraDenied = true) } } }
                        )
                    }
                }
            } else {
                if (rule.exerciseType == "STEPS" && ContextCompat.checkSelfPermission(this@GateActivity, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                    awaitingPermissionResult = true
                    activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
                setContent { GateTheme { GateScreen(this@GateActivity, pkg) } }
            }
        }
    }
}

/** Applies the app's real design system (colors + typography) to this
 * Activity's content — previously missing here, so the gate screen was
 * silently falling back to Android's generic default Material3 theme
 * instead of the app's actual palette. */
@Composable private fun GateTheme(content: @Composable () -> Unit) {
    WatercolorTheme(content)
}

@Composable
private fun CameraDisclosure(onAllow: () -> Unit, onNotNow: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Text("Camera verification", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("This feature uses your camera only while you perform an exercise. Pose processing happens on this device. Camera frames are not uploaded or stored by default.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onAllow, Modifier.fillMaxWidth()) { Text("Allow Camera") }
        TextButton(onNotNow) { Text("Not Now") }
    }
}

@Composable private fun GateScreen(activity: GateActivity, packageName: String, cameraDenied: Boolean = false, stepPermissionDenied: Boolean = false) {
    val context = LocalContext.current; val repo = remember { Repository(AppDatabase.get(context)) }
    var app by remember { mutableStateOf<ProtectedAppEntity?>(null) }; var rule by remember { mutableStateOf<ActivityRuleEntity?>(null) }; var settings by remember { mutableStateOf(SettingsEntity()) }
    var started by remember { mutableStateOf(false) }; var completed by remember { mutableStateOf(false) }; var count by remember { mutableStateOf(0) }; var verified by remember { mutableStateOf(true) }; var remaining by remember { mutableStateOf(0L) }; var showBypassConfirm by remember { mutableStateOf(false) }; var bypassBlockedReason by remember { mutableStateOf<String?>(null) }; var blockedReason by remember { mutableStateOf<String?>(null) }; var cameraFailed by remember { mutableStateOf(false) }; var startedAt by remember { mutableStateOf(0L) }; var sessionRecorded by remember { mutableStateOf(false) }
    val tone = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }
    var lastFeedbackCount by remember { mutableStateOf(0) }
    DisposableEffect(Unit) { onDispose { tone.release() } }
    LaunchedEffect(packageName) {
        app = repo.app(packageName); app?.let { rule = repo.rule(it.id) }; settings = repo.getSettings()
        if (settings.maxUnlocksPerDay > 0 && repo.todayUnlocks() >= settings.maxUnlocksPerDay) blockedReason = "Daily access limit reached."
        else if (app != null && repo.inCooldown(app!!.id)) blockedReason = "Cooldown is active. Try again after the cooldown ends."
    }
    val r = rule ?: return
    val a = app ?: return
    val target = if (r.exerciseType == "PLANK") r.durationSeconds.coerceAtLeast(r.targetCount) else r.targetCount
    val authMinutes = r.authorizationMinutes
    LaunchedEffect(count) {
        if (count > lastFeedbackCount && started) {
            // Reps (squats, push-ups, etc.) get feedback every single count.
            // Steps would buzz constantly while walking at that rate, so those
            // are throttled to roughly every 5% of the target, plus always on
            // completion.
            val feedbackStep = if (r.exerciseType == "STEPS") maxOf(1, target / 20) else 1
            val milestoneReached = count >= target || (count - lastFeedbackCount) >= feedbackStep
            if (milestoneReached) {
                if (settings.hapticFeedback) activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (settings.sound) runCatching { tone.startTone(if (count >= target) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 70) }
                lastFeedbackCount = count
            }
        }
    }
    LaunchedEffect(completed) { if (completed && !sessionRecorded) {
        sessionRecorded = true
        repo.recordSession(ActivitySessionEntity(protectedAppId = a.id, exerciseType = r.exerciseType, target = target, completed = true, verified = verified, actualCount = count, startedAt = startedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), completedAt = System.currentTimeMillis()))
    } }
    // The countdown must not be gated by `remaining == 0L` beyond the initial
    // seed — once remaining becomes non-zero that condition flips false,
    // which would remove the ticking LaunchedEffect from composition (and
    // cancel its coroutine) the instant it starts, before it ever ticks.
    LaunchedEffect(completed) {
        if (completed && authMinutes > 0 && remaining == 0L) remaining = authMinutes * 60L
    }
    if (completed && authMinutes > 0) {
        LaunchedEffect(completed) {
            while (remaining > 0) {
                kotlinx.coroutines.delay(1000)
                remaining = (remaining - 1).coerceAtLeast(0)
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (blockedReason != null) {
            Spacer(Modifier.height(48.dp))
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(16.dp))
            Text(blockedReason!!, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Your local access-control settings are preventing this unlock.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button({ activity.goHome() }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        } else if (completed) {
            Spacer(Modifier.height(48.dp))
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text("Great job! 🎉", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("You completed your activity.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("${if (verified) "Verified" else "Self-confirmed"}: $count / $target", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(if (authMinutes < 0) "Unlocked until you leave the app" else "Unlocked for ${formatTime(remaining)}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Button(
                {
                    handOffToApp(
                        activity = activity,
                        repo = repo,
                        packageName = packageName,
                        authMinutes = authMinutes,
                        notifyExpiry = if (settings.notifications && authMinutes > 0) ({ expiresAt ->
                            NotificationHelper.scheduleExpiry(context, packageName, expiresAt)
                        }) else null
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Continue to ${a.appName}") }
            Spacer(Modifier.height(24.dp))
        } else if (!started) {
            Spacer(Modifier.height(40.dp))
            Text("Before you continue…", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(24.dp))
            Icon(exerciseIcon(r.exerciseType), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(20.dp))
            Text("$target", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
            Text(ExerciseType.entries.firstOrNull { it.name == r.exerciseType }?.label ?: r.exerciseType, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                if (r.exerciseType == "STEPS") "Just carry your phone and start walking — steps are counted using your phone's built-in sensor, no camera needed."
                else "Place the phone 2–3 meters away and keep your whole body visible.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.weight(1f))
            Button({ startedAt = System.currentTimeMillis(); started = true }, modifier = Modifier.fillMaxWidth()) { Text("Start Exercise") }
            if (settings.bypassMode != "OFF" && !settings.requireEveryTime) {
                Spacer(Modifier.height(4.dp))
                TextButton({ showBypassConfirm = true }) { Text("Skip for now") }
            }
            TextButton({ activity.goHome() }) { Text("Cancel") }
        } else if (r.exerciseType == "STEPS") {
            Spacer(Modifier.height(24.dp))
            StepActivity(target, settings, stepPermissionDenied, onUnavailable = { verified = false }) { value -> count = value; if (value >= target) completed = true }
            TextButton({ activity.goHome() }) { Text("Cancel") }
        } else if (cameraDenied || cameraFailed || !settings.cameraVerification) {
            LaunchedEffect(Unit) { verified = false }
            Spacer(Modifier.height(48.dp))
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(16.dp))
            Text("Self-confirmed activity", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                if (cameraFailed) "Camera verification could not start. You may continue with an unverified self-confirmation." else "Camera verification is unavailable or disabled. This does not verify that the exercise was physically performed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.weight(1f))
            Button({ count = target; completed = true }, modifier = Modifier.fillMaxWidth()) { Text("I completed it") }
            TextButton({ activity.goHome() }) { Text("Cancel") }
        } else {
            Spacer(Modifier.height(16.dp))
            Text("Keep going!", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(requirementText(r, target), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("$count / $target", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (count.toFloat() / target).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            CameraExerciseView(r.exerciseType, target, onCameraError = { cameraFailed = true }) { value -> count = value; if (value >= target) completed = true }
            Spacer(Modifier.height(8.dp))
            Text("Keep the full body in frame. Repetitions are counted only after the full movement is detected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton({ activity.goHome() }) { Text("Cancel") }
        }
    }
    if (bypassBlockedReason != null) {
        AlertDialog(
            onDismissRequest = { bypassBlockedReason = null },
            title = { Text("Bypass unavailable") },
            text = { Text(bypassBlockedReason!!) },
            confirmButton = { Button({ bypassBlockedReason = null }) { Text("OK") } }
        )
    }
    if (showBypassConfirm) {
        AlertDialog(onDismissRequest = { showBypassConfirm = false }, title = { Text("Emergency bypass") }, text = { Text(if (settings.bypassMode == "ONE_PER_DAY") "You can use one bypass per day. The activity will be marked as self-bypassed." else "This bypass is intended for emergencies. Continue without activity verification?") }, confirmButton = { Button({ activity.lifecycleScope.launch {
                    when {
                        settings.maxUnlocksPerDay > 0 && repo.todayUnlocks() >= settings.maxUnlocksPerDay -> {
                            showBypassConfirm = false
                            bypassBlockedReason = "Daily access limit reached."
                        }
                        repo.inCooldown(a.id) -> {
                            showBypassConfirm = false
                            bypassBlockedReason = "Cooldown is active. Try again after the cooldown ends."
                        }
                        settings.bypassMode == "ONE_PER_DAY" && repo.todayBypasses() >= 1 -> {
                            showBypassConfirm = false
                            bypassBlockedReason = "Today's emergency bypass has already been used."
                        }
                        else -> {
                            repo.recordBypass()
                            handOffToApp(
                                activity = activity,
                                repo = repo,
                                packageName = packageName,
                                authMinutes = 5,
                                // Bypass access is still a timed grant (5 min) and
                                // was missing its expiry notification entirely —
                                // it fell through the `authMinutes > 0` branch on
                                // the normal Continue button but bypass never
                                // passed a notifyExpiry callback at all.
                                notifyExpiry = if (settings.notifications) ({ expiresAt ->
                                    NotificationHelper.scheduleExpiry(context, packageName, expiresAt)
                                }) else null
                            )
                        }
                    }
                } }) { Text("Continue") } }, dismissButton = { TextButton({ showBypassConfirm = false }) { Text("Cancel") } })
    }
}

private fun requirementText(r: ActivityRuleEntity, target: Int) = if (r.exerciseType == "PLANK") "Hold a plank for $target seconds" else "Complete $target ${r.exerciseType.replace('_', ' ').lowercase()}"

/**
 * This hand-off's cleanup step (clearing handOffInProgress after a delay,
 * below) MUST keep running after activity.finish() destroys GateActivity —
 * so it cannot live on activity.lifecycleScope or any other Activity-owned
 * scope, all of which are cancelled by that very finish() call. It has no
 * per-activity state of its own, so one process-wide scope for every
 * hand-off is correct (and avoids leaking a new scope per gate pass).
 */
private val handOffScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * The single, safe hand-off path from GateActivity to the protected app.
 *
 * The root cause of the open/close flicker:
 *   1. repo.authorize(pkg) starts a Room write (async, coroutine)
 *   2. startActivity(protectedApp) fires — the new window appears
 *   3. Accessibility service sees TYPE_WINDOW_STATE_CHANGED immediately
 *   4. Service calls repo.authorized(pkg) — but the write from step 1
 *      hasn't committed to disk yet → returns false
 *   5. Service re-launches GateActivity → flicker loop
 *
 * Fix: set handOffInProgress FIRST (in-memory, instant, no DB round-trip),
 * then await the DB write to completion, THEN launch the app. The service
 * checks the in-memory flag before touching the DB at all, so it can never
 * see "not authorized" during the launch window regardless of timing.
 * The flag auto-clears after HAND_OFF_SUPPRESS_MS once authorization is
 * safely on disk and the app's launch animation is done.
 *
 * That auto-clear is why this runs on [handOffScope] rather than the
 * activity's own lifecycleScope: step 3 below calls activity.finish(),
 * which — once GateActivity's onDestroy() fires, usually within a frame or
 * two — cancels lifecycleScope immediately. A coroutine started on that
 * scope would be killed mid-delay, right before it could clear the flag,
 * leaving handOffInProgress permanently stuck to this exact package name.
 * Since the service's very first check is `if (pkg == handOffInProgress)
 * return`, a stuck flag means that one package silently skips ALL future
 * gating forever — reopening it needs no exercise and ignores whatever the
 * real DB authorization says, until some other protected app happens to
 * overwrite the (single, shared) flag. Running on an independent scope
 * that isn't torn down by the finish() call fixes that at the source.
 */
private fun handOffToApp(
    activity: GateActivity,
    repo: Repository,
    packageName: String,
    authMinutes: Int,
    notifyExpiry: ((Long) -> Unit)? = null
) {
    handOffScope.launch {
        // 1. Mark the hand-off BEFORE any async work so the service sees it
        //    on its very next event, even if that fires before this coroutine
        //    completes the next line.
        AppAccessAccessibilityService.handOffInProgress = packageName

        // 2. Wait for the DB write to actually complete (suspend, not fire-and-forget).
        NotificationHelper.cancelExpiry(activity, packageName)
        val expiresAt = repo.authorize(packageName, authMinutes)
        if (authMinutes > 0) notifyExpiry?.invoke(expiresAt)

        // 3. Now launch the protected app — authorization is committed on disk
        //    AND the in-memory flag is set, so the service is doubly safe.
        withContext(Dispatchers.Main) {
            activity.handedOff = true
            activity.packageManager.getLaunchIntentForPackage(packageName)
                ?.let { activity.startActivity(it) }
            activity.finish()
        }

        // 4. Clear the flag after the suppress window once the launch animation
        //    is done. Even if the service fires multiple events during the
        //    animation, all of them will see the authorized DB row now.
        delay(AppAccessAccessibilityService.HAND_OFF_SUPPRESS_MS)
        if (AppAccessAccessibilityService.handOffInProgress == packageName) {
            AppAccessAccessibilityService.handOffInProgress = null
        }
    }
}
private fun formatTime(seconds: Long) = "%02d:%02d".format(seconds / 60, seconds % 60)
private fun readyPoseHint(exercise: String) = when (exercise) {
    "SQUATS" -> "Stand up straight,\nfacing the camera"
    "PUSH_UPS" -> "Get into push-up position,\nside-on to the camera"
    "SIT_UPS" -> "Lie back with knees bent,\nside-on to the camera"
    "JUMPING_JACKS" -> "Stand with feet together,\narms at your sides"
    else -> "Get in position…"
}

@Composable private fun StepActivity(target: Int, settings: SettingsEntity, permissionDenied: Boolean, onUnavailable: () -> Unit, onProgress: (Int) -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(0) }
    var available by remember { mutableStateOf(true) }
    var elapsedSeconds by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // TYPE_STEP_DETECTOR fires exactly once per real step taken, with no
        // cumulative "total since reboot" value involved — so unlike
        // TYPE_STEP_COUNTER there is no baseline subtraction that can glitch
        // and show a huge instant jump when the screen opens. Only fall back
        // to the counter (with a warm-up window) on the rare device that
        // lacks a step detector but has a counter.
        val detectorSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val counterSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val sensor = detectorSensor ?: counterSensor
        if (sensor == null || ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) available = false

        var counterBaseline: Long? = null
        val warmupUntil = System.currentTimeMillis() + 700

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                    // The step-detector sensor can fire one spurious event
                    // right at activation — either a hardware calibration
                    // artifact or just detecting the motion of the phone
                    // being picked up/held, before any real walking starts.
                    // Ignore events in a brief warm-up window so that doesn't
                    // count as a step.
                    if (System.currentTimeMillis() < warmupUntil) return
                    current += 1
                    onProgress(current)
                    return
                }
                // TYPE_STEP_COUNTER fallback path only.
                val total = e.values.firstOrNull()?.toLong() ?: return
                if (System.currentTimeMillis() < warmupUntil) { counterBaseline = total; return }
                if (counterBaseline == null) counterBaseline = total
                current = (total - (counterBaseline ?: total)).toInt().coerceAtLeast(0)
                onProgress(current)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (sensor != null && available) {
            runCatching { sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST) }.onFailure { available = false }
        }
        onDispose { sm.unregisterListener(listener) }
    }

    if (!available || permissionDenied) {
        LaunchedEffect(Unit) { onUnavailable() }
        Icon(Icons.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(8.dp))
        Text("Step verification is unavailable on this device or permission was denied.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("You may self-confirm this activity, but it will be recorded as unverified.", style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button({ onProgress(target) }) { Text("I completed the steps") }
        return
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            elapsedSeconds++
        }
    }

    val progress = (current.toFloat() / target.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
    val remainingSteps = (target - current).coerceAtLeast(0)
    val pace = if (elapsedSeconds >= 10) (current * 60f / elapsedSeconds).toInt() else null

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Walk to unlock — no camera needed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
            CircularProgressIndicator(
                progress = { 1f },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 12.dp,
                color = MaterialTheme.colorScheme.outline
            )
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 12.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.DirectionsWalk, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(4.dp))
                Text("$current", style = MaterialTheme.typography.headlineLarge)
                Text("/ $target steps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (remainingSteps > 0) "$remainingSteps steps to go" else "Target reached! 🎉",
            style = MaterialTheme.typography.titleMedium
        )
        pace?.let {
            Spacer(Modifier.height(4.dp))
            Text("~$it steps/min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Carry your phone and walk normally. Steps are counted by the built-in sensor — ideal for the office, no space needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable private fun CameraExerciseView(exercise: String, target: Int, onCameraError: () -> Unit, onProgress: (Int) -> Unit) {
    val context = LocalContext.current; val lifecycleOwner = LocalLifecycleOwner.current; val previewView = remember { PreviewView(context) }; val squat = remember { SquatDetector() }; val push = remember { PushUpDetector() }; val jack = remember { JumpingJackDetector() }; val plank = remember { PlankDetector() }; val sit = remember { SitUpDetector() }; val executor = remember { Executors.newSingleThreadExecutor() }
    val readiness = remember { PoseReadinessGate() }
    var overlayPose by remember { mutableStateOf<Pose?>(null) }
    var imageWidth by remember { mutableStateOf(0) }
    var imageHeight by remember { mutableStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    var readyProgress by remember { mutableStateOf(0f) }

    // Use the CameraX 1.4.x coroutine API (awaitInstance) instead of the
    // old ListenableFuture-based getInstance which caused the compile error.
    LaunchedEffect(Unit) {
        var poseDetector: com.google.mlkit.vision.pose.PoseDetector? = null
        try {
            val provider = ProcessCameraProvider.getInstance(context).await()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val options = AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
                .build()
            poseDetector = PoseDetection.getClient(options)
            analyzer.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                val rotation = proxy.imageInfo.rotationDegrees
                poseDetector.process(InputImage.fromMediaImage(media, rotation))
                    .addOnSuccessListener { pose ->
                        val rotated = rotation == 90 || rotation == 270
                        imageWidth = if (rotated) media.height else media.width
                        imageHeight = if (rotated) media.width else media.height
                        overlayPose = pose
                        when (exercise) {
                            "SQUATS" -> {
                                val angle = squatAngle(pose)
                                val isArmed = readiness.update(angle != null && angle >= 145f)
                                armed = isArmed; readyProgress = readiness.progress
                                if (isArmed && angle != null) onProgress(squat.update(angle))
                            }
                            "PUSH_UPS" -> {
                                val angle = pushAngle(pose)
                                val isArmed = readiness.update(angle != null && angle >= 145f)
                                armed = isArmed; readyProgress = readiness.progress
                                if (isArmed && angle != null) onProgress(push.update(angle))
                            }
                            "SIT_UPS" -> {
                                val angle = sitAngle(pose)
                                val isArmed = readiness.update(angle != null && angle >= 145f)
                                armed = isArmed; readyProgress = readiness.progress
                                if (isArmed && angle != null) onProgress(sit.update(angle))
                            }
                            "JUMPING_JACKS" -> {
                                val signals = jackSignals(pose)
                                val isArmed = readiness.update(signals != null && !signals.first && !signals.second)
                                armed = isArmed; readyProgress = readiness.progress
                                if (isArmed && signals != null) onProgress(jack.update(signals.first, signals.second))
                            }
                            "PLANK" -> {
                                armed = true; readyProgress = 1f
                                onProgress(plank.update(plankValid(pose)))
                            }
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analyzer)
            }.onFailure {
                analyzer.clearAnalyzer()
                poseDetector.close()
                onCameraError()
            }
        } catch (e: Exception) {
            poseDetector?.close()
            onCameraError()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdownNow()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(390.dp)) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            PoseSkeletonOverlay(overlayPose, imageWidth, imageHeight, mirror = true, armed = armed, modifier = Modifier.fillMaxSize())
            if (!armed) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(readyPoseHint(exercise), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { readyProgress }, modifier = Modifier.width(140.dp).height(4.dp).clip(RoundedCornerShape(2.dp)), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (armed)
                "Locked on — repetitions are being counted."
            else
                "Hold your starting position steadily until it locks on — this stops setup movements from being counted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun landmark(p: Pose, type: Int): com.google.mlkit.vision.pose.PoseLandmark? = p.getPoseLandmark(type)?.takeIf { it.inFrameLikelihood >= 0.72f }
private fun point3d(l: com.google.mlkit.vision.pose.PoseLandmark?) = l?.position3D
private fun best(p: Pose, left: Int, right: Int) = point3d(landmark(p, left) ?: landmark(p, right))

// Every angle function below requires the FULL relevant kinetic chain to be
// visible at once — not just the two or three joints the angle math uses —
// before it will report anything at all. This is what stops an isolated hand
// or leg movement (e.g. holding the phone, gesturing) from swinging the
// tracked angle through a full range and faking a rep: a stray limb alone can
// never satisfy "shoulder AND elbow AND wrist AND hip AND knee all in frame
// together", which a genuine push-up naturally does.
//
// These return null — not a fallback number — when the chain isn't visible,
// so a frame with missing landmarks is correctly treated as "no data" and
// never misread as a value that happens to look like "standing straight" or
// "lying flat".
private fun squatAngle(p: Pose): Float? {
    val h = best(p, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP) ?: return null
    val k = best(p, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE) ?: return null
    val a = best(p, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE) ?: return null
    best(p, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER) ?: return null // full torso must be in frame too
    return angleDegrees(Point(h.x, h.y), Point(k.x, k.y), Point(a.x, a.y))
}
private fun pushAngle(p: Pose): Float? {
    val s = best(p, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER) ?: return null
    val e = best(p, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW) ?: return null
    val w = best(p, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST) ?: return null
    val hip = best(p, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP) ?: return null
    best(p, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE) ?: return null
    // A push-up body is roughly level (shoulders and hips at a similar height,
    // side-on to the camera) rather than stacked vertically like a standing
    // or seated person waving an arm — reject anything too "upright".
    val verticalGap = kotlin.math.abs(s.y - hip.y)
    val horizontalGap = kotlin.math.abs(s.x - hip.x)
    if (verticalGap > horizontalGap * 1.4f) return null
    return angleDegrees(Point(s.x, s.y), Point(e.x, e.y), Point(w.x, w.y))
}
private fun sitAngle(p: Pose): Float? {
    val s = best(p, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER) ?: return null
    val h = best(p, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP) ?: return null
    val k = best(p, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE) ?: return null
    best(p, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE) ?: return null // full leg must be in frame too
    return angleDegrees(Point(s.x, s.y), Point(h.x, h.y), Point(k.x, k.y))
}
private fun plankValid(p: Pose): Boolean {
    val s = best(p, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER) ?: return false
    val h = best(p, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP) ?: return false
    val a = best(p, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE) ?: return false
    return angleDegrees(Point(s.x, s.y), Point(h.x, h.y), Point(a.x, a.y)) > 155f
}
private fun jackSignals(p: Pose): Pair<Boolean, Boolean>? {
    val ls = landmark(p, PoseLandmark.LEFT_SHOULDER) ?: return null
    val rs = landmark(p, PoseLandmark.RIGHT_SHOULDER) ?: return null
    val lh = landmark(p, PoseLandmark.LEFT_HIP) ?: return null
    val rh = landmark(p, PoseLandmark.RIGHT_HIP) ?: return null
    val lw = landmark(p, PoseLandmark.LEFT_WRIST) ?: return null
    val rw = landmark(p, PoseLandmark.RIGHT_WRIST) ?: return null
    val la = landmark(p, PoseLandmark.LEFT_ANKLE) ?: return null
    val ra = landmark(p, PoseLandmark.RIGHT_ANKLE) ?: return null
    val shoulderY = (ls.position3D.y + rs.position3D.y) / 2f
    val wristY = (lw.position3D.y + rw.position3D.y) / 2f
    val hipWidth = abs(lh.position3D.x - rh.position3D.x)
    return (wristY < shoulderY) to (abs(la.position3D.x - ra.position3D.x) > hipWidth * 1.5f)
}

/**
 * Requires the person to hold the exercise's neutral/starting pose for a
 * cumulative [requiredHoldMs] before rep-counting is allowed to begin, so
 * setup fidgeting can't be mistaken for a rep. Unlike a simple "unbroken
 * streak" timer, this accumulates progress and only lets it decay after a
 * short grace period of genuinely broken pose — a normal frame-to-frame
 * jitter in the pose estimate (a degree or two of noise, or one bad frame)
 * does not reset it back to zero, which an unbroken-streak version would.
 * Once armed for a session it stays armed, so it never interrupts real reps.
 */
private class PoseReadinessGate(private val requiredHoldMs: Long = 900L, private val graceMs: Long = 350L) {
    private var accumulatedMs = 0L
    private var lastFrameMs = 0L
    private var notReadySinceMs = 0L
    var armed = false; private set
    var progress = 0f; private set

    fun update(isReadyPose: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        if (armed) { progress = 1f; return true }
        val delta = if (lastFrameMs == 0L) 0L else (now - lastFrameMs).coerceIn(0, 200)
        lastFrameMs = now

        if (isReadyPose) {
            accumulatedMs += delta
            notReadySinceMs = 0L
        } else {
            if (notReadySinceMs == 0L) notReadySinceMs = now
            // Only start losing progress once the pose has been genuinely
            // broken for longer than the grace window — a single noisy frame
            // never costs any progress at all.
            if (now - notReadySinceMs > graceMs) {
                accumulatedMs = (accumulatedMs - delta * 2).coerceAtLeast(0)
            }
        }

        progress = (accumulatedMs.toFloat() / requiredHoldMs).coerceIn(0f, 1f)
        if (accumulatedMs >= requiredHoldMs) armed = true
        return armed
    }
}

/** Joint pairs drawn as skeleton lines over the camera preview. */
private val POSE_CONNECTIONS = listOf(
    PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
    PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
    PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
    PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
    PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
    PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
    PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
    PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
    PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
    PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
    PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
    PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE
)

/**
 * Draws a live skeleton (joint dots + connecting lines) over the camera
 * preview so the person can see exactly what the app is tracking — if the
 * lines look broken or absent, that's an immediate visual cue to reposition
 * rather than wondering why reps aren't counting. The lines turn from amber
 * (still calibrating / not yet locked on) to green (locked on, reps are
 * being counted) so lock-on status is visible at a glance, not just in text.
 */
@Composable private fun PoseSkeletonOverlay(pose: Pose?, imageWidth: Int, imageHeight: Int, mirror: Boolean, armed: Boolean, modifier: Modifier = Modifier) {
    if (pose == null || imageWidth <= 0 || imageHeight <= 0) return
    val lineColor = if (armed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val jointColor = if (armed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    androidx.compose.foundation.Canvas(modifier) {
        // PreviewView defaults to center-crop "fill" scaling — mirror that
        // math here so the skeleton lines land on the right spot.
        val scale = maxOf(size.width / imageWidth, size.height / imageHeight)
        val offsetX = (size.width - imageWidth * scale) / 2f
        val offsetY = (size.height - imageHeight * scale) / 2f

        fun toCanvas(x: Float, y: Float): androidx.compose.ui.geometry.Offset {
            val px = if (mirror) imageWidth - x else x
            return androidx.compose.ui.geometry.Offset(px * scale + offsetX, y * scale + offsetY)
        }

        POSE_CONNECTIONS.forEach { (a, b) ->
            val la = pose.getPoseLandmark(a); val lb = pose.getPoseLandmark(b)
            if (la != null && lb != null && la.inFrameLikelihood > 0.5f && lb.inFrameLikelihood > 0.5f) {
                drawLine(lineColor, toCanvas(la.position.x, la.position.y), toCanvas(lb.position.x, lb.position.y), strokeWidth = 7f)
            }
        }
        pose.allPoseLandmarks.forEach { lm ->
            if (lm.inFrameLikelihood > 0.5f) drawCircle(jointColor, radius = 8f, center = toCanvas(lm.position.x, lm.position.y))
        }
    }
}

