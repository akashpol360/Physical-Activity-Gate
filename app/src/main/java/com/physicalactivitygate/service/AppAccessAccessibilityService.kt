package com.physicalactivitygate.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import com.physicalactivitygate.GateActivity
import com.physicalactivitygate.NotificationHelper
import com.physicalactivitygate.data.AppDatabase
import com.physicalactivitygate.data.Repository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppAccessAccessibilityService : AccessibilityService() {
    // Window-state events must be processed in the exact order Android
    // delivers them. Dispatchers.IO is backed by a multi-thread pool, so
    // two events fired in quick succession (e.g. a fast app switch) were
    // not guaranteed to reach eventMutex.withLock in the order they were
    // launched — a coroutine started slightly later could win the race to
    // acquire the lock first. That reordering could make the service think
    // a still-foreground protected app was "previous" and briefly treat a
    // stale/foreground package as authorized, occasionally letting a
    // protected app open without the gate. Confining this scope to a
    // single thread makes event processing strictly FIFO while keeping it
    // off the main thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var currentForegroundPackage: String? = null
    private val eventMutex = Mutex()

    // Receives ACTION_FORCE_CHECK from ExpiryReceiver — fires the gate
    // immediately against the currently-foreground app when authorization
    // expires, even if the user is actively inside the protected app and
    // no natural window-state event has occurred.
    private val forceCheckReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val expiredPkg = intent.getStringExtra("package") ?: return
            scope.launch {
                eventMutex.withLock {
                    val repo = Repository(AppDatabase.get(applicationContext))
                    // Revoke first (in case ExpiryReceiver's runBlocking hasn't
                    // fully committed yet when this fires).
                    repo.revoke(expiredPkg)
                    // Always launch the gate if this package was the last one
                    // we saw in the foreground OR if currentForegroundPackage
                    // is null (phone was idle while app was open — service never
                    // fired a new event, so the package is still "open" from the
                    // OS perspective). Do NOT skip if currentForegroundPackage
                    // is null — that's exactly the case where the user is sitting
                    // still inside the protected app when the timer fires.
                    val isForeground = currentForegroundPackage == expiredPkg ||
                        currentForegroundPackage == null
                    if (isForeground) {
                        withContext(Dispatchers.Main) {
                            startActivity(
                                Intent(this@AppAccessAccessibilityService, GateActivity::class.java).apply {
                                    putExtra(GateActivity.EXTRA_PACKAGE, expiredPkg)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * GateActivity writes the package name here BEFORE calling
         * startActivity() + finish(). The service checks this map first —
         * it's in-memory and synchronous, so it can never lose the race
         * against a Room write that hasn't been flushed to disk yet.
         *
         * Without this, the sequence was:
         *   1. GateActivity: repo.authorize(pkg)  ← Room write starts (async)
         *   2. GateActivity: startActivity(pkg)   ← protected app window appears
         *   3. Service:      onAccessibilityEvent  ← fires immediately
         *   4. Service:      repo.authorized(pkg)  ← reads DB before step 1 commits
         *   5. Service:      "not authorized" → re-launches GateActivity
         *   6. GateActivity: Room write finally commits
         *   → open/close/open/close flicker loop
         *
         * With this map:
         *   Step 4 checks handOffInProgress first → sees pkg → skips entirely
         *   No DB read needed, no race possible.
         */
        @Volatile
        var handOffInProgress: String? = null

        /**
         * How long (ms) to suppress re-gating after a hand-off. Must be longer
         * than the target app's launch animation (~300ms on most devices) but
         * short enough not to create a noticeable gap in protection.
         */
        const val HAND_OFF_SUPPRESS_MS = 1500L
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
            // Fires whenever the set of on-screen windows itself changes —
            // a window appearing, disappearing, or resizing — regardless of
            // which one has focus. A floating "pop-up view" window (Samsung)
            // or small/split-screen window (MIUI and others) can appear
            // without the main foreground window ever losing focus, so it
            // never produces its own TYPE_WINDOW_STATE_CHANGED and the
            // handler above never sees it. This event does, which is why we
            // also scan for it below.
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scanAllWindowsForBypass()
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // our own windows never gate themselves

        // Android can report the launcher or another transition window while
        // the authorized app is being opened. Do not interpret those events
        // as the user closing the protected app. Keep the target foreground
        // for the whole handoff window; after it ends, normal close detection
        // resumes and revokes the grant when the user leaves the app.
        val handOffTarget = handOffInProgress
        if (handOffTarget != null) {
            if (pkg == handOffTarget) currentForegroundPackage = handOffTarget
            return
        }

        // A system permission prompt, the share sheet, the notification
        // shade, an autofill/password overlay, etc. is not a real app
        // switch: none of them have a home-screen launch entry, and the
        // user hasn't actually left whatever was open underneath. Treating
        // any window change as "left the app" meant one of these transient
        // overlays appearing over an "until app is closed" session — while
        // the user was still genuinely inside that app — started the
        // delayed revoke below, and if the overlay stayed up any
        // longer than that (a permission dialog the user is reading, a
        // share sheet, a phone call banner), the session got revoked and
        // the gate reappeared even though nothing was ever closed. Ignoring
        // these events entirely (leaving currentForegroundPackage exactly
        // as it was) fixes that without changing behavior for genuine app
        // switches, which always go through a real launcher-visible app.
        if (!isRealForegroundApp(pkg)) return

        // If GateActivity just handed off to this exact package, suppress all
        // events for it during the launch-animation window. This is the primary
        // fix for the open/close flicker — checked before any coroutine or DB
        // access so it's effectively instantaneous.
        scope.launch {
            eventMutex.withLock {
                val repo = Repository(AppDatabase.get(applicationContext))

                if (currentForegroundPackage != null && currentForegroundPackage != pkg) {
                    val previous = currentForegroundPackage!!
                    val previousApp = repo.app(previous)
                    if (previousApp?.isProtected == true) {
                        // Check the grant that was actually issued, not the
                        // app's current rule — the rule reflects what a
                        // *future* exercise would grant and can be changed
                        // by the user mid-session (e.g. switching the app
                        // from "until closed" to a timed duration while
                        // already inside it). Reading the rule here made
                        // that edit silently strand the earlier unlimited
                        // grant active forever, since nothing else ever
                        // revokes it apart from this exact check.
                        val grant = repo.currentAuthorization(previous)
                        val requireEveryTime = repo.getSettings().requireEveryTime
                        if (grant != null && (grant.expiresAt == Long.MAX_VALUE || requireEveryTime)) {
                            // A genuine launcher-visible package transition
                            // means this app has left the foreground. Revoke
                            // immediately so reopening it—even quickly—always
                            // requires the activity again. Handoff transitions
                            // are filtered above before reaching this branch.
                            repo.revoke(previous)
                            if (repo.getSettings().notifications) {
                                NotificationHelper.notifyExpired(applicationContext, previous)
                            }
                        }
                    }
                }
                currentForegroundPackage = pkg

                val app = repo.app(pkg) ?: return@launch
                if (!app.isProtected) return@launch

                // Second check: also consult the DB (handles the case where
                // handOffInProgress already cleared but the app is still within
                // its valid authorization window from a previous exercise).
                if (repo.authorized(pkg)) return@launch

                withContext(Dispatchers.Main) {
                    startActivity(Intent(this@AppAccessAccessibilityService, GateActivity::class.java).apply {
                        putExtra(GateActivity.EXTRA_PACKAGE, pkg)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                }
            }
        }
    }

    // Second, additive layer of detection for windows that never trigger a
    // full TYPE_WINDOW_STATE_CHANGED for their own package — floating
    // "pop-up view" windows and small/split-screen panes on some OEM skins.
    // This never revokes anything and never touches currentForegroundPackage
    // (that stays purely driven by handleWindowStateChanged, so the "until
    // app is closed" leave-detection above is completely unaffected); it
    // only ever looks for a protected, unauthorized app sitting in some
    // window and gates it, exactly like the normal path would have if that
    // window had produced its own focus-change event.
    private fun scanAllWindowsForBypass() {
        scope.launch {
            eventMutex.withLock {
                val repo = Repository(AppDatabase.get(applicationContext))
                val seen = mutableSetOf<String>()
                for (window in windows) {
                    val pkg = window.root?.packageName?.toString() ?: continue
                    if (pkg == packageName || pkg == handOffInProgress) continue
                    if (!seen.add(pkg)) continue
                    if (!isRealForegroundApp(pkg)) continue
                    val app = repo.app(pkg) ?: continue
                    if (!app.isProtected) continue
                    if (repo.authorized(pkg)) continue

                    withContext(Dispatchers.Main) {
                        startActivity(Intent(this@AppAccessAccessibilityService, GateActivity::class.java).apply {
                            putExtra(GateActivity.EXTRA_PACKAGE, pkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(NotificationHelper.ACTION_FORCE_CHECK)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(forceCheckReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(forceCheckReceiver, filter)
        }
        // Reconcile open-ended grants after a service restart. Preserve the
        // grant for the app that is still visibly in the foreground; revoke
        // stale grants for every other app because their close event may
        // have been missed while the service was down.
        val foreground = rootInActiveWindow?.packageName?.toString()
        scope.launch {
            val repo = Repository(AppDatabase.get(applicationContext))
            repo.activeUnlimitedSessions()
                .map { it.packageName }
                .filter { it != foreground }
                .distinct()
                .forEach { repo.revoke(it) }
            if (foreground != null && repo.app(foreground)?.isProtected == true && !repo.authorized(foreground)) {
                withContext(Dispatchers.Main) {
                    startActivity(Intent(this@AppAccessAccessibilityService, GateActivity::class.java).apply {
                        putExtra(GateActivity.EXTRA_PACKAGE, foreground)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    // True if pkg is something the user could actually tap into from the
    // home screen (a normal app, or the home launcher itself) — as opposed
    // to a system-owned window like a permission dialog, share sheet, or
    // notification shade that merely happens to draw on top of something
    // else. Every app the picker in MainActivity lets you protect is found
    // via ACTION_MAIN/CATEGORY_LAUNCHER, the same resolution
    // getLaunchIntentForPackage() uses, so this never misclassifies an
    // actual protected app.
    private fun isRealForegroundApp(pkg: String): Boolean {
        if (packageManager.getLaunchIntentForPackage(pkg) != null) return true
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == pkg
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(forceCheckReceiver) }
        scope.cancel()
        super.onDestroy()
    }
}
