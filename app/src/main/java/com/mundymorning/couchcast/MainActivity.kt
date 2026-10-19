package com.mundymorning.couchcast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.mundymorning.couchcast.ui.CouchcastApp

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CouchcastApp(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshRooms()
    }
}
