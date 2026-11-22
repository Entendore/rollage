package com.rollage.game

import android.util.Log

object L {
    fun d(msg: String) { if (BuildConfig.DEBUG) Log.d("ROLLAGE", msg) }
    fun e(msg: String, t: Throwable? = null) { Log.e("ROLLAGE", msg, t) }
}
