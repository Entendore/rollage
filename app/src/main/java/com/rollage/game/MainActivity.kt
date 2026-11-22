package com.rollage.game

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private lateinit var gameView: GameView
    private lateinit var adContainer: FrameLayout
    private val ads get() = (application as RollageApp).ads

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gameView = GameView(this)
        val root = FrameLayout(this)
        root.addView(gameView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        adContainer = FrameLayout(this)
        root.addView(adContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))
        setContentView(root)

        // When the banner shows/hides, give the game view the space back.
        ads.attachBanner(this, adContainer) { h ->
            val lp = gameView.layoutParams as? FrameLayout.LayoutParams ?: return@attachBanner
            if (lp.bottomMargin != h) { lp.bottomMargin = h; gameView.layoutParams = lp }
        }
        ads.bootstrap(this)   // UMP consent -> SDK init -> preload (all async)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!gameView.handleBack()) finish()
            }
        })

        applyImmersive()
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        ctl.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onPause() { super.onPause(); gameView.pause(); ads.pauseBanner() }
    override fun onResume() { super.onResume(); gameView.resume(); ads.resumeBanner() }
    override fun onDestroy() { super.onDestroy(); gameView.pause(); ads.releaseBanner() }
}
