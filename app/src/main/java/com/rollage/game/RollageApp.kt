package com.rollage.game

import android.app.Application

class RollageApp : Application() {
    val ads by lazy { AdManager(this) }
}
