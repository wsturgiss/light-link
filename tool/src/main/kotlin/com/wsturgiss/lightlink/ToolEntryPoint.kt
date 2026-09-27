package com.wsturgiss.lightlink

import android.util.Log
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {

    /** Send-only for now: nothing reaches the phone, so there is nothing to push. */
    override val enablePushNotifications: Boolean = false

    override suspend fun onToolCreate(
        serverData: StateFlow<LightServerData?>,
    ) {
        serverData.collect {
            Log.d("ToolEntryPoint", "Current LightOS registration data: $it")
        }
    }

    override suspend fun onPushNotification(
        data: ByteArray,
    ) = Unit
}
