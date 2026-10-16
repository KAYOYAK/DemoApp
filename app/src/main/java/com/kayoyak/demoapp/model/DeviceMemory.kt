package com.kayoyak.demoapp.model

import android.app.ActivityManager
import android.content.Context

/**
 * How much memory this device has.
 *
 * Read once and held, rather than on each use. Total RAM does not change while
 * the app is running, and a value that could move would make the same model
 * acceptable on one screen and too big on the next.
 *
 * This reads the *total*, not the free amount. Free memory moves by hundreds of
 * megabytes between one moment and the next -- a browser opening, a photo
 * syncing -- and a check against it would approve a load and then refuse the
 * identical one a second later. What the device came with is a fact about it.
 */
class DeviceMemory(private val totalBytes: Long) {

    /** What this device has, in bytes. */
    val total: Long get() = totalBytes

    /** How much of it a single model may be given. */
    val modelBudget: Long get() = ModelMemory.budgetBytes(totalBytes)

    /** What this device would do about a model of [fileSizeBytes]. */
    fun judge(fileSizeBytes: Long): ModelMemory.Verdict =
        ModelMemory.judge(fileSizeBytes, totalBytes)

    companion object {
        /**
         * Reads the device's total RAM.
         *
         * Zero when the system will not say, which [ModelMemory.judge] treats as
         * "no opinion" rather than "no memory" -- refusing every model on a device
         * whose total could not be read would be a worse outcome than trying.
         */
        fun from(context: Context): DeviceMemory {
            val manager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return DeviceMemory(0L)
            val info = ActivityManager.MemoryInfo()
            return runCatching {
                manager.getMemoryInfo(info)
                DeviceMemory(info.totalMem)
            }.getOrElse { DeviceMemory(0L) }
        }
    }
}
