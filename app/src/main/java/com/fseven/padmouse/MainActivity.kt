package com.fseven.padmouse

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * M0 空壳主界面：仅展示占位状态，不包含任何业务逻辑。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
