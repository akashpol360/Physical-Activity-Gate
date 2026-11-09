package com.physicalactivitygate

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageView
import android.provider.Settings
import android.Manifest
import androidx.core.app.ActivityCompat
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.physicalactivitygate.billing.BillingManager
import com.physicalactivitygate.billing.PRO_MONTHLY_ID
import com.physicalactivitygate.billing.PRO_YEARLY_ID
import com.physicalactivitygate.data.*
import com.physicalactivitygate.exercise.ExerciseType
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.stateIn

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialise AdMob once on app start. The app's own functionality
        // (exercise detection, blocking, local DB) never uses the network —
        // only AdMob does, and only for free users.
        initAdMob(this)
        setContent { GateApp(this) }
    }
}

class GateViewModel(private val repo: Repository) : ViewModel() {
    val apps = repo.apps().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = repo.history().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())
    val statistics = repo.statistics().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())
    val achievements = repo.achievements().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = repo.settings().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), SettingsEntity())
    // Guards the free-app-limit check-then-write below so rapid taps across
    // multiple switches can't all read the same pre-write count and all pass.
    private val protectMutex = kotlinx.coroutines.sync.Mutex()
    fun toggleApp(pkg: String, name: String, enabled: Boolean, onBlockedByFreeLimit: () -> Unit = {}) = viewModelScope.launch {
        protectMutex.withLock {
            val old = repo.app(pkg)
            val wasProtected = old?.isProtected == true
            // The free-tier check must run any time protection is being
            // turned ON for an app that isn't currently protected — whether
            // it's a brand-new app or one that was toggled off before.
            // Checking only "old == null" let a previously-added app skip
            // the check entirely on re-enable, silently exceeding the limit.
            if (enabled && !wasProtected) {
                val currentSettings = repo.getSettings()
                val protectedCount = repo.protectedCount()
                if (!currentSettings.isProActive && protectedCount >= FREE_APP_LIMIT) {
                    onBlockedByFreeLimit()
                    return@withLock
                }
            }
            // Always upsert using the existing row's id when one is found,
            // never a fresh id=0 entity for an app that might already have a
            // row. Passing id=0 when a row already exists forces SQLite's
            // unique-index conflict resolution to DELETE the old row and
            // insert a new one with a different id — and because
            // activity_sessions used to cascade-delete on that id, this
            // silently wiped that app's entire session history while
            // daily_statistics (a separate, unrelated table) kept
            // accumulating normally. Reusing the real id makes this an
            // in-place update instead, so it can never happen this way again.
            val id = repo.saveApp(ProtectedAppEntity(
                id = old?.id ?: 0,
                packageName = pkg,
                appName = name,
                isProtected = enabled,
                createdAt = old?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            ))
            if (old == null) {
                val settings = repo.getSettings()
                repo.saveRule(ActivityRuleEntity(
                    protectedAppId = id,
                    exerciseType = settings.defaultExercise,
                    targetCount = settings.defaultTarget.coerceAtLeast(1),
                    durationSeconds = if (settings.defaultExercise == "PLANK") settings.defaultTarget.coerceAtLeast(1) else 0,
                    authorizationMinutes = if (settings.authorizationMinutes < 0) -1 else settings.authorizationMinutes.coerceAtLeast(1)
                ))
            }
            if (!enabled) repo.revoke(pkg)
        }
    }
    fun toggleDevPro() = viewModelScope.launch {
        val s = repo.getSettings()
        repo.saveSettings(s.copy(devProOverride = !s.devProOverride))
    }
    suspend fun getRule(appId: Long) = repo.rule(appId)
    fun saveRule(app: ProtectedAppEntity, exercise: String, target: Int, auth: Int) = viewModelScope.launch {
        repo.saveRule(ActivityRuleEntity(protectedAppId = app.id, exerciseType = exercise, targetCount = target.coerceAtLeast(1), durationSeconds = if (exercise == "PLANK") target else 0, authorizationMinutes = if (auth < 0) -1 else auth.coerceAtLeast(1)))
    }
    fun saveSettings(s: SettingsEntity) = viewModelScope.launch { repo.saveSettings(s) }
    fun setPin(pin: String?) = viewModelScope.launch { repo.setPin(pin) }
    fun settingsSnapshot() = viewModelScope.launch { repo.getSettings() }
    suspend fun repositoryCheckPin(pin: String): Boolean = repo.checkPin(pin)
}

private fun currentStreak(days: List<DailyStatisticsEntity>): Int {
    val active = days.filter { it.exercisesCompleted > 0 }.map { it.dateKey }.toSet()
    var cursor = java.time.LocalDate.now(); var streak = 0
    while (active.contains(cursor.toString())) { streak++; cursor = cursor.minusDays(1) }
    return streak
}

/** Free tier: protect up to this many apps. Pro: unlimited. */
private const val FREE_APP_LIMIT = 3

/** True if Pro features should be unlocked — either a real active subscription, or the local developer test override. */
val SettingsEntity.isProActive: Boolean get() = isPro || devProOverride

@Composable fun GateApp(activity: ComponentActivity) {
    val db = remember { AppDatabase.get(activity) }
    val vm: GateViewModel = viewModel(factory = object : ViewModelProvider.Factory { override fun <T : ViewModel> create(c: Class<T>): T = GateViewModel(Repository(db)) as T })
    val nav = rememberNavController(); var onboarding by remember { mutableStateOf(!activity.getPreferences(0).getBoolean("onboarding_complete", false)) }
    WatercolorTheme {
        if (onboarding) { GuideScreen(activity, firstRun = true) { activity.getPreferences(0).edit().putBoolean("onboarding_complete", true).apply(); onboarding = false } }
        else {
            val backStackEntry by nav.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            val bottomNavRoutes = setOf("home", "apps", "activity", "settings")
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    if (currentRoute in bottomNavRoutes) {
                        val settings by vm.settings.collectAsState()
                        Column {
                            BannerAd(isPro = settings.isProActive)
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)) {
                                NavigationBarItem(
                                    selected = currentRoute == "home",
                                    onClick = { nav.navigate("home") { launchSingleTop = true; popUpTo("home") } },
                                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                                    label = { Text("Home") }
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "apps",
                                    onClick = { nav.navigate("apps") { launchSingleTop = true; popUpTo("home") } },
                                    icon = { Icon(Icons.Filled.Apps, contentDescription = "Protected Apps") },
                                    label = { Text("Apps") }
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "activity",
                                    onClick = { nav.navigate("activity") { launchSingleTop = true; popUpTo("home") } },
                                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = "Activity") },
                                    label = { Text("Activity") }
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "settings",
                                    onClick = { nav.navigate("settings") { launchSingleTop = true; popUpTo("home") } },
                                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                                    label = { Text("Settings") }
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    NavHost(nav, "home") {
                        composable("home") { Home(vm, nav) }; composable("apps") { Apps(vm, activity, nav) }; composable("activity") { ActivityScreen(vm, activity) }
                        composable("history") { History(vm) }; composable("stats") { Statistics(vm, activity, nav) }; composable("settings") { SettingsScreen(vm, activity, nav) }
                        composable("subscription") { SubscriptionScreen(vm, activity) }
                        composable("guide") { GuideScreen(activity, firstRun = false) { nav.popBackStack() } }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Shell(title: String, nav: NavHostController? = null, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            if (title.isNotBlank()) {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { if (nav != null) IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        },
        containerColor = Color.Transparent
    ) { p -> Column(Modifier.padding(p).padding(horizontal = 20.dp).fillMaxSize(), content = content) }
}

/** Soft, rounded cards keep the dashboard friendly and tactile. */
@Composable private fun PagCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.8f))
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

private data class StatItem(val label: String, val value: String, val icon: ImageVector)

/** A calm summary row of a few key numbers — no color-coded tiles, just clear numbers. */
@Composable private fun SummaryRow(items: List<StatItem>) {
    PagCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(item.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(item.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                if (index != items.lastIndex) Spacer(Modifier.width(8.dp))
            }
        }
    }
}

/**
 * Android never lets an app silently detect+block another app without the
 * Accessibility Service being manually switched on in system Settings. This
 * banner makes that state visible everywhere it matters, so "the protected
 * app just opens" is never a silent, confusing failure.
 */
@Composable
fun AccessibilityStatusBanner() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(AccessibilityUtils.isServiceEnabled(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = AccessibilityUtils.isServiceEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (enabled) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (enabled) "Blocking is active" else "Blocking is off",
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (enabled)
                        "Protected apps are gated automatically."
                    else
                        "Turn on Physical Activity Gate under Accessibility Settings to enable blocking. This can only be done manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
                if (!enabled) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Enable Now")
                    }
                }
            }
        }
    }
}

@Composable fun Home(vm: GateViewModel, nav: NavHostController) {
    val apps by vm.apps.collectAsState(); val stats by vm.statistics.collectAsState(); val settings by vm.settings.collectAsState()
    // stats.firstOrNull() is just the most recent row — on a fresh day where
    // nothing has happened yet, that row is still *yesterday's*, so it must
    // only be used when its date actually matches today, or the dashboard
    // would show stale numbers under "Today's Activity".
    val todayKey = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) }
    val today = stats.firstOrNull()?.takeIf { it.dateKey == todayKey }
    val protectedApps = apps.filter { it.isProtected }
    val streak = currentStreak(stats)

    Shell("") {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(20.dp))
            Text("Stay Active", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
            Text("Move a little before you scroll.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Tiny moves, big wins!", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(5.dp))
                        Text("Your apps cheer you on after every activity.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("🏃‍♀️", style = MaterialTheme.typography.headlineLarge)
                }
            }
            Spacer(Modifier.height(18.dp))

            AccessibilityStatusBanner()

            PagCard(Modifier.fillMaxWidth().clickable { nav.navigate("stats") }) {
                Text("Today's Activity", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${today?.exercisesCompleted ?: 0} exercise${if ((today?.exercisesCompleted ?: 0) == 1) "" else "s"} completed",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (streak > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("$streak day streak", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Protected Apps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton({ nav.navigate("apps") }) { Text("Manage") }
            }
            Spacer(Modifier.height(8.dp))
            if (protectedApps.isEmpty()) {
                PagCard(Modifier.fillMaxWidth().clickable { nav.navigate("apps") }) {
                    Text("No apps protected yet", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Choose an app to require activity before opening it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                PagCard(Modifier.fillMaxWidth()) {
                    protectedApps.forEachIndexed { index, app ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(app.appName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                        if (index != protectedApps.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { nav.navigate("subscription") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    if (settings.isProActive) Icons.Filled.CheckCircle else Icons.Filled.WorkspacePremium,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (settings.isProActive) "You're on Pro" else "Upgrade to Pro")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable fun Apps(vm: GateViewModel, activity: ComponentActivity, nav: NavHostController) {
    val protected by vm.apps.collectAsState(); val settings by vm.settings.collectAsState(); val pm = activity.packageManager
    var unlocked by remember { mutableStateOf(settings.pinHash == null || !settings.protectSettings) }
    var pin by remember { mutableStateOf("") }
    var upgradeReason by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Scanning every installed app (queryIntentActivities) can take a
    // noticeable moment on a device with a lot of apps. Doing it inside
    // `remember { }` ran it synchronously on the very first frame of this
    // screen's composition, so switching to this tab visibly stuttered —
    // the whole tab-switch animation had to wait for the scan to finish.
    // Loading it in a background coroutine instead lets the tab switch
    // itself render instantly; the list simply fills in a beat later.
    var installed by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var installedLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.Default) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != activity.packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.packageName.contains("youtube", true)) }
        }
        installed = list
        installedLoaded = true
    }
    var selected by remember { mutableStateOf<ProtectedAppEntity?>(null) }; var query by remember { mutableStateOf("") }; var recentFirst by remember { mutableStateOf(false) }
    // This used to run again on every recomposition (not just when its
    // inputs changed), including every frame of the tab-switch crossfade
    // animation — and each pass calls PackageManager for every installed
    // app (getApplicationLabel/getPackageInfo are binder calls, not free).
    // That's what made switching into this tab visibly stutter instead of
    // sliding in smoothly. Keying it to remember() so it only recomputes
    // when the installed list, search text, or sort order actually change
    // fixes that without changing what's shown.
    val filtered = remember(installed, query, recentFirst) {
        installed.filter { pm.getApplicationLabel(it).toString().contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
            .let { list -> if (recentFirst) list.sortedByDescending { pm.getPackageInfo(it.packageName, 0).firstInstallTime } else list.sortedBy { pm.getApplicationLabel(it).toString().lowercase() } }
    }
    Shell("Protected Apps") {
        if (!unlocked) {
            Spacer(Modifier.height(20.dp))
            Text("Protected app changes are locked.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button({ scope.launch { if (vm.repositoryCheckPin(pin)) { unlocked = true; pin = "" } } }, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
        } else {
        Spacer(Modifier.height(12.dp))
        if (!settings.isProActive) {
            Text("${protected.count { it.isProtected }} of $FREE_APP_LIMIT free apps used", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(
            query, { query = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            shape = RoundedCornerShape(14.dp)
        )
        Spacer(Modifier.height(8.dp))
        TextButton({ recentFirst = !recentFirst }) { Text(if (recentFirst) "Sort: Recently installed" else "Sort: Name") }
        Spacer(Modifier.height(4.dp))
        if (!installedLoaded) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        LazyColumn {
            items(filtered, key = { it.packageName }) { info ->
                val old = protected.firstOrNull { it.packageName == info.packageName }
                val name = pm.getApplicationLabel(info).toString()
                val isOn = old?.isProtected == true
                PagCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            AndroidView(
                                factory = { context -> ImageView(context) },
                                modifier = Modifier.size(32.dp),
                                update = { imageView ->
                                    imageView.setImageDrawable(info.loadIcon(pm))
                                }
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            if (isOn) {
                                Spacer(Modifier.height(2.dp))
                                TextButton({ selected = old }, contentPadding = PaddingValues(0.dp)) {
                                    Text("Edit activity rule", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Switch(isOn, { vm.toggleApp(info.packageName, name, it) { upgradeReason = "Protecting more than $FREE_APP_LIMIT apps" } })
                    }
                }
            }
        }
        }
    }
    if (unlocked) selected?.let { app -> RuleDialog(app, vm, settings, activity, onUpgradeNeeded = { reason -> selected = null; upgradeReason = reason }) { selected = null } }
    upgradeReason?.let { reason ->
        ProUpgradeDialog(reason, onDismiss = { upgradeReason = null }, onUpgrade = { upgradeReason = null; nav.navigate("subscription") })
    }
}

@Composable private fun ProUpgradeDialog(featureName: String, onDismiss: () -> Unit, onUpgrade: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.WorkspacePremium, contentDescription = null) },
        title = { Text("Pro feature") },
        text = { Text("$featureName is part of Physical Activity Gate Pro.") },
        confirmButton = { Button(onUpgrade) { Text("View Plans") } },
        dismissButton = { TextButton(onDismiss) { Text("Not now") } }
    )
}

fun exerciseIcon(type: String): ImageVector = when (type) {
    "SQUATS" -> Icons.Filled.FitnessCenter
    "PUSH_UPS" -> Icons.Filled.FitnessCenter
    "JUMPING_JACKS" -> Icons.Filled.Accessibility
    "PLANK" -> Icons.Filled.Timer
    "STEPS" -> Icons.Filled.DirectionsWalk
    "SIT_UPS" -> Icons.Filled.FitnessCenter
    else -> Icons.Filled.FitnessCenter
}
private fun exerciseTagline(type: String): String = when (type) {
    "SQUATS" -> "Classic lower-body exercise"
    "PUSH_UPS" -> "Upper-body strength, no equipment"
    "JUMPING_JACKS" -> "Quick full-body cardio"
    "PLANK" -> "Timed core hold"
    "STEPS" -> "Just walk — no camera needed"
    "SIT_UPS" -> "Core strength exercise"
    else -> ""
}

@Composable fun RuleDialog(app: ProtectedAppEntity, vm: GateViewModel, settings: SettingsEntity, activity: ComponentActivity, onUpgradeNeeded: (String) -> Unit, onDismiss: () -> Unit) {
    val options = ExerciseType.entries.map { it.name to it.label }; var exercise by remember { mutableStateOf("SQUATS") }; var target by remember { mutableStateOf("10") }; var auth by remember { mutableStateOf("15") }
    val proExercises = setOf("JUMPING_JACKS", "SIT_UPS", "PLANK")
    val interstitial = remember { InterstitialAdHelper(activity).also { if (!settings.isProActive) it.load() } }
    LaunchedEffect(app.id) { vm.getRule(app.id)?.let { rule -> exercise = rule.exerciseType; target = if (rule.exerciseType == "PLANK") rule.durationSeconds.toString() else rule.targetCount.toString(); auth = rule.authorizationMinutes.toString() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rule for ${app.appName}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Activity", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (key, label) ->
                    val locked = key in proExercises && !settings.isProActive
                    val isSelected = exercise == key && !locked
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { if (locked) onUpgradeNeeded(label) else exercise = key },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                exerciseIcon(key),
                                contentDescription = null,
                                tint = if (locked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyLarge, color = if (locked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else Color.Unspecified)
                                Text(exerciseTagline(key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (locked) {
                                Icon(Icons.Filled.WorkspacePremium, contentDescription = "Pro", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            } else if (isSelected) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(target, { target = it.filter(Char::isDigit) }, label = { Text(if (exercise == "PLANK") "Seconds" else "Quantity") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(16.dp))
            Text("Authorization", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("5" to "5 minutes", "10" to "10 minutes", "15" to "15 minutes", "30" to "30 minutes", "60" to "1 hour", "-1" to "Until app is closed").forEach { (value, label) ->
                        val locked = value == "-1" && !settings.isProActive
                        Row(
                            Modifier.fillMaxWidth().clickable { if (locked) onUpgradeNeeded("\"Until app is closed\" authorization") else auth = value },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = auth == value, onClick = null, enabled = !locked)
                            Spacer(Modifier.width(4.dp))
                            Text(label, color = if (locked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else Color.Unspecified)
                            if (locked) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Filled.WorkspacePremium, contentDescription = "Pro", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = {
        Button({
            if (settings.isProActive) {
                vm.saveRule(app, exercise, target.toIntOrNull() ?: 10, auth.toIntOrNull() ?: 15); onDismiss()
            } else {
                interstitial.show(activity) {
                    vm.saveRule(app, exercise, target.toIntOrNull() ?: 10, auth.toIntOrNull() ?: 15); onDismiss()
                }
            }
        }) { Text("Save") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable fun ActivityScreen(vm: GateViewModel, activity: ComponentActivity) {
    val apps by vm.apps.collectAsState()
    val protected = apps.filter { it.isProtected }
    Shell("Start Activity") {
        Text("Run the configured activity check for a protected app.")
        Spacer(Modifier.height(8.dp))
        if (protected.isEmpty()) {
            Text("No protected apps yet. Add one from Protected Apps.")
        } else {
            LazyColumn {
                items(protected) { app ->
                    ListItem(
                        headlineContent = { Text(app.appName) },
                        supportingContent = { Text(app.packageName) },
                        trailingContent = {
                            Button({
                                activity.startActivity(Intent(activity, GateActivity::class.java).putExtra(GateActivity.EXTRA_PACKAGE, app.packageName))
                            }) { Text("Start") }
                        }
                    )
                }
            }
        }
    }
}

private val historyDateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
private fun historyDate(ts: Long): String = runCatching { formatFullDate(historyDateFmt.format(java.util.Date(ts))) }.getOrDefault("")

@Composable fun History(vm: GateViewModel) {
    val h by vm.history.collectAsState()
    Shell("Activity History") {
        if (h.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("No sessions yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn {
                items(h, key = { it.id }) { s ->
                    PagCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(exerciseLabel(s.exerciseType), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${s.actualCount}/${s.target} • ${if (s.completed) "Verified" else "Incomplete"} • ${historyDate(s.startedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun exerciseLabel(type: String) = ExerciseType.entries.firstOrNull { it.name == type }?.label ?: type.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }

@Composable fun Statistics(vm: GateViewModel, activity: ComponentActivity, nav: NavHostController) {
    val s by vm.statistics.collectAsState(); val a by vm.achievements.collectAsState(); val apps by vm.apps.collectAsState()
    val settings by vm.settings.collectAsState()
    var upgradeReason by remember { mutableStateOf<String?>(null) }
    val week = s.take(7); val month = s.take(30)
    Shell("Statistics") {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {

            Text("This week", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (week.any { it.repetitions > 0 || it.sessions > 0 }) {
                SummaryRow(
                    listOf(
                        StatItem("Repetitions", week.sumOf { it.repetitions }.toString(), Icons.Filled.Repeat),
                        StatItem("Sessions", week.sumOf { it.sessions }.toString(), Icons.Filled.FitnessCenter),
                        StatItem("Unlocks", week.sumOf { it.unlocks }.toString(), Icons.Filled.LockOpen)
                    )
                )
                Spacer(Modifier.height(20.dp))
                Text("Repetitions per day", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                WeeklyBarChart(week)
            } else {
                PagCard(Modifier.fillMaxWidth()) {
                    Text("No activity yet this week", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Complete an activity to start seeing your stats here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Monthly trends & full history", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!settings.isProActive) Icon(Icons.Filled.WorkspacePremium, contentDescription = "Pro", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            if (settings.isProActive) {
                SummaryRow(
                    listOf(
                        StatItem("Repetitions", month.sumOf { it.repetitions }.toString(), Icons.Filled.Repeat),
                        StatItem("Sessions", month.sumOf { it.sessions }.toString(), Icons.Filled.FitnessCenter),
                        StatItem("Unlocks", month.sumOf { it.unlocks }.toString(), Icons.Filled.LockOpen)
                    )
                )
                Spacer(Modifier.height(20.dp))
                Text("Daily history", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (s.isEmpty()) {
                    Text("No history yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    s.take(30).forEach { day ->
                        PagCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(formatFullDate(day.dateKey), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(2.dp))
                            Text("${day.repetitions} reps • ${day.sessions} sessions • ${day.unlocks} unlocks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                PagCard(
                    Modifier.fillMaxWidth().clickable { upgradeReason = "30-day trends and full history" }
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Unlock 30-day trends and full daily history", style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text("Free plan shows the last 7 days only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Button({ upgradeReason = "30-day trends and full history" }) { Text("View Plans") }
                    }
                }
            }

            if (a.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text("Achievements", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                a.forEach { achievement ->
                    val (label, desc) = achievementInfo(achievement.key)
                    PagCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (UsageStatsHelper.hasUsageAccess(activity) && apps.any { it.isProtected }) {
                Spacer(Modifier.height(28.dp))
                Text("Protected-app usage today", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                apps.filter { it.isProtected }.forEach { app ->
                    PagCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(app.appName, style = MaterialTheme.typography.bodyMedium)
                            Text("${UsageStatsHelper.todayMinutes(activity, app.packageName)} min", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    upgradeReason?.let { reason ->
        ProUpgradeDialog(reason, onDismiss = { upgradeReason = null }, onUpgrade = { upgradeReason = null; nav.navigate("subscription") })
    }
}

@Composable private fun WeeklyBarChart(week: List<DailyStatisticsEntity>) {
    val days = week.reversed()
    val max = (days.maxOfOrNull { it.repetitions } ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEach { day ->
            val fraction = if (day.repetitions > 0) (day.repetitions.toFloat() / max).coerceIn(0.06f, 1f) else 0f
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (day.repetitions > 0) "${day.repetitions}" else "", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.weight(1f).fillMaxWidth(0.55f)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                            .align(Alignment.BottomCenter)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (day.repetitions > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(formatWeekday(day.dateKey), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatWeekday(dateKey: String): String = runCatching {
    java.time.LocalDate.parse(dateKey).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
}.getOrDefault(dateKey.takeLast(2))

private fun formatFullDate(dateKey: String): String = runCatching {
    val d = java.time.LocalDate.parse(dateKey)
    val weekday = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
    val month = d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
    "$weekday, $month ${d.dayOfMonth}"
}.getOrDefault(dateKey)

private fun achievementInfo(key: String): Pair<String, String> = when (key) {
    "FIRST_SESSION" -> "First Session" to "Completed your first activity"
    "TEN_SESSIONS" -> "10 Sessions" to "Completed 10 activity sessions"
    "HUNDRED_REPS" -> "100 Reps" to "Completed 100 total repetitions"
    "THOUSAND_REPS" -> "1000 Reps" to "Completed 1,000 total repetitions"
    else -> key.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() } to ""
}

@Composable fun SettingsScreen(vm: GateViewModel, activity: ComponentActivity, nav: NavHostController) {
    val settings by vm.settings.collectAsState(); var pin by remember { mutableStateOf("") }; var unlocked by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    var pinHint by remember { mutableStateOf<String?>(null) }
    var upgradeReason by remember { mutableStateOf<String?>(null) }
    val locked = settings.protectSettings && settings.pinHash != null && !unlocked
    // Building InterstitialAdHelper and calling load() inside remember{}
    // ran synchronously on this screen's very first composition frame —
    // every time you switched to this tab, since leaving it via the
    // bottom nav (popUpTo("home")) fully disposes it, so remember{} starts
    // over on every re-entry. That's what made the Settings tab switch
    // stutter instead of sliding in smoothly, the same issue the Apps
    // screen's installed-app scan had. Deferring the construction+load
    // into a post-composition LaunchedEffect lets the tab switch render
    // immediately; the ad simply finishes loading a beat later, exactly
    // like before.
    var interstitial by remember { mutableStateOf<InterstitialAdHelper?>(null) }
    LaunchedEffect(Unit) {
        interstitial = InterstitialAdHelper(activity).also { if (!settings.isProActive) it.load() }
    }
    // Save actions call this instead of interstitial.show(...) directly,
    // since interstitial can briefly be null right after entering this
    // screen (before the LaunchedEffect above finishes). Falls back to
    // running the action immediately in that case — the user's Save is
    // never blocked waiting for the ad helper to exist.
    fun runWithInterstitial(action: () -> Unit) {
        interstitial?.show(activity, action) ?: action()
    }
    Shell("Settings") {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
        if (locked) {
            Text("Settings are protected by your PIN.", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(12.dp)); OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, singleLine = true); Spacer(Modifier.height(8.dp)); Button({ scope.launch { if (Repository(AppDatabase.get(activity)).checkPin(pin)) { unlocked = true; pin = "" } } }) { Text("Unlock Settings") }
        } else {
            Card(
                Modifier.fillMaxWidth().clickable { nav.navigate("subscription") },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = if (settings.isProActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = if (settings.isProActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (settings.isProActive) "Physical Activity Gate Pro" else "Upgrade to Pro", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (settings.devProOverride) "Developer test mode active" else if (settings.isPro) "Plan: ${settings.proPlan ?: "active"}" else "Unlimited apps, all exercises, and more",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                }
            }
            Spacer(Modifier.height(16.dp))
            AccessibilityStatusBanner()

            SectionHeader("Protection")
            PagCard(Modifier.fillMaxWidth()) {
                SettingSwitch("Camera verification", settings.cameraVerification) { vm.saveSettings(settings.copy(cameraVerification = it)) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
                SettingSwitch("Require activity every time", settings.requireEveryTime) { vm.saveSettings(settings.copy(requireEveryTime = it)) }
                if (settings.requireEveryTime) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your chosen authorization duration is always honored. This just removes the \"Skip for now\" bypass — once your time is up, you always have to actually do the activity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
                Text("Emergency bypass", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("OFF" to "Off (no bypass allowed)", "ONE_PER_DAY" to "One bypass per day", "CONFIRM" to "Allow with confirmation").forEach { (mode, label) ->
                            val locked = mode == "CONFIRM" && !settings.isProActive
                            Row(
                                Modifier.fillMaxWidth().clickable { if (locked) upgradeReason = "\"Allow with confirmation\" bypass mode" else vm.saveSettings(settings.copy(bypassMode = mode)) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = settings.bypassMode == mode, onClick = null, enabled = !locked)
                                Spacer(Modifier.width(4.dp))
                                Text(label, style = MaterialTheme.typography.bodyMedium, color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                                if (locked) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Filled.WorkspacePremium, contentDescription = "Pro", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
                Text("Limits", style = MaterialTheme.typography.titleSmall)
                if (!settings.isProActive) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WorkspacePremium, contentDescription = "Pro", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("Pro feature", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().let { if (!settings.isProActive) it.clickable { upgradeReason = "Daily unlock limits" } else it },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Daily unlock limit (0 = off)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (!settings.isProActive) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                    OutlinedTextField(settings.maxUnlocksPerDay.toString(), { vm.saveSettings(settings.copy(maxUnlocksPerDay = it.filter(Char::isDigit).toIntOrNull() ?: 0)) }, Modifier.width(100.dp), singleLine = true, enabled = settings.isProActive, shape = RoundedCornerShape(12.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().let { if (!settings.isProActive) it.clickable { upgradeReason = "Cooldown periods" } else it },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cooldown minutes", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (!settings.isProActive) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                    OutlinedTextField(settings.cooldownMinutes.toString(), { vm.saveSettings(settings.copy(cooldownMinutes = it.filter(Char::isDigit).toIntOrNull() ?: 0)) }, Modifier.width(100.dp), singleLine = true, enabled = settings.isProActive, shape = RoundedCornerShape(12.dp))
                }
            }

            SectionHeader("Exercise Rules")
            PagCard(Modifier.fillMaxWidth()) {
                Text("Defaults for newly protected apps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text("Default exercise: ${ExerciseType.entries.firstOrNull { it.name == settings.defaultExercise }?.label ?: settings.defaultExercise}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("SQUATS", "PUSH_UPS", "JUMPING_JACKS", "PLANK", "STEPS", "SIT_UPS").forEach { key ->
                        FilterChip(selected = settings.defaultExercise == key, onClick = { vm.saveSettings(settings.copy(defaultExercise = key)) }, label = { Text(key.replace("_", " ").lowercase()) })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Default quantity / seconds", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(settings.defaultTarget.toString(), { vm.saveSettings(settings.copy(defaultTarget = it.filter(Char::isDigit).toIntOrNull()?.coerceAtLeast(1) ?: 1)) }, Modifier.width(120.dp), singleLine = true, shape = RoundedCornerShape(12.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("Default authorization", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30, 60).forEach { mins -> FilterChip(selected = settings.authorizationMinutes == mins, onClick = { vm.saveSettings(settings.copy(authorizationMinutes = mins)) }, label = { Text("$mins min") }) }
                    FilterChip(
                        selected = settings.authorizationMinutes < 0,
                        onClick = { if (settings.isProActive) vm.saveSettings(settings.copy(authorizationMinutes = -1)) else upgradeReason = "\"Until app is closed\" authorization" },
                        label = { Text("Until app closes" + if (!settings.isProActive) " 👑" else "") }
                    )
                }
            }

            SectionHeader("Feedback")
            PagCard(Modifier.fillMaxWidth()) {
                SettingSwitch("Haptic feedback", settings.hapticFeedback) { vm.saveSettings(settings.copy(hapticFeedback = it)) }
                SettingSwitch("Sound feedback", settings.sound) { vm.saveSettings(settings.copy(sound = it)) }
                SettingSwitch("Local notifications", settings.notifications) { enabled ->
                    vm.saveSettings(settings.copy(notifications = enabled))
                    if (enabled && Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
                    }
                }
                SettingSwitch("Usage statistics", settings.usageStats) { vm.saveSettings(settings.copy(usageStats = it)) }
            }

            SectionHeader("Security")
            PagCard(Modifier.fillMaxWidth()) {
                SettingSwitch("Protect settings with PIN", settings.protectSettings) { checked ->
                    if (checked && settings.pinHash == null) {
                        pinHint = "Set a PIN below first, then turn this on."
                    } else {
                        pinHint = null
                        vm.saveSettings(settings.copy(protectSettings = checked))
                    }
                }
                pinHint?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    pin,
                    { pin = it.filter(Char::isDigit).take(12) },
                    label = { Text(if (settings.pinHash == null) "Set a PIN (4+ digits)" else "Change PIN (4+ digits)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(10.dp))
                Button({
                    if (pin.length >= 4) {
                        if (settings.isProActive) {
                            vm.setPin(pin); pin = ""; pinHint = null
                        } else {
                            runWithInterstitial {
                                vm.setPin(pin); pin = ""; pinHint = null
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (settings.pinHash == null) "Save PIN" else "Update PIN")
                }
            }

            SectionHeader("Permissions")
            PagCard(Modifier.fillMaxWidth()) {
                Text(
                    "Some Android manufacturers (Xiaomi, Vivo, Oppo, and similar) kill accessibility services in the background unless the app is exempted from battery optimization. If blocking stops working after a while, check this first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                PermissionButtonGrid(activity)
            }

            SectionHeader("Other")
            PagCard(Modifier.fillMaxWidth()) {
                Text("Privacy: camera frames and activity data remain on-device. No account or server is required.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { nav.navigate("guide") }) { Text("How to use this app (guide)") }
                TextButton(onClick = {
                    PrivacyPolicyHelper.open(activity)
                }) { Text("View Privacy Policy") }
                TextButton(onClick = {
                    android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                        data = android.net.Uri.parse("mailto:akpolstudio@gmail.com")
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Physical Activity Gate - Support")
                    }.also { runCatching { activity.startActivity(it) } }
                }) { Text("Contact Support — akpolstudio@gmail.com") }
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }
    upgradeReason?.let { reason ->
        ProUpgradeDialog(reason, onDismiss = { upgradeReason = null }, onUpgrade = { upgradeReason = null; nav.navigate("subscription") })
    }
}

@Composable private fun SectionHeader(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))
}

@Composable private fun PermissionButtonGrid(activity: ComponentActivity) {
    data class PermButton(val label: String, val icon: ImageVector, val action: () -> Unit)
    val buttons = buildList {
        add(PermButton("Accessibility", Icons.Filled.Accessibility) { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        add(PermButton("Battery Exemption", Icons.Filled.BatteryChargingFull) { requestBatteryExemption(activity) })
        add(PermButton("Usage Access", Icons.Filled.QueryStats) { activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        if (Build.VERSION.SDK_INT >= 33) {
            add(PermButton("Notifications", Icons.Filled.Notifications) { ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001) })
        }
        // Without this, Android 12+ silently falls back to an *inexact*
        // alarm for the expiry-warning/expired notifications (see
        // NotificationHelper.setAlarmCompat) — they still arrive, but can
        // be batched/delayed by the OS instead of firing right on time.
        // There was previously no way to grant this from inside the app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Kept short ("Exact Alarms" not "Alarms & Reminders") so it
            // fits on one line in this half-width grid slot the same way
            // "Usage Access" and "Notifications" already do — the longer
            // label was getting clipped mid-word since these buttons use
            // maxLines = 1 with no ellipsis/shrink-to-fit.
            add(PermButton("Exact Alarms", Icons.Filled.Alarm) {
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(android.net.Uri.parse("package:${activity.packageName}")))
                }
            })
        }
    }
    buttons.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { b ->
                OutlinedButton(
                    onClick = b.action,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(b.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    // Ellipsis added as a safety net: without it, a label
                    // that doesn't fit this half-width slot gets clipped
                    // mid-word (e.g. "Alarms &") instead of trailing off
                    // cleanly — see the "Exact Alarms" label above for why
                    // this button in particular needed to stay short too.
                    Text(b.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** Tries the direct per-app battery-exemption dialog first, then falls back
 * progressively so the button always opens *something* useful instead of
 * silently doing nothing. */
private fun requestBatteryExemption(activity: ComponentActivity) {
    val pkg = activity.packageName
    val attempts = listOf<() -> Unit>(
        { activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(android.net.Uri.parse("package:$pkg"))) },
        { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:$pkg"))) }
    )
    for (attempt in attempts) {
        if (runCatching(attempt).isSuccess) return
    }
}

@Composable private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

/**
 * Pro plan comparison + purchase screen.
 *
 * DEVELOPER TESTING: double-tap either plan card to instantly toggle a local
 * "Pro unlocked" override, with no real purchase or network call involved.
 * This is only meant for you to verify the gated UI on your own device
 * before publishing. Remove the onDoubleClick handlers (and the
 * devProOverride field) once you're done testing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun SubscriptionScreen(vm: GateViewModel, activity: ComponentActivity) {
    val settings by vm.settings.collectAsState()
    val billing = remember { BillingManager(activity) }
    val billingState by billing.state.collectAsState()

    DisposableEffect(Unit) {
        billing.start()
        onDispose { billing.end() }
    }

    val restoreSnackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(billingState.restoreMessage) {
        billingState.restoreMessage?.let {
            restoreSnackbarHost.showSnackbar(it)
            billing.clearRestoreMessage()
        }
    }

    Shell("Physical Activity Gate Pro") {
        Box(Modifier.fillMaxWidth().weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            if (settings.devProOverride) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Developer test mode", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(4.dp))
                        Text("Pro is unlocked locally for testing — no real purchase was made.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { vm.toggleDevPro() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Turn Off Test Mode") }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (settings.isProActive) {
                PagCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("You're on Pro", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (settings.isPro) "Plan: ${settings.proPlan ?: "active"}" else "Unlocked via developer test mode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (settings.isPro) {
                    OutlinedButton(
                        onClick = { activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/account/subscriptions?package=${activity.packageName}"))) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Manage Subscription on Google Play") }
                }
            } else {
                Text("Upgrade to Pro", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text("Unlimited protected apps, every exercise type, daily limits, cooldowns, and more.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp))

                PlanCard(
                    title = "Monthly",
                    priceText = billingState.monthlyPriceText ?: "₹49 / month",
                    isPlaceholder = billingState.monthlyPriceText == null,
                    onClick = { billing.launchPurchase(activity, PRO_MONTHLY_ID) },
                    onDoubleClick = { vm.toggleDevPro() }
                )
                Spacer(Modifier.height(12.dp))
                PlanCard(
                    title = "Yearly",
                    priceText = billingState.yearlyPriceText ?: "₹399 / year",
                    isPlaceholder = billingState.yearlyPriceText == null,
                    badge = "Best value",
                    onClick = { billing.launchPurchase(activity, PRO_YEARLY_ID) },
                    onDoubleClick = { vm.toggleDevPro() }
                )

                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = { billing.restorePurchases() },
                    enabled = !billingState.restoring,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (billingState.restoring) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (billingState.restoring) "Restoring purchases…" else "Restore purchases")
                }

                billingState.lastError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Free plan: up to $FREE_APP_LIMIT protected apps, squats/push-ups/steps, fixed authorization durations.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(24.dp))
        }
        SnackbarHost(restoreSnackbarHost, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun PlanCard(
    title: String,
    priceText: String,
    isPlaceholder: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    badge?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondary).padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(priceText + if (isPlaceholder) " (placeholder — set in Play Console)" else "", style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}
