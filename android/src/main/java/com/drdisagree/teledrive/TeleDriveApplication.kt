package com.drdisagree.teledrive

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.drdisagree.teledrive.core.common.AppNotifications
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.logging.DebugLogController
import com.drdisagree.teledrive.core.proxy.ProxyFailover
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.core.transfer.MaintenanceScheduler
import com.drdisagree.teledrive.core.transfer.MediaStoreWatcher
import com.drdisagree.teledrive.core.transfer.MediaTriggerService
import com.drdisagree.teledrive.di.appModules
import com.drdisagree.teledrive.data.repository.FolderOwnershipRepair
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

class TeleDriveApplication : Application(), SingletonImageLoader.Factory {

    private val imageLoader: ImageLoader by inject()

    private val appNotifications: AppNotifications by inject()

    private val maintenanceScheduler: MaintenanceScheduler by inject()

    private val settingsRepository: SettingsRepository by inject()

    private val transferRepository: TransferRepository by inject()

    private val fileRepository: FileRepository by inject()

    private val folderOwnershipRepair: FolderOwnershipRepair by inject()

    private val trashRepository: TrashRepository by inject()

    private val mediaStoreWatcher: MediaStoreWatcher by inject()

    private val publishScheduler: PublishScheduler by inject()

    private val proxyFailover: ProxyFailover by inject()

    private val debugLogController: DebugLogController by inject()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    override fun onCreate() {
        super.onCreate()
        SafeLog.verbose = BuildConfig.DEBUG
        startKoin {
            androidContext(this@TeleDriveApplication)
            workManagerFactory()
            modules(appModules)
        }
        appNotifications.createChannels()
        mediaStoreWatcher.start()
        proxyFailover.start(applicationScope)
        debugLogController.start(applicationScope, alwaysVerbose = BuildConfig.DEBUG)
        
        // ব্যাকগ্রাউন্ড অ্যালার্ম ট্রিগার ইনিশিয়ালাইজ করা (ইনফিনিক্স বা XOS ডিভাইসের জন্য)
        scheduleBackgroundAlarm()

        applicationScope.launch {
            transferRepository.recoverOrphanedTransfers()
            fileRepository.sweepImportOrphans()
            folderOwnershipRepair.runOnce()
            folderOwnershipRepair.refileStrayFiles()
            publishScheduler.kick()
            trashRepository.repairTrashTree()
            val prefs = settingsRepository.preferences.first()
            maintenanceScheduler.scheduleAll(
                backupEnabled = prefs.autoBackupEnabled && prefs.backupIntervalHours > 0,
                backupIntervalHours = prefs.backupIntervalHours,
                wifiOnly = prefs.backupWifiOnly,
                chargingOnly = prefs.backupChargingOnly,
                instantBackup = prefs.instantBackupEnabled,
                updateChecks = prefs.updateCheckEnabled
            )
        }
    }

    private fun scheduleBackgroundAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, MediaTriggerService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // প্রতি ১৫ মিনিট পরপর অ্যালার্ম ট্রিগার করার জন্য সেট করা হলো
            val triggerTime = System.currentTimeMillis() + (15 * 60 * 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
