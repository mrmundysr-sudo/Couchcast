package com.mundymorning.couchcast

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.mundymorning.couchcast.ui.CouchcastApp

/** FragmentActivity (a ComponentActivity) because the Cast device chooser is a dialog fragment. */
class MainActivity : FragmentActivity() {
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
