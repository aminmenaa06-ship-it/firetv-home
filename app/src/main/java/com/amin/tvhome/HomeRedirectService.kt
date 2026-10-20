package com.amin.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Fire OS 8 marks its launcher as a protected package, so it can't be disabled and it outranks
 * any third-party Home app. Instead, watch for Amazon's home screen coming to the front (Home
 * button, Back out of an app, boot) and bring this launcher up over it.
 *
 * The service only receives window-state events from com.amazon.tv.launcher (see
 * res/xml/home_redirect.xml) and ignores everything but its home activity, so Amazon's settings
 * screens, which live in the same package, still open normally.
 */
class HomeRedirectService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != AMAZON_LAUNCHER) return
        if (event.className?.startsWith(AMAZON_HOME_PREFIX) != true) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        const val AMAZON_HOME_PREFIX = "com.amazon.tv.launcher.ui.HomeActivity"
    }
}
